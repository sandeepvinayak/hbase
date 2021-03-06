/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.replication.regionserver;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;
import org.apache.hadoop.hbase.protobuf.generated.WALProtos.BulkLoadDescriptor;
import org.apache.hadoop.hbase.protobuf.generated.WALProtos.StoreDescriptor;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.replication.ReplicationQueueInfo;
import org.apache.hadoop.hbase.replication.WALEntryFilter;
import org.apache.hadoop.hbase.replication.regionserver.WALEntryStream.WALEntryStreamRuntimeException;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.wal.WAL.Entry;
import org.apache.hadoop.hbase.wal.WALKey;

/**
 * Reads and filters WAL entries, groups the filtered entries into batches,
 * and puts the batches onto a queue
 *
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class ReplicationSourceWALReaderThread extends Thread {
  private static final Log LOG = LogFactory.getLog(ReplicationSourceWALReaderThread.class);

  private ReplicationSourceLogQueue logQueue;
  private FileSystem fs;
  private Configuration conf;
  private BlockingQueue<WALEntryBatch> entryBatchQueue;
  // max (heap) size of each batch - multiply by number of batches in queue to get total
  private long replicationBatchSizeCapacity;
  // max count of each batch - multiply by number of batches in queue to get total
  private int replicationBatchCountCapacity;
  // position in the WAL to start reading at
  private long lastReadPosition;
  private Path lastReadPath;
  private WALEntryFilter filter;
  private long sleepForRetries;
  //Indicates whether this particular worker is running
  private boolean isReaderRunning = true;
  private ReplicationQueueInfo replicationQueueInfo;
  private int maxRetriesMultiplier;
  private MetricsSource metrics;

  private AtomicLong totalBufferUsed;
  private long totalBufferQuota;
  private final String walGroupId;

  private ReplicationSource source;
  private ReplicationSourceManager manager;

  /**
   * Creates a reader worker for a given WAL queue. Reads WAL entries off a given queue, batches the
   * entries, and puts them on a batch queue.
   * @param manager replication manager
   * @param replicationQueueInfo replication queue info
   * @param logQueue The WAL queue to read off of
   * @param startPosition position in the first WAL to start reading from
   * @param fs the files system to use
   * @param conf configuration to use
   * @param filter The filter to use while reading
   * @param metrics replication metrics
   */
  public ReplicationSourceWALReaderThread(ReplicationSourceManager manager,
      ReplicationQueueInfo replicationQueueInfo, ReplicationSourceLogQueue logQueue,
      long startPosition, FileSystem fs, Configuration conf, WALEntryFilter filter,
      MetricsSource metrics, ReplicationSource source, String walGroupId) {
    this.replicationQueueInfo = replicationQueueInfo;
    this.logQueue = logQueue;
    this.walGroupId = walGroupId;
    this.lastReadPath = logQueue.getQueue(walGroupId).peek();
    this.lastReadPosition = startPosition;
    this.fs = fs;
    this.conf = conf;
    this.filter = filter;
    this.replicationBatchSizeCapacity =
        this.conf.getLong("replication.source.size.capacity", 1024 * 1024 * 64);
    this.replicationBatchCountCapacity = this.conf.getInt("replication.source.nb.capacity", 25000);
    // memory used will be batchSizeCapacity * (nb.batches + 1)
    // the +1 is for the current thread reading before placing onto the queue
    int batchCount = conf.getInt("replication.source.nb.batches", 1);
    this.totalBufferUsed = manager.getTotalBufferUsed();
    this.totalBufferQuota = conf.getLong(HConstants.REPLICATION_SOURCE_TOTAL_BUFFER_KEY,
      HConstants.REPLICATION_SOURCE_TOTAL_BUFFER_DFAULT);
    this.sleepForRetries =
        this.conf.getLong("replication.source.sleepforretries", 1000);    // 1 second
    this.maxRetriesMultiplier =
        this.conf.getInt("replication.source.maxretriesmultiplier", 300); // 5 minutes @ 1 sec per
    this.metrics = metrics;
    this.entryBatchQueue = new LinkedBlockingQueue<>(batchCount);
    this.source = source;
    this.manager = manager;
    LOG.info("peerClusterZnode=" + replicationQueueInfo.getPeerClusterZnode()
        + ", ReplicationSourceWALReaderThread : " + replicationQueueInfo.getPeerId()
        + " inited, replicationBatchSizeCapacity=" + replicationBatchSizeCapacity
        + ", replicationBatchCountCapacity=" + replicationBatchCountCapacity
        + ", replicationBatchQueueCapacity=" + batchCount);
  }

  @Override
  public void run() {
    int sleepMultiplier = 1;
    WALEntryBatch batch = null;
    WALEntryStream entryStream =
      new WALEntryStream(logQueue, fs, conf, lastReadPosition, metrics, walGroupId);
    try {
      while (isReaderRunning()) { // we only loop back here if something fatal happens to stream
        try {
          entryStream = new WALEntryStream(logQueue, fs, conf,
            lastReadPosition, metrics, walGroupId);
          while (isReaderRunning()) { // loop here to keep reusing stream while we can
            if (!source.isPeerEnabled()) {
              Threads.sleep(sleepForRetries);
              continue;
            }
            if (!checkQuota()) {
              continue;
            }
            batch = new WALEntryBatch(replicationBatchCountCapacity);
            boolean hasNext = entryStream.hasNext();
            while (hasNext) {
              Entry entry = entryStream.next();
              entry = filterEntry(entry);
              if (entry != null) {
                WALEdit edit = entry.getEdit();
                if (edit != null && !edit.isEmpty()) {
                  long entrySize = getEntrySizeIncludeBulkLoad(entry);
                  long entrySizeExcludeBulkLoad = getEntrySizeExcludeBulkLoad(entry);
                  batch.addEntry(entry, entrySize);
                  updateBatchStats(batch, entry, entryStream.getPosition(), entrySize);
                  boolean totalBufferTooLarge = acquireBufferQuota(entrySizeExcludeBulkLoad);
                  // Stop if too many entries or too big
                  if (totalBufferTooLarge || batch.getHeapSize() >= replicationBatchSizeCapacity
                    || batch.getNbEntries() >= replicationBatchCountCapacity) {
                    break;
                  }
                }
              }
              hasNext = entryStream.hasNext();
            }

            // If the batch has data to max capacity or stream doesn't have anything
            // try to ship it
            if (updateBatchAndShippingQueue(entryStream, batch, hasNext, false)) {
              sleepMultiplier = 1;
            }
          }
        } catch (IOException | WALEntryStreamRuntimeException e) { // stream related
          if (handleEofException(e, entryStream, batch)) {
            sleepMultiplier = 1;
          } else {
            if (sleepMultiplier < maxRetriesMultiplier) {
              LOG.debug("Failed to read stream of replication entries: " + e);
              sleepMultiplier++;
            } else {
              LOG.error("Failed to read stream of replication entries", e);
            }
            Threads.sleep(sleepForRetries * sleepMultiplier);
          }
        } catch (InterruptedException e) {
          LOG.trace("Interrupted while sleeping between WAL reads");
          Thread.currentThread().interrupt();
        } finally {
          entryStream.close();
        }
      }
    } catch (IOException e) {
      if (sleepMultiplier < maxRetriesMultiplier) {
        LOG.debug("Failed to read stream of replication entries: " + e);
        sleepMultiplier++;
      } else {
        LOG.error("Failed to read stream of replication entries", e);
      }
      Threads.sleep(sleepForRetries * sleepMultiplier);
    } catch (InterruptedException e) {
      LOG.trace("Interrupted while sleeping between WAL reads");
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Update the batch try to ship and return true if shipped
   * @param entryStream stream of the WALs
   * @param batch Batch of entries to ship
   * @param hasMoreData if the stream has more yet more data to read
   * @param isEOFException if we have hit the EOF exception before this. For EOF exception,
   *                      we do not want to reset the stream since entry stream doesn't
   *                      have correct information.
   * @return if batch is shipped successfully
   * @throws InterruptedException throws interrupted exception
   * @throws IOException throws io exception from stream
   */
  private boolean updateBatchAndShippingQueue(WALEntryStream entryStream, WALEntryBatch batch,
      boolean hasMoreData, boolean isEOFException) throws InterruptedException, IOException {
    updateBatch(entryStream, batch, hasMoreData, isEOFException);
    boolean isDataQueued = false;
    if (isShippable(batch)) {
      isDataQueued = true;
      entryBatchQueue.put(batch);
      if (!batch.hasMoreEntries()) {
        // we're done with queue recovery, shut ourselves down
        LOG.debug("Stopping the reader after recovering the queue");
        setReaderRunning(false);
      }
    } else {
      Thread.sleep(sleepForRetries);
    }

    if (!isEOFException) {
      resetStream(entryStream);
    }
    return isDataQueued;
  }

  private void updateBatch(WALEntryStream entryStream, WALEntryBatch batch, boolean moreData,
      boolean isEOFException) {
    logMessage(batch);
    // In case of EOF exception we can utilize the last read path and position
    // since we do not have the current information.
    if (isEOFException) {
      batch.updatePosition(lastReadPath, lastReadPosition);
    } else {
      batch.updatePosition(entryStream.getCurrentPath(), entryStream.getPosition());
    }
    batch.setMoreEntries(!replicationQueueInfo.isQueueRecovered() || moreData);
  }

  private void logMessage(WALEntryBatch batch) {
    if (LOG.isTraceEnabled()) {
      if (batch.isEmpty()) {
        LOG.trace("Didn't read any new entries from WAL");
      } else {
        LOG.trace(String.format("Read %s WAL entries eligible for replication",
                batch.getNbEntries()));
      }
    }
  }

  private boolean isShippable(WALEntryBatch batch) {
    return !batch.isEmpty() || checkIfWALRolled(batch) || !batch.hasMoreEntries();
  }

  private boolean checkIfWALRolled(WALEntryBatch batch) {
    return lastReadPath == null && batch.lastWalPath != null
      || lastReadPath != null && !lastReadPath.equals(batch.lastWalPath);
  }

  private void resetStream(WALEntryStream stream) throws IOException {
    lastReadPosition = stream.getPosition();
    lastReadPath = stream.getCurrentPath();
    stream.reset(); // reuse stream
  }

  /**
   * This is to handle the EOFException from the WAL entry stream. EOFException should
   * be handled carefully because there are chances of data loss because of never replicating
   * the data.
   * If EOFException happens on the last log in recovered queue, we can safely stop
   * the reader.
   * If EOException doesn't happen on the last log in recovered queue, we should
   * not stop the reader.
   * @return true only the IOE can be handled
   */
  private boolean handleEofException(Exception e, WALEntryStream entryStream,
      WALEntryBatch batch) throws InterruptedException {
    boolean isRecoveredSource = manager.getOldSources().contains(source);
    PriorityBlockingQueue<Path> queue = logQueue.getQueue(walGroupId);
    // Dump the log even if logQueue size is 1 if the source is from recovered Source since we don't
    // add current log to recovered source queue so it is safe to remove.
    if (e.getCause() instanceof EOFException && (isRecoveredSource || queue.size() > 1)
        && conf.getBoolean("replication.source.eof.autorecovery", false)) {
      try {
        if (fs.getFileStatus(queue.peek()).getLen() == 0) {
          LOG.warn("Forcing removal of 0 length log in queue: " + queue.peek());
          lastReadPath = queue.peek();
          logQueue.remove(walGroupId);
          lastReadPosition = 0;

          // If it was on last log in the recovered queue,
          // the stream doesn't have more data, we should stop the reader
          boolean hasMoreData = !queue.isEmpty();
          // After we removed the WAL from the queue, we should
          // try shipping the existing batch of entries, we do not want to reset
          // stream since entry stream doesn't have the correct data at this point
          updateBatchAndShippingQueue(entryStream, batch, hasMoreData, true);
          return true;
        }
      } catch (IOException ioe) {
        LOG.warn("Couldn't get file length information about log " + queue.peek());
      }
    }

    return false;
  }

  public Path getCurrentPath() {
    return logQueue.getQueue(walGroupId).peek();
  }

  //returns false if we've already exceeded the global quota
  private boolean checkQuota() {
    // try not to go over total quota
    if (totalBufferUsed.get() > totalBufferQuota) {
      Threads.sleep(sleepForRetries);
      return false;
    }
    return true;
  }

  private Entry filterEntry(Entry entry) {
    Entry filtered = filter.filter(entry);
    if (entry != null && filtered == null) {
      metrics.incrLogEditsFiltered();
    }
    return filtered;
  }

  /**
   * Retrieves the next batch of WAL entries from the queue, waiting up to the specified time for a
   * batch to become available
   * @return A batch of entries, along with the position in the log after reading the batch
   * @throws InterruptedException if interrupted while waiting
   */
  public WALEntryBatch take() throws InterruptedException {
    return entryBatchQueue.take();
  }

  public WALEntryBatch poll(long timeout) throws InterruptedException {
    return entryBatchQueue.poll(timeout, TimeUnit.MILLISECONDS);
  }

  private long getEntrySizeIncludeBulkLoad(Entry entry) {
    WALEdit edit = entry.getEdit();
    return  getEntrySizeExcludeBulkLoad(entry) + sizeOfStoreFilesIncludeBulkLoad(edit);
  }

  public long getEntrySizeExcludeBulkLoad(Entry entry) {
    WALEdit edit = entry.getEdit();
    WALKey key = entry.getKey();
    return edit.heapSize() + key.estimatedSerializedSizeOf();
  }

  private void updateBatchStats(WALEntryBatch batch, Entry entry,
    long entryPosition, long entrySize) {
    WALEdit edit = entry.getEdit();
    if (edit != null && !edit.isEmpty()) {
      batch.incrementHeapSize(entrySize);
      Pair<Integer, Integer> nbRowsAndHFiles = countDistinctRowKeysAndHFiles(edit);
      batch.incrementNbRowKeys(nbRowsAndHFiles.getFirst());
      batch.incrementNbHFiles(nbRowsAndHFiles.getSecond());
    }
    batch.lastWalPosition = entryPosition;
  }

  /**
   * Count the number of different row keys in the given edit because of mini-batching. We assume
   * that there's at least one Cell in the WALEdit.
   * @param edit edit to count row keys from
   * @return number of different row keys and HFiles
   */
  private Pair<Integer, Integer> countDistinctRowKeysAndHFiles(WALEdit edit) {
    List<Cell> cells = edit.getCells();
    int distinctRowKeys = 1;
    int totalHFileEntries = 0;
    Cell lastCell = cells.get(0);

    int totalCells = edit.size();
    for (int i = 0; i < totalCells; i++) {
      // Count HFiles to be replicated
      if (CellUtil.matchingQualifier(cells.get(i), WALEdit.BULK_LOAD)) {
        try {
          BulkLoadDescriptor bld = WALEdit.getBulkLoadDescriptor(cells.get(i));
          List<StoreDescriptor> stores = bld.getStoresList();
          int totalStores = stores.size();
          for (int j = 0; j < totalStores; j++) {
            totalHFileEntries += stores.get(j).getStoreFileList().size();
          }
        } catch (IOException e) {
          LOG.error("Failed to deserialize bulk load entry from wal edit. "
              + "Then its hfiles count will not be added into metric.");
        }
      }

      if (!CellUtil.matchingRow(cells.get(i), lastCell)) {
        distinctRowKeys++;
      }
      lastCell = cells.get(i);
    }

    Pair<Integer, Integer> result = new Pair<>(distinctRowKeys, totalHFileEntries);
    return result;
  }

  /**
   * Calculate the total size of all the store files
   * @param edit edit to count row keys from
   * @return the total size of the store files
   */
  private int sizeOfStoreFilesIncludeBulkLoad(WALEdit edit) {
    List<Cell> cells = edit.getCells();
    int totalStoreFilesSize = 0;

    int totalCells = edit.size();
    for (int i = 0; i < totalCells; i++) {
      if (CellUtil.matchingQualifier(cells.get(i), WALEdit.BULK_LOAD)) {
        try {
          BulkLoadDescriptor bld = WALEdit.getBulkLoadDescriptor(cells.get(i));
          List<StoreDescriptor> stores = bld.getStoresList();
          int totalStores = stores.size();
          for (int j = 0; j < totalStores; j++) {
            totalStoreFilesSize += stores.get(j).getStoreFileSizeBytes();
          }
        } catch (IOException e) {
          LOG.error("Failed to deserialize bulk load entry from wal edit. "
              + "Size of HFiles part of cell will not be considered in replication "
              + "request size calculation.",
            e);
        }
      }
    }
    return totalStoreFilesSize;
  }

  /**
   * @param size delta size for grown buffer
   * @return true if we should clear buffer and push all
   */
  private boolean acquireBufferQuota(long size) {
    return totalBufferUsed.addAndGet(size) >= totalBufferQuota;
  }

  /**
   * @return whether the reader thread is running
   */
  public boolean isReaderRunning() {
    return isReaderRunning && !isInterrupted();
  }

  /**
   * @param readerRunning the readerRunning to set
   */
  public void setReaderRunning(boolean readerRunning) {
    this.isReaderRunning = readerRunning;
  }

  public long getLastReadPosition() {
    return this.lastReadPosition;
  }

  /**
   * Holds a batch of WAL entries to replicate, along with some statistics
   *
   */
  final static class WALEntryBatch {
    private List<Pair<Entry, Long>> walEntriesWithSize;
    // last WAL that was read
    private Path lastWalPath;
    // position in WAL of last entry in this batch
    private long lastWalPosition = 0;
    // number of distinct row keys in this batch
    private int nbRowKeys = 0;
    // number of HFiles
    private int nbHFiles = 0;
    // heap size of data we need to replicate
    private long heapSize = 0;
    // whether more entries to read exist in WALs or not
    private boolean moreEntries = true;

    /**
     * @param maxNbEntries the number of entries a batch can have
     */
    private WALEntryBatch(int maxNbEntries) {
      this.walEntriesWithSize = new ArrayList<>(maxNbEntries);
    }

    public void addEntry(Entry entry, long entrySize) {
      walEntriesWithSize.add(new Pair<>(entry, entrySize));
    }

    /**
     * @return the WAL Entries.
     */
    public List<Entry> getWalEntries() {
      List<Entry> entries = new ArrayList<>(walEntriesWithSize.size());
      for (Pair<Entry, Long> pair: walEntriesWithSize) {
        entries.add(pair.getFirst());
      }
      return entries;
    }

    /**
     * @return the WAL Entries with size.
     */
    public List<Pair<Entry, Long>> getWalEntriesWithSize() {
      return walEntriesWithSize;
    }

    /**
     * @return the path of the last WAL that was read.
     */
    public Path getLastWalPath() {
      return lastWalPath;
    }

    /**
     * @return the position in the last WAL that was read.
     */
    public long getLastWalPosition() {
      return lastWalPosition;
    }

    public int getNbEntries() {
      return walEntriesWithSize.size();
    }

    /**
     * @return the number of distinct row keys in this batch
     */
    public int getNbRowKeys() {
      return nbRowKeys;
    }

    /**
     * @return the number of HFiles in this batch
     */
    public int getNbHFiles() {
      return nbHFiles;
    }

    /**
     * @return total number of operations in this batch
     */
    public int getNbOperations() {
      return getNbRowKeys() + getNbHFiles();
    }

    /**
     * @return the heap size of this batch
     */
    public long getHeapSize() {
      return heapSize;
    }

    private void incrementNbRowKeys(int increment) {
      nbRowKeys += increment;
    }

    private void incrementNbHFiles(int increment) {
      nbHFiles += increment;
    }

    private void incrementHeapSize(long increment) {
      heapSize += increment;
    }

    public boolean isEmpty() {
      return walEntriesWithSize.isEmpty();
    }

    /**
     * Update the wal entry batch with latest wal and position which will be used by
     * shipper to update the log position in ZK node
     * @param currentPath the path of WAL
     * @param currentPosition the position of the WAL
     */
    public void updatePosition(Path currentPath, long currentPosition) {
      lastWalPath = currentPath;
      lastWalPosition = currentPosition;
    }

    public boolean hasMoreEntries() {
      return moreEntries;
    }

    public void setMoreEntries(boolean moreEntries) {
      this.moreEntries = moreEntries;
    }
  }
}
