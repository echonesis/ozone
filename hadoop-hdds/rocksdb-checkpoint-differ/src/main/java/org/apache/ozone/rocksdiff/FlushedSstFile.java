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

import org.apache.ozone.rocksdb.util.SstFileInfo;
import org.rocksdb.LiveFileMetaData;

import java.util.Objects;

/**
 * Represents a flushed SST file tracked between snapshots.
 * This class extends SstFileInfo to add sequence number tracking
 * for determining the order of flushes between snapshots.
 *
 * HDDS-13874: Simplified design using LinkedList instead of CompactionDag.
 * Tracks flush events instead of compaction events.
 */
public class FlushedSstFile extends SstFileInfo {
  private final long sequenceNumber;

  /**
   * Creates a FlushedSstFile from LiveFileMetaData.
   *
   * @param fileMetaData RocksDB file metadata
   * @param sequenceNumber DB sequence number when this file was flushed
   */
  public FlushedSstFile(LiveFileMetaData fileMetaData, long sequenceNumber) {
    super(fileMetaData);
    this.sequenceNumber = sequenceNumber;
  }

  /**
   * Creates a FlushedSstFile with explicit parameters.
   *
   * @param fileName SST file name (without extension)
   * @param startKey Smallest key in the file
   * @param endKey Largest key in the file
   * @param columnFamily Column family name
   * @param sequenceNumber DB sequence number when this file was flushed
   */
  public FlushedSstFile(String fileName, String startKey, String endKey,
                        String columnFamily, long sequenceNumber) {
    super(fileName, startKey, endKey, columnFamily);
    this.sequenceNumber = sequenceNumber;
  }

  /**
   * Gets the sequence number when this file was flushed.
   * Used to determine the order of flushed files between snapshots.
   */
  public long getSequenceNumber() {
    return sequenceNumber;
  }

  @Override
  public String toString() {
    return String.format("FlushedSstFile{fileName='%s', startKey='%s', endKey='%s', " +
        "columnFamily='%s', sequenceNumber=%d}",
        getFileName(), getStartKey(), getEndKey(), getColumnFamily(), sequenceNumber);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FlushedSstFile)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    FlushedSstFile that = (FlushedSstFile) o;
    return sequenceNumber == that.sequenceNumber;
  }

  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), sequenceNumber);
  }

  @Override
  public FlushedSstFile copyObject() {
    return new FlushedSstFile(getFileName(), getStartKey(), getEndKey(),
        getColumnFamily(), sequenceNumber);
  }
}
