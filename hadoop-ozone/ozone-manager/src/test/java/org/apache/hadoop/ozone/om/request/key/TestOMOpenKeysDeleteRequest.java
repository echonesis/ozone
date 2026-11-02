/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.request.key;

import static org.apache.hadoop.ozone.om.codec.OMDBDefinition.OPEN_FILE_TABLE;
import static org.apache.hadoop.ozone.om.codec.OMDBDefinition.OPEN_KEY_TABLE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.client.ContainerBlockID;
import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.container.common.helpers.AllocatedBlock;
import org.apache.hadoop.hdds.scm.container.common.helpers.ExcludeList;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;
import org.apache.hadoop.hdds.utils.db.BatchOperation;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.audit.OMSystemAction;
import org.apache.hadoop.ozone.om.BucketManagerImpl;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.hadoop.ozone.om.OpenKeyDBKey;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmBucketInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.RepeatedOmKeyInfo;
import org.apache.hadoop.ozone.om.lock.OzoneLockProvider;
import org.apache.hadoop.ozone.om.request.OMClientRequest;
import org.apache.hadoop.ozone.om.request.OMRequestTestUtils;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.snapshot.filter.ReclaimableKeyFilter;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CommitKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.CreateKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.DeleteOpenKeysRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OpenKey;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OpenKeyBucket;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.RenameKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.Status;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * This class tests the OM Open Keys Delete Request.
 */
public class TestOMOpenKeysDeleteRequest extends OMKeyRequestTests {

  private BucketLayout bucketLayout;
  private boolean deferLifecycleFlush;

  @Override
  public BucketLayout getBucketLayout() {
    return bucketLayout;
  }

  public static Collection<BucketLayout> bucketLayouts() {
    return Arrays.asList(
        BucketLayout.DEFAULT,
        BucketLayout.FILE_SYSTEM_OPTIMIZED
    );
  }

