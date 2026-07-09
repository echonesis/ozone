# HDDS-15492 gRPC OM Follower Read Verification

This note documents how to verify that OM follower read works for the gRPC OM client transport on a real OM HA compose cluster.

## Goal

Verify that a client read request:

1. Uses `GrpcOmTransportFactory`.
2. Has OM follower read enabled.
3. Completes successfully.
4. Increments `NumFollowerReadLocalLeaseSuccess` on an OM node that is currently a `FOLLOWER`.

The important part is the last item. A successful command alone only proves the client can talk to OM. The follower OM metric proves the read path actually exercised follower read.

## Branch and Change Under Test

Branch:

```bash
git checkout HDDS-15492
```

Relevant change:

```text
HDDS-15492. Support OM follower read for gRPC client
```

## Build Distribution

From the repository root:

```bash
mvn -Pdist -DskipTests package -DskipRecon -DskipDocs
```

This creates a runnable compose cluster under:

```text
hadoop-ozone/dist/target/ozone-*-SNAPSHOT/compose/ozone-om-ha
```

## Configure the OM HA Compose Cluster

Edit the generated compose config, not the source config, unless the change is intended to be committed:

```text
hadoop-ozone/dist/target/ozone-*-SNAPSHOT/compose/ozone-om-ha/docker-config
```

Add these local verification settings:

```properties
OZONE-SITE.XML_ozone.om.transport.class=org.apache.hadoop.ozone.om.protocolPB.GrpcOmTransportFactory
OZONE-SITE.XML_ozone.client.follower.read.enabled=true
OZONE-SITE.XML_ozone.client.follower.read.default.consistency=LOCAL_LEASE
OZONE-SITE.XML_ozone.om.follower.read.local.lease.enabled=true
```

`ozone-om-ha/docker-config` already contains:

```properties
OZONE-SITE.XML_ozone.om.s3.grpc.server_enabled=true
```

Do not add it again if it is already present.

Why these settings are needed:

| Setting | Purpose |
| --- | --- |
| `ozone.om.transport.class=GrpcOmTransportFactory` | Makes the Ozone client use the gRPC OM transport. |
| `ozone.client.follower.read.enabled=true` | Enables client-side follower read routing. |
| `ozone.client.follower.read.default.consistency=LOCAL_LEASE` | Uses the local lease follower read mode, which is easy to verify through OM metrics. |
| `ozone.om.follower.read.local.lease.enabled=true` | Enables server-side local lease handling on OM. |
| `ozone.om.s3.grpc.server_enabled=true` | Starts the OM gRPC server. |

## Start the Compose Cluster

From the generated compose directory:

```bash
cd hadoop-ozone/dist/target/ozone-*-SNAPSHOT/compose/ozone-om-ha
```

Start the containers:

```bash
docker compose up -d --build
```

In this compose layout, OM containers start with `sleep 1d`, so start each OM explicitly:

```bash
docker compose exec -T om1 /opt/startOM.sh
docker compose exec -T om2 /opt/startOM.sh
docker compose exec -T om3 /opt/startOM.sh
```

Check the containers:

```bash
docker compose ps
```

## Confirm OM Roles

Run:

```bash
docker compose exec -T scm ozone admin om roles --service-id=omservice
```

It is normal to see an `OMNotLeaderException` before the command prints the roles. The admin client may first contact a follower, then fail over to the leader.

Example:

```text
om3 : FOLLOWER (om3)
om2 : FOLLOWER (om2)
om1 : LEADER (om1)
```

## Ensure the Target OM Is a Follower

The compose HTTP ports map as follows:

| OM | HTTP port |
| --- | --- |
| `om1` | `localhost:9880` |
| `om2` | `localhost:9882` |
| `om3` | `localhost:9884` |

In the observed verification run, `om2` was the node whose follower-read metric changed. Therefore `om2` must be a follower for the result to prove follower read.

If `om2` is the leader, transfer leadership away from it:

```bash
docker compose exec -T scm ozone admin om transfer --service-id=omservice -n om1
docker compose exec -T scm ozone admin om roles --service-id=omservice
```

Expected role state for this verification:

```text
om1 : LEADER (om1)
om2 : FOLLOWER (om2)
```

## Capture Metrics Before the Read

Run:

```bash
curl -s http://localhost:9880/jmx | grep NumFollowerReadLocalLeaseSuccess
curl -s http://localhost:9882/jmx | grep NumFollowerReadLocalLeaseSuccess
curl -s http://localhost:9884/jmx | grep NumFollowerReadLocalLeaseSuccess
```

