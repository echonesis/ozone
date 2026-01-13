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

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages a LinkedList of flushed SST files between two snapshots.
 * This class replaces the complex CompactionDag with a simple linear structure.
 *
 * HDDS-13874: Simplified design - tracks flush events instead of compaction events.
 * Uses LinkedList for efficient append operations when tracking flushes.
 */
public class FlushList {
  private final long fromSnapshotSeqNum;
  private final long toSnapshotSeqNum;
  private final LinkedList<FlushedSstFile> flushedFiles;

  /**
   * Creates a FlushList for the interval between two snapshots.
   *
   * @param fromSnapshotSeqNum Start snapshot sequence number
   * @param toSnapshotSeqNum End snapshot sequence number
   */
  public FlushList(long fromSnapshotSeqNum, long toSnapshotSeqNum) {
    this.fromSnapshotSeqNum = fromSnapshotSeqNum;
    this.toSnapshotSeqNum = toSnapshotSeqNum;
    this.flushedFiles = new LinkedList<>();
  }

  /**
   * Creates a FlushList with pre-populated files.
   *
   * @param fromSnapshotSeqNum Start snapshot sequence number
   * @param toSnapshotSeqNum End snapshot sequence number
   * @param files Initial list of flushed files
   */
  public FlushList(long fromSnapshotSeqNum, long toSnapshotSeqNum,
                   List<FlushedSstFile> files) {
    this.fromSnapshotSeqNum = fromSnapshotSeqNum;
    this.toSnapshotSeqNum = toSnapshotSeqNum;
    this.flushedFiles = new LinkedList<>(files);
  }

  /**
   * Adds a flushed file to the end of the list.
   * Files are typically added in chronological order as flushes occur.
   *
   * @param file The flushed SST file to add
   */
  public void addFlushedFile(FlushedSstFile file) {
    flushedFiles.add(file);
  }

  /**
   * Gets an unmodifiable view of the flushed files list.
   *
   * @return List of flushed files in chronological order
   */
  public List<FlushedSstFile> getFlushedFiles() {
    return Collections.unmodifiableList(flushedFiles);
  }

  /**
   * Gets files within a specific key range.
   *
   * @param startKey Start of key range (inclusive)
   * @param endKey End of key range (inclusive)
   * @return List of files overlapping with the key range
   */
  public List<FlushedSstFile> getFilesInRange(String startKey, String endKey) {
    if (startKey == null || endKey == null) {
      return getFlushedFiles();
    }

    return flushedFiles.stream()
        .filter(file -> overlaps(file, startKey, endKey))
        .collect(Collectors.toList());
  }

  /**
   * Gets files for a specific column family.
   *
   * @param columnFamily Column family name
   * @return List of files in the specified column family
   */
  public List<FlushedSstFile> getFilesByColumnFamily(String columnFamily) {
    return flushedFiles.stream()
        .filter(file -> columnFamily.equals(file.getColumnFamily()))
        .collect(Collectors.toList());
  }

  /**
   * Removes files older than the specified sequence number.
   *
   * @param sequenceNumber Cutoff sequence number
   * @return Number of files removed
   */
  public int pruneFilesOlderThan(long sequenceNumber) {
    int initialSize = flushedFiles.size();
    flushedFiles.removeIf(file -> file.getSequenceNumber() < sequenceNumber);
    return initialSize - flushedFiles.size();
  }

  /**
   * Checks if a file's key range overlaps with the given range.
   */
  private boolean overlaps(FlushedSstFile file, String startKey, String endKey) {
    String fileStart = file.getStartKey();
    String fileEnd = file.getEndKey();

    if (fileStart == null || fileEnd == null) {
      return true; // Include files with unknown ranges
    }

    // Check if ranges overlap: [fileStart, fileEnd] overlaps with [startKey, endKey]
    return fileStart.compareTo(endKey) <= 0 && fileEnd.compareTo(startKey) >= 0;
  }

  public long getFromSnapshotSeqNum() {
    return fromSnapshotSeqNum;
  }

  public long getToSnapshotSeqNum() {
    return toSnapshotSeqNum;
  }

  public int size() {
    return flushedFiles.size();
  }

  public boolean isEmpty() {
    return flushedFiles.isEmpty();
  }

  @Override
  public String toString() {
    return String.format("FlushList{from=%d, to=%d, files=%d}",
        fromSnapshotSeqNum, toSnapshotSeqNum, flushedFiles.size());
  }
}