  /**
   * Tests removing keys from the open key table cache that never existed there.
   * The operation should complete without errors.
   * <p>
   * This simulates a run of the open key cleanup service where a set of
   * expired open keys are identified and passed to the request, but before
   * the request can process them, those keys are committed and removed from
   * the open key table.
   * @throws Exception
   */
  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testDeleteOpenKeysNotInTable(
      BucketLayout buckLayout) throws Exception {
    this.bucketLayout = buckLayout;
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, bucketName,
            omMetadataManager, getBucketLayout());
    List<Pair<Long, OmKeyInfo>> openKeys =
        makeOpenKeys(volumeName, bucketName, 5);
    deleteOpenKeysFromCache(openKeys);
    assertNotInOpenKeyTable(openKeys);
  }

  /**
   * Tests adding multiple keys to the open key table, and updating the table
   * cache to only remove some of them.
   * Keys not removed should still be present in the open key table.
   * Mixes which keys will be kept and deleted among different volumes and
   * buckets.
   * @throws Exception
   */
  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testDeleteSubsetOfOpenKeys(
      BucketLayout buckLayout) throws Exception {
    this.bucketLayout = buckLayout;
    final String volume1 = UUID.randomUUID().toString();
    final String volume2 = UUID.randomUUID().toString();
    final String bucket1 = UUID.randomUUID().toString();
    final String bucket2 = UUID.randomUUID().toString();

    OMRequestTestUtils.addVolumeAndBucketToDB(volume1, bucket1,
            omMetadataManager, getBucketLayout());
    OMRequestTestUtils.addVolumeAndBucketToDB(volume1, bucket2,
            omMetadataManager, getBucketLayout());
    OMRequestTestUtils.addVolumeAndBucketToDB(volume2, bucket2,
            omMetadataManager, getBucketLayout());

    List<Pair<Long, OmKeyInfo>> v1b1KeysToDelete =
        makeOpenKeys(volume1, bucket1, 3);
    List<Pair<Long, OmKeyInfo>> v1b1KeysToKeep =
        makeOpenKeys(volume1, bucket1, 3);

    List<Pair<Long, OmKeyInfo>> v1b2KeysToDelete =
        makeOpenKeys(volume1, bucket2, 3);
    List<Pair<Long, OmKeyInfo>> v1b2KeysToKeep =
        makeOpenKeys(volume1, bucket2, 2);

    List<Pair<Long, OmKeyInfo>> v2b2KeysToDelete =
        makeOpenKeys(volume2, bucket2, 2);
    List<Pair<Long, OmKeyInfo>> v2b2KeysToKeep =
        makeOpenKeys(volume2, bucket2, 3);

    addToOpenKeyTableDB(
        v1b1KeysToKeep,
        v1b2KeysToKeep,
        v2b2KeysToKeep,
        v1b1KeysToDelete,
        v1b2KeysToDelete,
        v2b2KeysToDelete
    );

    deleteOpenKeysFromCache(
        v1b1KeysToDelete,
        v1b2KeysToDelete,
        v2b2KeysToDelete
    );

    assertNotInOpenKeyTable(
        v1b1KeysToDelete,
        v1b2KeysToDelete,
        v2b2KeysToDelete
    );

    assertInOpenKeyTable(
        v1b1KeysToKeep,
        v1b2KeysToKeep,
        v2b2KeysToKeep
    );
  }

  /**
   * Tests removing keys from the open key table cache that have the same
   * name, but different client IDs.
   * @throws Exception
   */
  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testDeleteSameKeyNameDifferentClient(
      BucketLayout buckLayout) throws Exception {
    this.bucketLayout = buckLayout;
    final String volume = UUID.randomUUID().toString();
    final String bucket = UUID.randomUUID().toString();
    final String key = UUID.randomUUID().toString();

    OMRequestTestUtils.addVolumeAndBucketToDB(volume, bucket,
            omMetadataManager, getBucketLayout());

    List<Pair<Long, OmKeyInfo>> keysToKeep =
        makeOpenKeys(volume, bucket, key, 3);
    List<Pair<Long, OmKeyInfo>> keysToDelete =
        makeOpenKeys(volume, bucket, key, 3);

    addToOpenKeyTableDB(keysToKeep, keysToDelete);
    deleteOpenKeysFromCache(keysToDelete);

    assertNotInOpenKeyTable(keysToDelete);
    assertInOpenKeyTable(keysToKeep);
  }

  /**
   * Tests removing keys from the open key table cache that have higher
   * updateID than the transactionID. Those keys should be ignored.
   * It is OK if updateID equals to or less than transactionID.
   * See {@link #setUpdateID(long)}.
   *
   * @throws Exception
   */
  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testDeleteKeyWithHigherUpdateID(
      BucketLayout buckLayout) throws Exception {
    this.bucketLayout = buckLayout;
    final String volume = UUID.randomUUID().toString();
    final String bucket = UUID.randomUUID().toString();

    OMRequestTestUtils.addVolumeAndBucketToDB(volume, bucket,
        omMetadataManager, getBucketLayout());

    final long updateId = 200L;
    final long transactionId = 100L;

    OmKeyInfo.Builder builder = new OmKeyInfo.Builder()
        .setVolumeName(volume)
        .setBucketName(bucket)
        .setUpdateID(updateId)
        .setReplicationConfig(ReplicationConfig.fromTypeAndFactor(
            ReplicationType.RATIS, ReplicationFactor.THREE));

    if (getBucketLayout().isFileSystemOptimized()) {
      builder.setParentObjectID(random.nextLong());
    }

    List<Pair<Long, OmKeyInfo>> keysWithHigherUpdateID = new ArrayList<>(1);
    keysWithHigherUpdateID.add(Pair.of(clientID,
        builder.setKeyName("key")
            .setUpdateID(updateId)
            .build()));

    List<Pair<Long, OmKeyInfo>> keysWithSameUpdateID = new ArrayList<>(1);
    keysWithSameUpdateID.add(Pair.of(clientID,
        builder.setKeyName("key2")
            .setUpdateID(transactionId)
            .build()));

    List<Pair<Long, OmKeyInfo>> allKeys = new ArrayList<>(2);
    allKeys.addAll(keysWithHigherUpdateID);
    allKeys.addAll(keysWithSameUpdateID);

    addToOpenKeyTableDB(allKeys);

    OMRequest omRequest = doPreExecute(createDeleteOpenKeyRequest(allKeys));
    OMOpenKeysDeleteRequest openKeyDeleteRequest =
        new OMOpenKeysDeleteRequest(omRequest, getBucketLayout());
    OMClientResponse omClientResponse =
        openKeyDeleteRequest.validateAndUpdateCache(ozoneManager, transactionId);

    assertEquals(Status.OK, omClientResponse.getOMResponse().getStatus());
    String expectedCleanupTable =
        getBucketLayout().isFileSystemOptimized() ? OPEN_FILE_TABLE : OPEN_KEY_TABLE;
    assertEquals(Collections.singleton(expectedCleanupTable),
        omClientResponse.removeCleanupTables());

    assertInOpenKeyTable(keysWithHigherUpdateID);
    assertNotInOpenKeyTable(keysWithSameUpdateID);
  }

  /**
   * Tests metrics set by {@link OMOpenKeysDeleteRequest}.
   * Submits a set of keys for deletion where only some of the keys actually
   * exist in the open key table, and asserts that the metrics count keys
   * that were submitted for deletion versus those that were actually deleted.
   * @throws Exception
   */
  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testMetrics(BucketLayout buckLayout) throws Exception {
    this.bucketLayout = buckLayout;
    final String volume = UUID.randomUUID().toString();
    final String bucket = UUID.randomUUID().toString();
    final String key = UUID.randomUUID().toString();
    final int numExistentKeys = 3;
    final int numNonExistentKeys = 5;

    OMRequestTestUtils.addVolumeAndBucketToDB(volume, bucket,
            omMetadataManager, getBucketLayout());

    OMMetrics metrics = ozoneManager.getMetrics();
    assertEquals(metrics.getNumOpenKeyDeleteRequests(), 0);
    assertEquals(metrics.getNumOpenKeyDeleteRequestFails(), 0);
    assertEquals(metrics.getNumOpenKeysSubmittedForDeletion(), 0);
    assertEquals(metrics.getNumOpenKeysDeleted(), 0);

    List<Pair<Long, OmKeyInfo>> existentKeys =
        makeOpenKeys(volume, bucket, key, numExistentKeys);
    List<Pair<Long, OmKeyInfo>> nonExistentKeys =
        makeOpenKeys(volume, bucket, key, numNonExistentKeys);

    addToOpenKeyTableDB(existentKeys);
    deleteOpenKeysFromCache(existentKeys, nonExistentKeys);

    assertNotInOpenKeyTable(existentKeys);
    assertNotInOpenKeyTable(nonExistentKeys);

    assertEquals(1, metrics.getNumOpenKeyDeleteRequests());
    assertEquals(0, metrics.getNumOpenKeyDeleteRequestFails());
    assertEquals(numExistentKeys + numNonExistentKeys,
        metrics.getNumOpenKeysSubmittedForDeletion());
    assertEquals(numExistentKeys, metrics.getNumOpenKeysDeleted());
  }

  /**
   * Test OPEN_KEY_CLEANUP audit logging for both success and failure cases when open keys are deleted.
   */
  @ParameterizedTest
  @MethodSource("bucketLayouts")
  public void testOpenKeyCleanupAuditLogging(BucketLayout buckLayout) throws Exception {
    this.bucketLayout = buckLayout;
    final String volume = UUID.randomUUID().toString();
    final String bucket = UUID.randomUUID().toString();

    OMRequestTestUtils.addVolumeAndBucketToDB(volume, bucket,
        omMetadataManager, getBucketLayout());

    List<Pair<Long, OmKeyInfo>> openKeys = makeOpenKeys(volume, bucket, 3);
    addToOpenKeyTableDB(openKeys);

    OMRequest omRequest = doPreExecute(createDeleteOpenKeyRequest(openKeys));
    OMOpenKeysDeleteRequest openKeyDeleteRequest = spy(new OMOpenKeysDeleteRequest(omRequest, getBucketLayout()));

    OMClientResponse omClientResponse =
        openKeyDeleteRequest.validateAndUpdateCache(ozoneManager, 100L);

    assertEquals(Status.OK, omClientResponse.getOMResponse().getStatus());

    verify(ozoneManager, times(1))
        .buildAuditMessageForSuccess(eq(OMSystemAction.OPEN_KEY_CLEANUP),
            argThat(params -> {
              assertEquals("3", params.get("numOpenKeysDeleted"));
              assertTrue(params.containsKey("openKeysDeleted"));
              return true;
            }));

    assertNotInOpenKeyTable(openKeys);

    // Simulate failure by mocking updateOpenKeyTableCache to throw an IOException, and verify the failure audit log.
    doThrow(new IOException())
        .when(openKeyDeleteRequest)
        .updateOpenKeyTableCache(
            any(OzoneManager.class),
            anyLong(),
            any(OpenKeyBucket.class),
            anyMap());

    omClientResponse = openKeyDeleteRequest.validateAndUpdateCache(ozoneManager, 100L);

    assertEquals(Status.INTERNAL_ERROR, omClientResponse.getOMResponse().getStatus());

    verify(ozoneManager, times(1))
        .buildAuditMessageForFailure(eq(OMSystemAction.OPEN_KEY_CLEANUP),
            argThat(Map::isEmpty),
            any(Throwable.class));
  }

  /**
   * Runs the validate and update cache step of
   * {@link OMOpenKeysDeleteRequest} to mark the keys in {@code openKeys}
   * as deleted in the open key table cache.
   * Asserts that the call's response status is {@link Status#OK}.
   * @throws Exception
   */
  private void  deleteOpenKeysFromCache(List<Pair<Long, OmKeyInfo>>... allKeys)
      throws Exception {

    deleteOpenKeysFromCache(Arrays.stream(allKeys)
        .flatMap(List::stream)
        .collect(Collectors.toList()));
  }

  private void deleteOpenKeysFromCache(List<Pair<Long, OmKeyInfo>> openKeys)
      throws Exception {

    OMRequest omRequest =
        doPreExecute(createDeleteOpenKeyRequest(openKeys));

    OMOpenKeysDeleteRequest openKeyDeleteRequest =
        new OMOpenKeysDeleteRequest(omRequest, getBucketLayout());

    OMClientResponse omClientResponse =
        openKeyDeleteRequest.validateAndUpdateCache(ozoneManager, 100L);
    assertEquals(Status.OK,
        omClientResponse.getOMResponse().getStatus());
    for (OmKeyInfo openKey : openKeys.stream().map(Pair::getRight).collect(Collectors.toList())) {
      assertEquals(0, omMetadataManager.getBucketTable().get(
          omMetadataManager.getBucketKey(openKey.getVolumeName(), openKey.getBucketName())).getSnapshotUsedBytes());
      assertEquals(0, omMetadataManager.getBucketTable().get(
          omMetadataManager.getBucketKey(openKey.getVolumeName(), openKey.getBucketName())).getSnapshotUsedNamespace());
    }
  }

  static Stream<Arguments> lifecycleScenarios() {
    return bucketLayouts().stream().flatMap(layout -> Stream.of("same", "renamed", "commit", "nonversioned")
        .map(scenario -> Arguments.of(layout, scenario)));
  }

  static Stream<Arguments> reclaimScenarios() {
    return bucketLayouts().stream().flatMap(layout -> Stream.of("same", "renamed", "absent", "empty", "shared",
        "partial", "history", "nonversioned", "reused", "multipart", "deletedHsync", "overwrittenHsync")
        .map(scenario -> Arguments.of(layout, scenario)));
  }

  @ParameterizedTest
  @MethodSource("lifecycleScenarios")
  public void testCleanupAfterCreateAndCommit(BucketLayout layout, String scenario) throws Exception {
    bucketLayout = layout;
    when(ozoneManager.getOzoneLockProvider()).thenReturn(
        new OzoneLockProvider(false, false));
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, omMetadataManager,
        OmBucketInfo.newBuilder().setVolumeName(volumeName).setBucketName(bucketName)
            .setBucketLayout(layout).setIsVersionEnabled(!scenario.equals("nonversioned")));
    when(ozoneManager.getBucketManager()).thenReturn(new BucketManagerImpl(ozoneManager, omMetadataManager));
    Pipeline pipeline = Pipeline.newBuilder().setId(PipelineID.randomId()).setState(Pipeline.PipelineState.OPEN)
        .setReplicationConfig(replicationConfig).setNodes(Collections.emptyList()).build();
    AtomicLong nextContainer = new AtomicLong(5001);
    when(scmBlockLocationProtocol.allocateBlock(anyLong(), anyInt(), any(ReplicationConfig.class),
        anyString(), any(ExcludeList.class), anyString())).thenAnswer(invocation -> {
          assertEquals(1, (int) invocation.getArgument(1));
          return Collections.singletonList(new AllocatedBlock.Builder().setPipeline(pipeline)
              .setContainerBlockID(new ContainerBlockID(nextContainer.getAndIncrement(), 1)).build());
        });
    BlockID b1 = new BlockID(5001, 1);
    BlockID b2 = new BlockID(5002, 1);
    KeyArgs args = KeyArgs.newBuilder().setVolumeName(volumeName).setBucketName(bucketName).setKeyName(keyName)
        .setType(HddsProtos.ReplicationType.RATIS)
        .setFactor(HddsProtos.ReplicationFactor.ONE).setDataSize(10).build();
    OMRequest create = OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
        .setCmdType(OzoneManagerProtocolProtos.Type.CreateKey)
        .setCreateKeyRequest(CreateKeyRequest.newBuilder().setKeyArgs(args)).build();
    OMRequest prepared = lifecycleRequest(create).preExecute(ozoneManager);
    applyLifecycle(prepared, 10L);
    OMRequest commit = OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
        .setCmdType(OzoneManagerProtocolProtos.Type.CommitKey)
        .setCommitKeyRequest(CommitKeyRequest.newBuilder().setClientID(prepared.getCreateKeyRequest().getClientID())
            .setKeyArgs(args.toBuilder().addAllKeyLocations(prepared.getCreateKeyRequest().getKeyArgs()
                .getKeyLocationsList()))).build();
    applyLifecycle(lifecycleRequest(commit).preExecute(ozoneManager), 20L);
    OMRequest overwrite = lifecycleRequest(create.toBuilder().setCreateKeyRequest(
        CreateKeyRequest.newBuilder().setKeyArgs(args)).build()).preExecute(ozoneManager);
    applyLifecycle(overwrite, 30L);
    long id = overwrite.getCreateKeyRequest().getClientID();
    String openName = layout.isFileSystemOptimized() ? omMetadataManager.getOpenFileName(
        omMetadataManager.getVolumeId(volumeName), omMetadataManager.getBucketId(volumeName, bucketName),
        omMetadataManager.getBucketId(volumeName, bucketName), keyName, id)
        : omMetadataManager.getOpenKey(volumeName, bucketName, keyName, id);
    OmKeyInfo open = omMetadataManager.getOpenKeyTable(layout).get(openName);
    String liveName = new OpenKeyDBKey(openName).getKeyTableDBKey();
    OmKeyInfo before = omMetadataManager.getKeyTable(layout).get(liveName).copyObject();
    assertEquals(Collections.singletonList(b1), blockIDs(before));
    assertEquals(scenario.equals("nonversioned") ? Collections.singletonList(b2) : Arrays.asList(b1, b2),
        blockIDs(open));
    assertEquals(scenario.equals("nonversioned") ? 1 : 2, open.getKeyLocationVersions().size());
    if (scenario.equals("commit")) {
      OMRequest secondCommit = commit.toBuilder().setCommitKeyRequest(commit.getCommitKeyRequest().toBuilder()
          .setClientID(id).setKeyArgs(args.toBuilder().addAllKeyLocations(
              overwrite.getCreateKeyRequest().getKeyArgs().getKeyLocationsList()))).build();
      applyLifecycle(lifecycleRequest(secondCommit).preExecute(ozoneManager), 40L);
      before = omMetadataManager.getKeyTable(layout).get(liveName).copyObject();
      assertEquals(Arrays.asList(b1, b2), blockIDs(before));
    }
    if (scenario.equals("renamed")) {
      OMRequest rename = OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
          .setCmdType(OzoneManagerProtocolProtos.Type.RenameKey)
          .setRenameKeyRequest(RenameKeyRequest.newBuilder().setKeyArgs(args).setToKeyName(keyName + "-renamed"))
          .build();
      applyLifecycle(lifecycleRequest(rename).preExecute(ozoneManager), 40L);
      liveName += "-renamed";
    }
    OMClientResponse response = new OMOpenKeysDeleteRequest(doPreExecute(createDeleteOpenKeyRequest(
        Collections.singletonList(Pair.of(id, open)))), layout).validateAndUpdateCache(ozoneManager, 50L);
    assertEquals(Status.OK, response.getOMResponse().getStatus());
    try (BatchOperation batch = omMetadataManager.getStore().initBatchOperation()) {
      response.checkAndUpdateDB(omMetadataManager, batch);
      omMetadataManager.getStore().commitBatchOperation(batch);
    }
    assertFalse(omMetadataManager.getOpenKeyTable(layout).isExist(openName));
    RepeatedOmKeyInfo deleted = omMetadataManager.getDeletedTable().get(
        omMetadataManager.getOzoneDeletePathKey(open.getObjectID(), openName));
    List<BlockID> deletedBlocks = deleted == null ? Collections.emptyList() : deleted.getOmKeyInfoList().stream()
        .flatMap(info -> blockIDs(info).stream()).collect(Collectors.toList());
    List<BlockID> pendingBlocks;
    try (ReclaimableKeyFilter filter = new ReclaimableKeyFilter(ozoneManager, ozoneManager.getOmSnapshotManager(),
        ((OmMetadataManagerImpl) omMetadataManager).getSnapshotChainManager(), null, keyManager,
        omMetadataManager.getLock())) {
      pendingBlocks = keyManager.getPendingDeletionKeys(filter, Integer.MAX_VALUE).getPurgedKeys().values().stream()
          .flatMap(key -> key.getBlockGroup().getDeletedBlocks().stream()).map(block -> block.getBlockID())
          .collect(Collectors.toList());
    }
    List<BlockID> expected = scenario.equals("commit") ? Collections.emptyList() : Collections.singletonList(b2);
    assertAll("Jira reproduction: " + layout + "/" + scenario,
        () -> assertEquals(expected, deletedBlocks, "deletedTable blocks"),
        () -> assertEquals(expected, pendingBlocks, "SCM deletion candidates"));
    assertEquals(before.getKeyLocationVersions(),
        omMetadataManager.getKeyTable(layout).get(liveName).getKeyLocationVersions());
  }

  static Stream<Arguments> hsyncCleanupScenarios() {
    return Stream.of(BucketLayout.OBJECT_STORE, BucketLayout.FILE_SYSTEM_OPTIMIZED)
        .flatMap(layout -> Stream.of("same", "renamed", "reused", "stale", "staleCache")
            .map(scenario -> Arguments.of(layout, scenario)));
  }

  @ParameterizedTest
  @MethodSource("hsyncCleanupScenarios")
  public void testHsyncCleanupProtectsRenamedVersions(BucketLayout layout, String scenario) throws Exception {
    bucketLayout = layout;
    when(ozoneManager.getOzoneLockProvider()).thenReturn(new OzoneLockProvider(false, false));
    when(ozoneManager.getObjectIdFromTxId(anyLong())).thenAnswer(invocation -> (long) invocation.getArgument(0) << 16);
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, omMetadataManager,
        OmBucketInfo.newBuilder().setVolumeName(volumeName).setBucketName(bucketName)
            .setBucketLayout(layout).setIsVersionEnabled(true));
    when(ozoneManager.getBucketManager()).thenReturn(new BucketManagerImpl(ozoneManager, omMetadataManager));
    Pipeline pipeline = Pipeline.newBuilder().setId(PipelineID.randomId()).setState(Pipeline.PipelineState.OPEN)
        .setReplicationConfig(replicationConfig).setNodes(Collections.emptyList()).build();
    AtomicLong next = new AtomicLong(5001);
    when(scmBlockLocationProtocol.allocateBlock(anyLong(), anyInt(), any(ReplicationConfig.class),
        anyString(), any(ExcludeList.class), anyString())).thenAnswer(invocation -> Collections.singletonList(
            new AllocatedBlock.Builder().setPipeline(pipeline)
                .setContainerBlockID(new ContainerBlockID(next.getAndIncrement(), 1)).build()));
    KeyArgs args = KeyArgs.newBuilder().setVolumeName(volumeName).setBucketName(bucketName).setKeyName(keyName)
        .setType(HddsProtos.ReplicationType.RATIS).setFactor(HddsProtos.ReplicationFactor.THREE)
        .setDataSize(10).build();
    OMRequest first = openForCleanup(args, 10L);
    commitForCleanup(first, args, false, 20L);
    OMRequest stale = scenario.startsWith("stale") ? openForCleanup(args, 25L) : null;
    OMRequest writerA = openForCleanup(args, 30L);
    long id = writerA.getCreateKeyRequest().getClientID();
    commitForCleanup(writerA, args, true, 40L);
    OMRequest allocate = OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
        .setCmdType(OzoneManagerProtocolProtos.Type.AllocateBlock)
        .setAllocateBlockRequest(OzoneManagerProtocolProtos.AllocateBlockRequest.newBuilder()
            .setClientID(id).setKeyArgs(args)).build();
    applyLifecycle(lifecycleRequest(allocate).preExecute(ozoneManager), 50L);
    OMRequest writerB = openForCleanup(args, 60L);
    commitForCleanup(writerB, args, false, 70L);
    String openName = layout.isFileSystemOptimized() ? omMetadataManager.getOpenFileName(
        omMetadataManager.getVolumeId(volumeName), omMetadataManager.getBucketId(volumeName, bucketName),
        omMetadataManager.getBucketId(volumeName, bucketName), keyName, id)
        : omMetadataManager.getOpenKey(volumeName, bucketName, keyName, id);
    String originalName = new OpenKeyDBKey(openName).getKeyTableDBKey();
    String liveName = originalName;
    OmKeyInfo open = omMetadataManager.getOpenKeyTable(layout).get(openName);
    assertTrue(open.isHsync());
    assertTrue(open.getMetadata().containsKey(OzoneConsts.OVERWRITTEN_HSYNC_KEY));
    assertEquals(Arrays.asList(new BlockID(stale == null ? 5002 : 5003, 1),
        new BlockID(stale == null ? 5003 : 5004, 1)),
        open.getLatestVersionLocations().getBlocksLatestVersionOnly().stream()
            .map(OmKeyLocationInfo::getBlockID).collect(Collectors.toList()));
    assertEquals(open.getObjectID(), omMetadataManager.getKeyTable(layout).get(liveName).getObjectID());
    deferLifecycleFlush = scenario.equals("staleCache");
    if (!scenario.equals("same")) {
      OMRequest rename = OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
          .setCmdType(OzoneManagerProtocolProtos.Type.RenameKey)
          .setRenameKeyRequest(RenameKeyRequest.newBuilder().setKeyArgs(args).setToKeyName(keyName + "-renamed"))
          .build();
      applyLifecycle(lifecycleRequest(rename).preExecute(ozoneManager), 80L);
      liveName += "-renamed";
    }
    if (scenario.equals("reused")) {
      OMRequest replacement = openForCleanup(args, 90L);
      commitForCleanup(replacement, args, false, 100L);
      assertNotEquals(open.getObjectID(), omMetadataManager.getKeyTable(layout).get(originalName).getObjectID());
    }
    if (stale != null) {
      commitForCleanup(stale, args, false, 105L);
      assertEquals(open.getObjectID(), omMetadataManager.getKeyTable(layout).get(originalName).getObjectID());
      assertFalse(blockIDs(omMetadataManager.getKeyTable(layout).get(originalName)).contains(new BlockID(5003, 1)));
    }
    OmKeyInfo before = omMetadataManager.getKeyTable(layout).get(liveName).copyObject();
    assertTrue(blockIDs(before).contains(new BlockID(stale == null ? 5002 : 5003, 1)));
    OMClientResponse response = new OMOpenKeysDeleteRequest(doPreExecute(createDeleteOpenKeyRequest(
        Collections.singletonList(Pair.of(id, open)))), layout).validateAndUpdateCache(ozoneManager, 110L);
    assertEquals(Status.OK, response.getOMResponse().getStatus());
    try (BatchOperation batch = omMetadataManager.getStore().initBatchOperation()) {
      response.checkAndUpdateDB(omMetadataManager, batch);
      omMetadataManager.getStore().commitBatchOperation(batch);
    }
    assertFalse(omMetadataManager.getOpenKeyTable(layout).isExist(openName));
    RepeatedOmKeyInfo deleted = omMetadataManager.getDeletedTable().get(
        omMetadataManager.getOzoneDeletePathKey(open.getObjectID(), openName));
    List<BlockID> expected = Collections.singletonList(new BlockID(stale == null ? 5003 : 5004, 1));
    assertEquals(expected, deleted == null ? Collections.emptyList() : blockIDs(deleted.getOmKeyInfoList().get(0)));
    if (deleted != null) {
      assertEquals(scmBlockSize, deleted.getOmKeyInfoList().get(0).getDataSize());
    }
    try (ReclaimableKeyFilter filter = new ReclaimableKeyFilter(ozoneManager, ozoneManager.getOmSnapshotManager(),
        ((OmMetadataManagerImpl) omMetadataManager).getSnapshotChainManager(), null, keyManager,
        omMetadataManager.getLock())) {
      assertEquals(expected, keyManager.getPendingDeletionKeys(filter, Integer.MAX_VALUE).getPurgedKeys().values()
          .stream().flatMap(key -> key.getBlockGroup().getDeletedBlocks().stream()).map(block -> block.getBlockID())
          .collect(Collectors.toList()));
    }
    assertEquals(before, omMetadataManager.getKeyTable(layout).get(liveName));
  }

  @ParameterizedTest
  @MethodSource("hsyncCleanupScenarios")
  public void testMalformedOpenKeyDoesNotInterruptBucketCleanup(BucketLayout layout, String scenario) throws Exception {
    bucketLayout = layout;
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, bucketName, omMetadataManager, layout);
    List<Pair<Long, OmKeyInfo>> valid = new ArrayList<>();
    valid.addAll(makeOpenKeys(volumeName, bucketName, 1));
    valid.addAll(makeOpenKeys(volumeName, bucketName, 1));
    for (Pair<Long, OmKeyInfo> key : valid) {
      key.getRight().addNewVersion(Collections.emptyList(), false, false);
    }
    addToOpenKeyTableDB(scmBlockSize, valid);
    OmKeyInfo malformed = valid.get(0).getRight().copyObject();
    boolean hsync = !scenario.equals("same");
    if (hsync) {
      malformed = malformed.withMetadataMutations(metadata -> metadata.put(OzoneConsts.HSYNC_CLIENT_ID, "123"));
    }
    String malformedName = "malformed";
    omMetadataManager.getOpenKeyTable(layout).put(malformedName, malformed);
    OMRequest request = createDeleteOpenKeyRequest(valid);
    OpenKeyBucket bucket = request.getDeleteOpenKeysRequest().getOpenKeysPerBucket(0);
    request = request.toBuilder().setDeleteOpenKeysRequest(request.getDeleteOpenKeysRequest().toBuilder()
        .clearOpenKeysPerBucket().addOpenKeysPerBucket(bucket.toBuilder().setVolumeName(volumeName)
            .setBucketName(bucketName).clearKeys().addKeys(bucket.getKeys(0))
            .addKeys(OpenKey.newBuilder().setName(malformedName))
            .addKeys(request.getDeleteOpenKeysRequest().getOpenKeysPerBucket(1).getKeys(0)))).build();
    OMClientResponse response = new OMOpenKeysDeleteRequest(doPreExecute(request), layout)
        .validateAndUpdateCache(ozoneManager, 100L);
    assertEquals(Status.OK, response.getOMResponse().getStatus());
    assertNotInOpenKeyTable(valid);
    assertFalse(omMetadataManager.getOpenKeyTable(layout).isExist(malformedName));
    try (BatchOperation batch = omMetadataManager.getStore().initBatchOperation()) {
      response.checkAndUpdateDB(omMetadataManager, batch);
      omMetadataManager.getStore().commitBatchOperation(batch);
    }
    for (String name : getDBKeyNames(valid)) {
      assertEquals(null, omMetadataManager.getOpenKeyTable(layout).getSkipCache(name));
    }
    assertEquals(null, omMetadataManager.getOpenKeyTable(layout).getSkipCache(malformedName));
    RepeatedOmKeyInfo deleted = omMetadataManager.getDeletedTable().get(
        omMetadataManager.getOzoneDeletePathKey(malformed.getObjectID(), malformedName));
    assertEquals(blockIDs(malformed), blockIDs(deleted.getOmKeyInfoList().get(0)));
    assertEquals(scmBlockSize, deleted.getOmKeyInfoList().get(0).getDataSize());
  }

  static Stream<Arguments> bucketScanSizes() {
    return Stream.of(BucketLayout.OBJECT_STORE, BucketLayout.FILE_SYSTEM_OPTIMIZED)
        .flatMap(layout -> Stream.of(1, 1000, 10000).map(count -> Arguments.of(layout, count)));
  }

  @ParameterizedTest
  @MethodSource("bucketScanSizes")
  public void testHsyncBatchScansBucketOnce(BucketLayout layout, int count) throws Exception {
    bucketLayout = layout;
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, bucketName, omMetadataManager, layout);
    List<Pair<Long, OmKeyInfo>> keys = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      keys.addAll(makeOpenKeys(volumeName, bucketName, 1));
      OmKeyInfo info = keys.get(i).getRight();
      info.addNewVersion(Collections.emptyList(), false, false);
      info.appendNewBlocks(Collections.singletonList(new OmKeyLocationInfo.Builder()
          .setBlockID(new BlockID(50000 + i, 1)).setLength(10).build()), false);
      OmKeyInfo hsync = info.withMetadataMutations(metadata -> metadata.put(OzoneConsts.HSYNC_CLIENT_ID, "123"));
      keys.set(i, Pair.of(keys.get(i).getLeft(), hsync));
    }
    keys.get(0).getRight().appendNewBlocks(Collections.singletonList(new OmKeyLocationInfo.Builder()
        .setBlockID(new BlockID(500, 1)).setLength(10).build()), false);
    addToOpenKeyTableDB(keys);
    org.apache.hadoop.hdds.utils.db.Table<String, OmKeyInfo> table = spy(omMetadataManager.getKeyTable(layout));
    String prefix = layout.isFileSystemOptimized() ? omMetadataManager.getOzoneKeyFSO(volumeName, bucketName, "/")
        : omMetadataManager.getBucketKeyPrefix(volumeName, bucketName);
    try (BatchOperation batch = omMetadataManager.getStore().initBatchOperation()) {
      for (int i = 0; i < count; i++) {
        OmKeyInfo live = keys.get(0).getRight().copyObject();
        live.setKeyLocationVersions(new ArrayList<>());
        live.addNewVersion(Collections.singletonList(new OmKeyLocationInfo.Builder()
            .setBlockID(new BlockID(500 + i, 1)).setLength(10).build()), false, false);
        table.putWithBatch(batch, prefix + "live-" + i, live);
      }
      // A different bucket must not protect this bucket's otherwise reclaimable block.
      table.putWithBatch(batch, prefix.substring(0, prefix.length() - 1) + "0/foreign", keys.get(1).getRight());
      table.putWithBatch(batch, prefix + "deleted-reference", keys.get(0).getRight());
      omMetadataManager.getStore().commitBatchOperation(batch);
    }
    table.addCacheEntry(new org.apache.hadoop.hdds.utils.db.cache.CacheKey<>(prefix + "deleted-reference"),
        org.apache.hadoop.hdds.utils.db.cache.CacheValue.get(90L));
    org.mockito.Mockito.doReturn(table).when(omMetadataManager).getKeyTable(layout);
    OMRequest original = createDeleteOpenKeyRequest(keys);
    List<String> names = getDBKeyNames(keys);
    OMRequest request = original.toBuilder().setDeleteOpenKeysRequest(original.getDeleteOpenKeysRequest().toBuilder()
        .clearOpenKeysPerBucket().addOpenKeysPerBucket(OpenKeyBucket.newBuilder().setVolumeName(volumeName)
            .setBucketName(bucketName).addKeys(OpenKey.newBuilder().setName(names.get(0)))
            .addKeys(OpenKey.newBuilder().setName(names.get(1))))).build();
    if (count == 1) {
      org.mockito.Mockito.doAnswer(invocation -> {
        throw new java.io.UncheckedIOException(new IOException("scan failure"));
      }).when(table).iterator(prefix);
      OMClientResponse failed = new OMOpenKeysDeleteRequest(doPreExecute(request), layout)
          .validateAndUpdateCache(ozoneManager, 99L);
      assertNotEquals(Status.OK, failed.getOMResponse().getStatus());
      assertInOpenKeyTable(keys);
      org.mockito.Mockito.doCallRealMethod().when(table).iterator(prefix);
      org.mockito.Mockito.clearInvocations(table);
    }
    long start = System.nanoTime();
    OMClientResponse response = new OMOpenKeysDeleteRequest(doPreExecute(request), layout)
        .validateAndUpdateCache(ozoneManager, 100L);
    System.out.println("bucket-scan " + layout + " keys=" + count + " elapsed-ms="
        + (System.nanoTime() - start) / 1000000.0);
    assertEquals(Status.OK, response.getOMResponse().getStatus());
    verify(table, times(1)).iterator(prefix);
    try (BatchOperation batch = omMetadataManager.getStore().initBatchOperation()) {
      response.checkAndUpdateDB(omMetadataManager, batch);
      omMetadataManager.getStore().commitBatchOperation(batch);
    }
    for (int i = 0; i < keys.size(); i++) {
      RepeatedOmKeyInfo deleted = omMetadataManager.getDeletedTable().get(
          omMetadataManager.getOzoneDeletePathKey(keys.get(i).getRight().getObjectID(), names.get(i)));
      assertEquals(Collections.singletonList(new BlockID(50000 + i, 1)), blockIDs(deleted.getOmKeyInfoList().get(0)));
    }
  }

  private OMRequest openForCleanup(KeyArgs args, long index) throws Exception {
    OMRequest request = OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
        .setCmdType(OzoneManagerProtocolProtos.Type.CreateKey)
        .setCreateKeyRequest(CreateKeyRequest.newBuilder().setKeyArgs(args)).build();
    OMRequest prepared = lifecycleRequest(request).preExecute(ozoneManager);
    applyLifecycle(prepared, index);
    return prepared;
  }

  private void commitForCleanup(OMRequest create, KeyArgs args, boolean hsync, long index) throws Exception {
    OMRequest commit = OMRequest.newBuilder().setClientId(UUID.randomUUID().toString())
        .setCmdType(OzoneManagerProtocolProtos.Type.CommitKey)
        .setCommitKeyRequest(CommitKeyRequest.newBuilder().setClientID(create.getCreateKeyRequest().getClientID())
            .setHsync(hsync).setKeyArgs(args.toBuilder().addAllKeyLocations(
                create.getCreateKeyRequest().getKeyArgs().getKeyLocationsList()))).build();
    applyLifecycle(lifecycleRequest(commit).preExecute(ozoneManager), index);
  }

  private List<BlockID> blockIDs(OmKeyInfo info) {
    return info.getKeyLocationVersions().stream().flatMap(group -> group.getLocationLists().stream())
        .flatMap(List::stream).map(OmKeyLocationInfo::getBlockID).collect(Collectors.toList());
  }

  private OMClientRequest lifecycleRequest(OMRequest request) throws IOException {
    OMClientRequest result;
    switch (request.getCmdType()) {
    case CreateKey:
      result = getBucketLayout().isFileSystemOptimized() ? new OMKeyCreateRequestWithFSO(request, getBucketLayout())
          : new OMKeyCreateRequest(request, getBucketLayout());
      break;
    case CommitKey:
      result = getBucketLayout().isFileSystemOptimized() ? new OMKeyCommitRequestWithFSO(request, getBucketLayout())
          : new OMKeyCommitRequest(request, getBucketLayout());
      break;
    case AllocateBlock:
      result = getBucketLayout().isFileSystemOptimized() ? new OMAllocateBlockRequestWithFSO(request, getBucketLayout())
          : new OMAllocateBlockRequest(request, getBucketLayout());
      break;
    case RenameKey:
      result = getBucketLayout().isFileSystemOptimized() ? new OMKeyRenameRequestWithFSO(request, getBucketLayout())
          : new OMKeyRenameRequest(request, getBucketLayout());
      break;
    default:
      throw new IllegalArgumentException(request.getCmdType().name());
    }
    result.setUGI(UserGroupInformation.getCurrentUser());
    return result;
  }

  private void applyLifecycle(OMRequest request, long index) throws Exception {
    OMClientResponse response = lifecycleRequest(request).validateAndUpdateCache(ozoneManager, index);
    assertEquals(Status.OK, response.getOMResponse().getStatus(), response.getOMResponse().toString());
    if (deferLifecycleFlush) {
      return;
    }
    try (BatchOperation batch = omMetadataManager.getStore().initBatchOperation()) {
      response.checkAndUpdateDB(omMetadataManager, batch);
      omMetadataManager.getStore().commitBatchOperation(batch);
    }
  }

  @ParameterizedTest
  @MethodSource("reclaimScenarios")
  public void testReclaimOnlyOpenVersionBlocks(BucketLayout layout, String scenario) throws Exception {
    bucketLayout = layout;
    OMRequestTestUtils.addVolumeAndBucketToDB(volumeName, bucketName, omMetadataManager, layout);

    List<Pair<Long, OmKeyInfo>> keys = makeOpenKeys(volumeName, bucketName, 1);
    OmKeyInfo open = keys.get(0).getRight();
    OmKeyLocationInfo oldBlock = new OmKeyLocationInfo.Builder()
        .setBlockID(new BlockID(100, 1)).setLength(10).build();
    OmKeyLocationInfo newBlock = new OmKeyLocationInfo.Builder()
        .setBlockID(new BlockID(100, 2)).setLength(10).build();
    open.addNewVersion(Collections.singletonList(oldBlock), false, true);
    if (scenario.equals("history")) {
      open.addNewVersion(Collections.emptyList(), false, true);
      open.appendNewBlocks(Collections.singletonList(new OmKeyLocationInfo.Builder()
          .setBlockID(new BlockID(101, 1)).setLength(10).build()), false);
    }
    OmKeyInfo committed = open.copyObject();
    open.addNewVersion(Collections.emptyList(), false, !scenario.equals("nonversioned"));
    if (!scenario.equals("empty")) {
      open.appendNewBlocks(Collections.singletonList(newBlock), false);
    }
    if (scenario.equals("shared")) {
      committed = open.copyObject();
    }
    if (scenario.equals("partial")) {
      committed.appendNewBlocks(Collections.singletonList(newBlock), false);
      newBlock = new OmKeyLocationInfo.Builder().setBlockID(new BlockID(102, 1)).setLength(10).build();
      open.appendNewBlocks(Collections.singletonList(newBlock), false);
    }
    if (scenario.equals("multipart")) {
      open.getLatestVersionLocations().setMultipartKey(true);
    }
    if (scenario.endsWith("Hsync")) {
      open = open.withMetadataMutations(metadata -> {
        metadata.put(OzoneConsts.HSYNC_CLIENT_ID, keys.get(0).getLeft().toString());
        metadata.put(scenario.equals("deletedHsync") ? OzoneConsts.DELETED_HSYNC_KEY
            : OzoneConsts.OVERWRITTEN_HSYNC_KEY, "true");
      });
      keys.set(0, Pair.of(keys.get(0).getLeft(), open));
    }
    addToOpenKeyTableDB(keys);
    String openName = getDBKeyNames(keys).get(0);
    String liveName = new OpenKeyDBKey(openName).getKeyTableDBKey();
    if (scenario.equals("renamed") || scenario.equals("reused")) {
      liveName += "-renamed";
      committed.setKeyName(committed.getKeyName() + "-renamed");
    }
    if (!scenario.equals("absent")) {
      omMetadataManager.getKeyTable(layout).put(liveName, committed);
    }
    if (scenario.equals("reused")) {
      OmKeyInfo replacement = committed.copyObject();
      replacement.setKeyLocationVersions(new ArrayList<>());
      replacement.addNewVersion(Collections.singletonList(new OmKeyLocationInfo.Builder()
          .setBlockID(new BlockID(103, 1)).setLength(10).build()), false, false);
      omMetadataManager.getKeyTable(layout).put(new OpenKeyDBKey(openName).getKeyTableDBKey(), replacement);
    }
    OmKeyInfo expectedCommitted = committed.copyObject();
    OMClientResponse response = new OMOpenKeysDeleteRequest(
        doPreExecute(createDeleteOpenKeyRequest(keys)), layout).validateAndUpdateCache(ozoneManager, 100L);
    assertEquals(Status.OK, response.getOMResponse().getStatus());
    try (BatchOperation batch = omMetadataManager.getStore().initBatchOperation()) {
      response.checkAndUpdateDB(omMetadataManager, batch);
      omMetadataManager.getStore().commitBatchOperation(batch);
    }
    assertNotInOpenKeyTable(keys);
    RepeatedOmKeyInfo deleted = omMetadataManager.getDeletedTable().get(
        omMetadataManager.getOzoneDeletePathKey(open.getObjectID(), openName));
    if (scenario.equals("empty") || scenario.equals("shared")) {
      assertEquals(null, deleted);
    } else {
      List<BlockID> blocks = deleted.getOmKeyInfoList().stream()
          .flatMap(info -> info.getKeyLocationVersions().stream())
          .flatMap(group -> group.getLocationLists().stream()).flatMap(List::stream)
          .map(OmKeyLocationInfo::getBlockID).collect(Collectors.toList());
      assertEquals(Collections.singletonList(newBlock.getBlockID()), blocks);
      assertEquals(newBlock.getLength(), deleted.getOmKeyInfoList().get(0).getDataSize());
    }
    if (!scenario.equals("absent")) {
      assertEquals(expectedCommitted, omMetadataManager.getKeyTable(layout).get(liveName));
    }
  }

  /**
   * Adds {@code openKeys} to the open key table DB only, and asserts that they
   * are present after the addition.
   * @throws Exception
   */
  private void addToOpenKeyTableDB(List<Pair<Long, OmKeyInfo>>... allKeys)
      throws Exception {

    addToOpenKeyTableDB(0, Arrays.stream(allKeys)
        .flatMap(List::stream)
        .collect(Collectors.toList()));
  }

  private void addToOpenKeyTableDB(long keySize,
      List<Pair<Long, OmKeyInfo>> openKeys) throws Exception {

    for (Pair<Long, OmKeyInfo> openKey : openKeys) {
      final long clientID = openKey.getLeft();
      final OmKeyInfo omKeyInfo = openKey.getRight();
      if (keySize > 0) {
        OMRequestTestUtils.addKeyLocationInfo(omKeyInfo, 0, keySize);
      }
      if (getBucketLayout().isFileSystemOptimized()) {
        OMRequestTestUtils.addFileToKeyTable(
            true, false, omKeyInfo.getFileName(),
            omKeyInfo, clientID, omKeyInfo.getUpdateID(), omMetadataManager);
      } else {
        OMRequestTestUtils.addKeyToTable(
            true, false,
            omKeyInfo, clientID, omKeyInfo.getUpdateID(), omMetadataManager);
      }
    }
    assertInOpenKeyTable(openKeys);
  }

  /*
   * Make open keys with randomized key name and client ID
   */
  private List<Pair<Long, OmKeyInfo>> makeOpenKeys(
      String volume, String bucket, int count) {
    return makeOpenKeys(volume, bucket, null, count);
  }

  /*
   * Make open keys with same key name and randomized client ID
   */
  private List<Pair<Long, OmKeyInfo>> makeOpenKeys(
      String volume, String bucket, String key, int count) {

    List<Pair<Long, OmKeyInfo>> keys = new ArrayList<>(count);

    OmKeyInfo.Builder builder = new OmKeyInfo.Builder()
        .setVolumeName(volume)
        .setBucketName(bucket)
        .setReplicationConfig(ReplicationConfig.fromTypeAndFactor(
            ReplicationType.RATIS, ReplicationFactor.THREE));

    if (getBucketLayout().isFileSystemOptimized()) {
      builder.setParentObjectID(random.nextLong());
    }

    for (int i = 0; i < count; i++) {
      final String name = key != null ? key : UUID.randomUUID().toString();
      builder.setKeyName(name);
      long clientID = random.nextLong();
      keys.add(Pair.of(clientID, builder.build()));
    }
    return keys;
  }

  private void assertInOpenKeyTable(List<Pair<Long, OmKeyInfo>>... allKeys)
      throws Exception {

    assertInOpenKeyTable(Arrays.stream(allKeys)
        .flatMap(List::stream)
        .collect(Collectors.toList()));
  }

  private void assertInOpenKeyTable(List<Pair<Long, OmKeyInfo>> openKeys)
      throws Exception {

    for (String keyName : getDBKeyNames(openKeys)) {
      assertTrue(omMetadataManager.getOpenKeyTable(getBucketLayout()).isExist(keyName));
    }
  }

  private void assertNotInOpenKeyTable(List<Pair<Long, OmKeyInfo>>... allKeys)
      throws Exception {

    assertNotInOpenKeyTable(Arrays.stream(allKeys)
        .flatMap(List::stream)
        .collect(Collectors.toList()));
  }

  private void assertNotInOpenKeyTable(List<Pair<Long, OmKeyInfo>> openKeys)
      throws Exception {

    for (String keyName : getDBKeyNames(openKeys)) {
      assertFalse(omMetadataManager.getOpenKeyTable(
          getBucketLayout()).isExist(keyName));
    }
  }

  private List<String> getDBKeyNames(List<Pair<Long, OmKeyInfo>> openKeys)
          throws IOException {

    final List<String> result = new ArrayList<>();
    for (Pair<Long, OmKeyInfo> entry : openKeys) {
      final OmKeyInfo ki = entry.getRight();
      if (getBucketLayout().isFileSystemOptimized()) {
        result.add(omMetadataManager.getOpenFileName(
                omMetadataManager.getVolumeId(ki.getVolumeName()),
                omMetadataManager.getBucketId(ki.getVolumeName(),
                        ki.getBucketName()),
                ki.getParentObjectID(),
                ki.getFileName(),
                entry.getLeft()));
      } else {
        result.add(omMetadataManager.getOpenKey(
                entry.getRight().getVolumeName(),
                entry.getRight().getBucketName(),
                entry.getRight().getKeyName(),
                entry.getLeft()));
      }
    }
    return result;
  }

  /**
   * Constructs a new {@link OMOpenKeysDeleteRequest} objects, and calls its
   * {@link OMOpenKeysDeleteRequest#preExecute} method with {@code
   * originalOMRequest}. It verifies that {@code originalOMRequest} is modified
   * after the call, and returns it.
   * @throws Exception
   */
  private OMRequest doPreExecute(OMRequest originalOmRequest) throws Exception {
    OMOpenKeysDeleteRequest omOpenKeysDeleteRequest =
        new OMOpenKeysDeleteRequest(originalOmRequest, getBucketLayout());

    OMRequest modifiedOmRequest =
        omOpenKeysDeleteRequest.preExecute(ozoneManager);

    // Will not be equal, as UserInfo will be set.
    assertNotEquals(originalOmRequest, modifiedOmRequest);

    return modifiedOmRequest;
  }

  /**
   * Creates an {@code OpenKeyDeleteRequest} to delete the keys represented by
   * {@code keysToDelete}. Returns an {@code OMRequest} which encapsulates this
   * {@code OpenKeyDeleteRequest}.
   */
  private OMRequest createDeleteOpenKeyRequest(
      List<Pair<Long, OmKeyInfo>> keysToDelete) throws IOException {

    List<String> names = getDBKeyNames(keysToDelete);

    // TODO: HDDS-6563, volume and bucket in OpenKeyBucket doesn't matter
    List<OpenKeyBucket> openKeyBuckets = names.stream()
        .map(name -> OpenKeyBucket.newBuilder()
            .setVolumeName("")
            .setBucketName("")
            .addKeys(OpenKey.newBuilder().setName(name).build())
            .build())
        .collect(Collectors.toList());

    DeleteOpenKeysRequest deleteOpenKeysRequest =
        DeleteOpenKeysRequest.newBuilder()
            .addAllOpenKeysPerBucket(openKeyBuckets)
            .build();

    return OMRequest.newBuilder()
        .setDeleteOpenKeysRequest(deleteOpenKeysRequest)
        .setCmdType(OzoneManagerProtocolProtos.Type.DeleteOpenKeys)
        .setClientId(UUID.randomUUID().toString()).build();
  }

}