Observed before the read:

```text
"NumFollowerReadLocalLeaseSuccess" : 0,
"NumFollowerReadLocalLeaseSuccess" : 2,
"NumFollowerReadLocalLeaseSuccess" : 0,
```

This maps to:

| OM | Value |
| --- | --- |
| `om1` | `0` |
| `om2` | `2` |
| `om3` | `0` |

## Run a gRPC Client Read

Run a follower-read eligible request:

```bash
docker compose exec -T scm ozone sh volume list /
```

Expected evidence in the command output:

```text
GrpcOmTransport: started
```

This confirms the shell client used the gRPC OM transport.

The command should print the volume list successfully. Example:

```json
[ {
  "metadata" : { },
  "name" : "s3v",
  "admin" : "hadoop",
  "owner" : "hadoop"
} ]
```

It is acceptable to see an error similar to this during client initialization:

```text
ERROR GrpcOmTransport:276 - Failed to submit request
io.grpc.StatusRuntimeException: INTERNAL: org.apache.hadoop.ozone.om.exceptions.OMNotLeaderException:
OM:om2 is not the leader. Suggested leader is OM:om1[om1/...].
```

In the observed run, this came from `getServiceInfo` during client creation:

```text
at org.apache.hadoop.ozone.om.protocolPB.GrpcOmTransport.submitRequestToLeader
at org.apache.hadoop.ozone.om.protocolPB.OzoneManagerProtocolClientSideTranslatorPB.getServiceInfo
```

That initialization request is not the follower-read request being verified. The client failed over and the actual `volume list` command completed successfully.

## Capture Metrics After the Read

Run:

```bash
curl -s http://localhost:9880/jmx | grep NumFollowerReadLocalLeaseSuccess
curl -s http://localhost:9882/jmx | grep NumFollowerReadLocalLeaseSuccess
curl -s http://localhost:9884/jmx | grep NumFollowerReadLocalLeaseSuccess
```

Observed after the read:

```text
"NumFollowerReadLocalLeaseSuccess" : 0,
"NumFollowerReadLocalLeaseSuccess" : 4,
"NumFollowerReadLocalLeaseSuccess" : 0,
```

This maps to:

| OM | Before | After | Role |
| --- | ---: | ---: | --- |
| `om1` | `0` | `0` | `LEADER` |
| `om2` | `2` | `4` | `FOLLOWER` |
| `om3` | `0` | `0` | `FOLLOWER` |

Since `om2` was confirmed as a `FOLLOWER`, and its `NumFollowerReadLocalLeaseSuccess` increased from `2` to `4`, the gRPC client follower-read path was exercised successfully.

## Final Observed Evidence

OM roles:

```text
om3 : FOLLOWER (om3)
om2 : FOLLOWER (om2)
om1 : LEADER (om1)
```

Client transport evidence:

```text
GrpcOmTransport: started
```

Follower-read metric evidence:

```text
om2 NumFollowerReadLocalLeaseSuccess: 2 -> 4
```

Conclusion:

```text
The gRPC OM client successfully executed a follower-read eligible request, and the follower OM metric increased on om2 while om2 was a FOLLOWER.
```

## Reviewer Response

Suggested PR response:

```text
Tested this on an OM HA compose cluster with GrpcOmTransportFactory and OM follower read enabled.

Configuration used:
- ozone.om.transport.class=org.apache.hadoop.ozone.om.protocolPB.GrpcOmTransportFactory
- ozone.client.follower.read.enabled=true
- ozone.client.follower.read.default.consistency=LOCAL_LEASE
- ozone.om.follower.read.local.lease.enabled=true

After transferring leadership to om1, I confirmed om2 was a FOLLOWER through `ozone admin om roles --service-id=omservice`. Then I ran `ozone sh volume list /`; the client logged `GrpcOmTransport: started` and the command completed successfully. The follower OM metric `NumFollowerReadLocalLeaseSuccess` on om2 increased from 2 to 4 via JMX, confirming the gRPC client follower-read path was exercised against a follower OM.

During client initialization I also saw an expected `OMNotLeaderException` for `getServiceInfo` when the client contacted stale leader om2; it failed over to the suggested leader om1 and the command completed successfully.
```

## Cleanup

Stop the compose cluster when done:

```bash
docker compose down
```
