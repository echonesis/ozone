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

package org.apache.ozone.rocksdiff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for FlushList functionality in HDDS-13874.
 */
public class TestFlushList {

  @Test
  public void testFlushListCreation() {
    // Given
    long fromSeqNum = 100L;
    long toSeqNum = 200L;

    // When
    FlushList flushList = new FlushList(fromSeqNum, toSeqNum);

    // Then
    assertThat(flushList).isNotNull();
    assertThat(flushList.getFromSnapshotSeqNum()).isEqualTo(fromSeqNum);
    assertThat(flushList.getToSnapshotSeqNum()).isEqualTo(toSeqNum);
    assertThat(flushList.isEmpty()).isTrue();
    assertThat(flushList.size()).isEqualTo(0);
  }

  @Test
  public void testFlushListAddFlushedFile() {
    // Given: A FlushList
    FlushList flushList = new FlushList(100L, 200L);

    // When: Adding flushed files
    FlushedSstFile file1 = createMockFlushedSstFile("file1", "keyTable", 150L);
    FlushedSstFile file2 = createMockFlushedSstFile("file2", "directoryTable", 160L);
    flushList.addFlushedFile(file1);
    flushList.addFlushedFile(file2);

    // Then: Files should be in the list
    assertThat(flushList.size()).isEqualTo(2);
    assertThat(flushList.getFlushedFiles()).containsExactly(file1, file2);
  }

  @Test
  public void testFlushListWithPrePopulatedFiles() {
    // Given: Pre-populated files
    List<FlushedSstFile> files = new ArrayList<>();
    files.add(createMockFlushedSstFile("file1", "keyTable", 110L));
    files.add(createMockFlushedSstFile("file2", "fileTable", 120L));
    files.add(createMockFlushedSstFile("file3", "directoryTable", 130L));

    // When: Creating FlushList with files
    FlushList flushList = new FlushList(100L, 200L, files);

    // Then: Should contain all files
    assertThat(flushList.size()).isEqualTo(3);
    assertThat(flushList.isEmpty()).isFalse();
    assertThat(flushList.getFlushedFiles()).hasSize(3);
  }

  @Test
  public void testGetFilesByColumnFamily() {
    // Given: FlushList with files from different column families
    FlushList flushList = new FlushList(100L, 200L);
    FlushedSstFile keyTableFile1 = createMockFlushedSstFile("file1", "keyTable", 110L);
    FlushedSstFile keyTableFile2 = createMockFlushedSstFile("file2", "keyTable", 120L);
    FlushedSstFile dirTableFile = createMockFlushedSstFile("file3", "directoryTable", 130L);
    flushList.addFlushedFile(keyTableFile1);
    flushList.addFlushedFile(keyTableFile2);
    flushList.addFlushedFile(dirTableFile);

    // When: Getting files for specific column family
    List<FlushedSstFile> keyTableFiles = flushList.getFilesByColumnFamily("keyTable");
    List<FlushedSstFile> dirTableFiles = flushList.getFilesByColumnFamily("directoryTable");
    List<FlushedSstFile> fileTableFiles = flushList.getFilesByColumnFamily("fileTable");

    // Then: Should return correct files
    assertThat(keyTableFiles).containsExactly(keyTableFile1, keyTableFile2);
    assertThat(dirTableFiles).containsExactly(dirTableFile);
    assertThat(fileTableFiles).isEmpty();
  }

  @Test
  public void testPruneFilesOlderThan() {
    // Given: FlushList with files at different sequence numbers
    FlushList flushList = new FlushList(100L, 200L);
    flushList.addFlushedFile(createMockFlushedSstFile("file1", "keyTable", 110L));
    flushList.addFlushedFile(createMockFlushedSstFile("file2", "keyTable", 140L));
    flushList.addFlushedFile(createMockFlushedSstFile("file3", "keyTable", 180L));

    // When: Pruning files older than sequence number 150
    int prunedCount = flushList.pruneFilesOlderThan(150L);

    // Then: Should remove 2 files (110 and 140)
    assertThat(prunedCount).isEqualTo(2);
    assertThat(flushList.size()).isEqualTo(1);
    assertThat(flushList.getFlushedFiles().get(0).getSequenceNumber()).isEqualTo(180L);
  }

  @Test
  public void testGetFilesInRange() {
    // Given: FlushList with files
    FlushList flushList = new FlushList(100L, 200L);
    FlushedSstFile file1 = createMockFlushedSstFileWithRange("file1", "keyTable", 110L, "a", "d");
    FlushedSstFile file2 = createMockFlushedSstFileWithRange("file2", "keyTable", 120L, "e", "h");
    FlushedSstFile file3 = createMockFlushedSstFileWithRange("file3", "keyTable", 130L, "m", "z");
    flushList.addFlushedFile(file1);
    flushList.addFlushedFile(file2);
    flushList.addFlushedFile(file3);

    // When: Getting files in range [c, f]
    List<FlushedSstFile> filesInRange = flushList.getFilesInRange("c", "f");

    // Then: Should return files that overlap [c, f]
    // file1 [a-d] overlaps, file2 [e-h] overlaps, file3 [m-z] doesn't overlap
    assertThat(filesInRange).containsExactly(file1, file2);
  }

  @Test
  public void testGetFilesInRangeWithNullKeys() {
    // Given: FlushList with files
    FlushList flushList = new FlushList(100L, 200L);
    FlushedSstFile file1 = createMockFlushedSstFile("file1", "keyTable", 110L);
    flushList.addFlushedFile(file1);

    // When: Getting files with null keys
    List<FlushedSstFile> result = flushList.getFilesInRange(null, "z");

    // Then: Should return all files
    assertThat(result).containsExactly(file1);
  }

  @Test
  public void testToString() {
    // Given: A FlushList
    FlushList flushList = new FlushList(100L, 200L);
    flushList.addFlushedFile(createMockFlushedSstFile("file1", "keyTable", 150L));
    flushList.addFlushedFile(createMockFlushedSstFile("file2", "keyTable", 160L));

    // When: Converting to string
    String result = flushList.toString();

    // Then: Should contain key information
    assertThat(result).contains("from=100");
    assertThat(result).contains("to=200");
    assertThat(result).contains("files=2");
  }

  // Helper methods to create mock FlushedSstFile objects

  private FlushedSstFile createMockFlushedSstFile(String fileName,
                                                   String columnFamily,
                                                   long sequenceNumber) {
    // Use the explicit parameter constructor with empty key range
    return new FlushedSstFile(fileName, "", "", columnFamily, sequenceNumber);
  }

  private FlushedSstFile createMockFlushedSstFileWithRange(String fileName,
                                                            String columnFamily,
                                                            long sequenceNumber,
                                                            String startKey,
                                                            String endKey) {
    // Use the explicit parameter constructor with key range
    return new FlushedSstFile(fileName, startKey, endKey, columnFamily, sequenceNumber);
  }
}
