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

package org.apache.hadoop.ozone.debug.om;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.util.mxCellRenderer;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
import org.apache.hadoop.hdds.cli.AbstractSubcommand;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.om.OMConfigKeys;
import org.apache.hadoop.ozone.om.OmMetadataManagerImpl;
import org.apache.ozone.rocksdiff.FlushList;
import org.apache.ozone.rocksdiff.FlushedSstFile;
import org.jgrapht.Graph;
import org.jgrapht.ext.JGraphXAdapter;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import picocli.CommandLine;

/**
 * Handler to generate image for flush list timeline.
 * Replacement for generate-compaction-dag after HDDS-13874 refactoring.
 * ozone debug om generate-flush-list.
 */
@CommandLine.Command(
    name = "generate-flush-list",
    aliases = "gfl",
    description = "Create an image of the flush list timeline for a snapshot. " +
        "This command is an offline command. i.e., it can run on any instance of om.db " +
        "and does not require OM to be up.")
public class FlushListPrinter extends AbstractSubcommand implements Callable<Void> {

  @CommandLine.ParentCommand
  private OMDebug parent;

  @CommandLine.Option(names = {"-s", "--snapshot-id"},
      required = true,
      description = "Snapshot ID to visualize flush list for.")
  private String snapshotId;

  @CommandLine.Option(names = {"-o", "--output-file"},
      required = true,
      description = "Path to location at which image will be saved. " +
          "Should include the image file name with \".png\" extension.")
  private String imageLocation;

  @Override
  public Void call() throws Exception {
    String dbPath = parent.getDbPath();
    if (!Files.exists(Paths.get(dbPath))) {
      err().println("DB path does not exist: " + dbPath);
      return null;
    }

    OzoneConfiguration conf = new OzoneConfiguration();
    conf.set(OMConfigKeys.OZONE_OM_DB_DIRS, dbPath);

    OmMetadataManagerImpl metadataManager = null;
    try {
      metadataManager = new OmMetadataManagerImpl(conf, null);
      metadataManager.start(conf);

      FlushList flushList = metadataManager.getStore()
          .getRocksDBCheckpointDiffer()
          .getFlushList(snapshotId);

      if (flushList == null || flushList.isEmpty()) {
        out().println("No flush list found for snapshot: " + snapshotId);
        out().println("This may be because:");
        out().println("  1. The snapshot does not exist");
        out().println("  2. No flushes have occurred for this snapshot");
        out().println("  3. The flush list has been pruned");
        return null;
      }

      generateFlushListImage(flushList, imageLocation);
      out().println("Flush list image was generated at '" + imageLocation + "'.");
      out().println("Snapshot: " + snapshotId);
      out().println("Total flushed files: " + flushList.size());
      out().println("Sequence range: " + flushList.getFromSnapshotSeqNum() +
          " -> " + flushList.getToSnapshotSeqNum());

    } finally {
      if (metadataManager != null) {
        metadataManager.stop();
      }
    }
    return null;
  }

  /**
   * Generate a timeline visualization of the flush list.
   * Creates a simple vertical graph showing chronological order of flushed files.
   */
  private void generateFlushListImage(FlushList flushList,
      String filePath) throws IOException {
    Objects.requireNonNull(filePath, "Image file path is required.");

    Graph<String, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);

    // Add snapshot node at the top
    String snapshotNode = String.format("Snapshot: %s%n(seq: %d -> %d)",
        snapshotId, flushList.getFromSnapshotSeqNum(), flushList.getToSnapshotSeqNum());
    graph.addVertex(snapshotNode);

    String previousNode = snapshotNode;
    List<FlushedSstFile> files = flushList.getFlushedFiles();

    // Add nodes for each flushed file in chronological order
    for (FlushedSstFile file : files) {
      String nodeLabel = String.format("[%d] %s%n(%s)",
          file.getSequenceNumber(),
          file.getFileName(),
          file.getColumnFamily() != null ? file.getColumnFamily() : "default");

      graph.addVertex(nodeLabel);
      graph.addEdge(previousNode, nodeLabel);
      previousNode = nodeLabel;
    }

    // Add end marker
    String endNode = String.format("Total: %d files", files.size());
    graph.addVertex(endNode);
    graph.addEdge(previousNode, endNode);

    // Render to image
    JGraphXAdapter<String, DefaultEdge> jGraphXAdapter = new JGraphXAdapter<>(graph);

    // Use hierarchical layout for vertical timeline
    mxHierarchicalLayout layout = new mxHierarchicalLayout(jGraphXAdapter);
    layout.execute(jGraphXAdapter.getDefaultParent());

    BufferedImage image =
        mxCellRenderer.createBufferedImage(jGraphXAdapter, null, 2,
            Color.WHITE, true, null);

    File outputFile = new File(filePath);
    ImageIO.write(image, "PNG", outputFile);
  }
}
