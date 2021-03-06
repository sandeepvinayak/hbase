/*
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.Waiter;
import org.apache.hadoop.hbase.protobuf.generated.WALProtos;
import org.apache.hadoop.hbase.regionserver.MultiVersionConcurrencyControl;
import org.apache.hadoop.hbase.regionserver.wal.WALActionsListener;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.replication.ChainWALEntryFilter;
import org.apache.hadoop.hbase.replication.ReplicationPeer;
import org.apache.hadoop.hbase.replication.ReplicationQueueInfo;
import org.apache.hadoop.hbase.replication.TableCfWALEntryFilter;
import org.apache.hadoop.hbase.replication.WALEntryFilter;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationSourceWALReaderThread.WALEntryBatch;
import org.apache.hadoop.hbase.testclassification.LargeTests;
import org.apache.hadoop.hbase.testclassification.ReplicationTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.wal.DefaultWALProvider;
import org.apache.hadoop.hbase.wal.WAL;
import org.apache.hadoop.hbase.wal.WAL.Entry;
import org.apache.hadoop.hbase.wal.WALFactory;
import org.apache.hadoop.hbase.wal.WALKey;
import org.apache.hadoop.hbase.wal.WALProvider;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.class)
@Category({ ReplicationTests.class, LargeTests.class })
public class TestWALEntryStream {

  private static HBaseTestingUtility TEST_UTIL;
  private static Configuration conf;
  private static FileSystem fs;
  private static MiniDFSCluster cluster;
  private static final TableName tableName = TableName.valueOf("tablename");
  private static final byte[] family = Bytes.toBytes("column");
  private static final byte[] qualifier = Bytes.toBytes("qualifier");
  private static final HRegionInfo info =
      new HRegionInfo(tableName, HConstants.EMPTY_START_ROW, HConstants.LAST_ROW, false);
  private static final HTableDescriptor htd = new HTableDescriptor(tableName);
  private static NavigableMap<byte[], Integer> scopes;
  private final String fakeWalGroupId = "fake-wal-group-id";

  private WAL log;
  ReplicationSourceLogQueue logQueue;
  private PathWatcher pathWatcher;

  @Rule
  public TestName tn = new TestName();
  private final MultiVersionConcurrencyControl mvcc = new MultiVersionConcurrencyControl();

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL = new HBaseTestingUtility();
    conf = TEST_UTIL.getConfiguration();
    TEST_UTIL.startMiniDFSCluster(3);

    cluster = TEST_UTIL.getDFSCluster();
    fs = cluster.getFileSystem();
    scopes = new TreeMap<byte[], Integer>(Bytes.BYTES_COMPARATOR);
    for (byte[] fam : htd.getFamiliesKeys()) {
      scopes.put(fam, 0);
    }
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    MetricsSource source = new MetricsSource("2");
    // Source with the same id is shared and carries values from the last run
    source.clear();
    logQueue = new ReplicationSourceLogQueue(conf, source);
    List<WALActionsListener> listeners = new ArrayList<WALActionsListener>();
    pathWatcher = new PathWatcher();
    listeners.add(pathWatcher);
    final WALFactory wals = new WALFactory(conf, listeners, tn.getMethodName());
    log = wals.getWAL(info.getEncodedNameAsBytes(), info.getTable().getNamespace());
  }

  @After
  public void tearDown() throws Exception {
    log.close();
  }

  // Try out different combinations of row count and KeyValue count
  @Test
  public void testDifferentCounts() throws Exception {
    int[] NB_ROWS = { 1500, 60000 };
    int[] NB_KVS = { 1, 100 };
    // whether compression is used
    Boolean[] BOOL_VALS = { false, true };
    // long lastPosition = 0;
    for (int nbRows : NB_ROWS) {
      for (int walEditKVs : NB_KVS) {
        for (boolean isCompressionEnabled : BOOL_VALS) {
          TEST_UTIL.getConfiguration().setBoolean(HConstants.ENABLE_WAL_COMPRESSION,
            isCompressionEnabled);
          mvcc.advanceTo(1);

          for (int i = 0; i < nbRows; i++) {
            appendToLogPlus(walEditKVs);
          }

          log.rollWriter();

          try (WALEntryStream entryStream =
              new WALEntryStream(logQueue, fs, conf, new MetricsSource("1"), fakeWalGroupId)) {
            int i = 0;
            for (WAL.Entry e : entryStream) {
              assertNotNull(e);
              i++;
            }
            assertEquals(nbRows, i);

            // should've read all entries
            assertFalse(entryStream.hasNext());
          }
          // reset everything for next loop
          log.close();
          setUp();
        }
      }
    }
  }

  /**
   * Tests basic reading of log appends
   */
  @Test
  public void testAppendsWithRolls() throws Exception {
    appendToLog();

    long oldPos;
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, new MetricsSource("1"), fakeWalGroupId)) {
      // There's one edit in the log, read it. Reading past it needs to throw exception
      assertTrue(entryStream.hasNext());
      WAL.Entry entry = entryStream.next();
      assertNotNull(entry);
      assertFalse(entryStream.hasNext());
      try {
        entry = entryStream.next();
        fail();
      } catch (NoSuchElementException e) {
        // expected
      }
      oldPos = entryStream.getPosition();
    }

    appendToLog();

    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, oldPos, new MetricsSource("1"), fakeWalGroupId)) {
      // Read the newly added entry, make sure we made progress
      WAL.Entry entry = entryStream.next();
      assertNotEquals(oldPos, entryStream.getPosition());
      assertNotNull(entry);
      oldPos = entryStream.getPosition();
    }

    // We rolled but we still should see the end of the first log and get that item
    appendToLog();
    log.rollWriter();
    appendToLog();

    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, oldPos, new MetricsSource("1"), fakeWalGroupId)) {
      WAL.Entry entry = entryStream.next();
      assertNotEquals(oldPos, entryStream.getPosition());
      assertNotNull(entry);

      // next item should come from the new log
      entry = entryStream.next();
      assertNotEquals(oldPos, entryStream.getPosition());
      assertNotNull(entry);

      // no more entries to read
      assertFalse(entryStream.hasNext());
      oldPos = entryStream.getPosition();
    }
  }

  /**
   * Tests that if after a stream is opened, more entries come in and then the log is rolled, we
   * don't mistakenly dequeue the current log thinking we're done with it
   */
  @Test
  public void testLogrollWhileStreaming() throws Exception {
    appendToLog("1");
    appendToLog("2");// 2
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, new MetricsSource("1"), fakeWalGroupId)) {
      assertEquals("1", getRow(entryStream.next()));

      appendToLog("3"); // 3 - comes in after reader opened
      log.rollWriter(); // log roll happening while we're reading
      appendToLog("4"); // 4 - this append is in the rolled log

      assertEquals("2", getRow(entryStream.next()));
      assertEquals(2, getQueue().size()); // we should not have dequeued yet since there's still an
                                        // entry in first log
      assertEquals("3", getRow(entryStream.next())); // if implemented improperly, this would be 4
                                                     // and 3 would be skipped
      assertEquals("4", getRow(entryStream.next())); // 4
      assertEquals(1, getQueue().size()); // now we've dequeued and moved on to next log properly
      assertFalse(entryStream.hasNext());
    }
  }

  /**
   * Tests that if writes come in while we have a stream open, we shouldn't miss them
   */
  @Test
  public void testNewEntriesWhileStreaming() throws Exception {
    appendToLog("1");
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, 0, new MetricsSource("1"), fakeWalGroupId)) {
      entryStream.next(); // we've hit the end of the stream at this point

      // some new entries come in while we're streaming
      appendToLog("2");
      appendToLog("3");

      // don't see them
      assertFalse(entryStream.hasNext());

      // But we do if we reset
      entryStream.reset();
      assertEquals("2", getRow(entryStream.next()));
      assertEquals("3", getRow(entryStream.next()));
      assertFalse(entryStream.hasNext());
    }
  }

  @Test
  public void testResumeStreamingFromPosition() throws Exception {
    long lastPosition = 0;
    appendToLog("1");
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, 0, new MetricsSource("1"), fakeWalGroupId)) {
      entryStream.next(); // we've hit the end of the stream at this point
      appendToLog("2");
      appendToLog("3");
      lastPosition = entryStream.getPosition();
    }
    // next stream should picks up where we left off
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, lastPosition, new MetricsSource("1"),
        fakeWalGroupId)) {
      assertEquals("2", getRow(entryStream.next()));
      assertEquals("3", getRow(entryStream.next()));
      assertFalse(entryStream.hasNext()); // done
      assertEquals(1, getQueue().size());
    }
  }

  /**
   * Tests that if we stop before hitting the end of a stream, we can continue where we left off
   * using the last position
   */
  @Test
  public void testPosition() throws Exception {
    long lastPosition = 0;
    appendEntriesToLog(3);
    // read only one element
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, lastPosition, new MetricsSource("1"),
        fakeWalGroupId)) {
      entryStream.next();
      lastPosition = entryStream.getPosition();
    }
    // there should still be two more entries from where we left off
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, lastPosition, new MetricsSource("1"),
        fakeWalGroupId)) {
      assertNotNull(entryStream.next());
      assertNotNull(entryStream.next());
      assertFalse(entryStream.hasNext());
    }
  }


  @Test
  public void testEmptyStream() throws Exception {
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, 0, new MetricsSource("1"), fakeWalGroupId)) {
      assertFalse(entryStream.hasNext());
    }
  }

  @Test
  public void testReplicationSourceWALReaderThread() throws Exception {
    appendEntriesToLog(3);
    // get ending position
    long position;
    try (WALEntryStream entryStream =
        new WALEntryStream(logQueue, fs, conf, new MetricsSource("1"), fakeWalGroupId)) {
      entryStream.next();
      entryStream.next();
      entryStream.next();
      position = entryStream.getPosition();
    }

    // start up a batcher
    ReplicationSourceManager mockSourceManager = Mockito.mock(ReplicationSourceManager.class);
    ReplicationSource source = Mockito.mock(ReplicationSource.class);
    when(source.isPeerEnabled()).thenReturn(true);
    when(mockSourceManager.getTotalBufferUsed()).thenReturn(new AtomicLong(0));
    ReplicationSourceWALReaderThread batcher =
            new ReplicationSourceWALReaderThread(mockSourceManager, getQueueInfo(),logQueue, 0,
                    fs, conf, getDummyFilter(), new MetricsSource("1"), source, fakeWalGroupId);
    Path walPath = getQueue().peek();
    batcher.start();
    WALEntryBatch entryBatch = batcher.take();

    // should've batched up our entries
    assertNotNull(entryBatch);
    assertEquals(3, entryBatch.getWalEntries().size());
    assertEquals(position, entryBatch.getLastWalPosition());
    assertEquals(walPath, entryBatch.getLastWalPath());
    assertEquals(3, entryBatch.getNbRowKeys());

    appendToLog("foo");
    entryBatch = batcher.take();
    assertEquals(1, entryBatch.getNbEntries());
    assertEquals(getRow(entryBatch.getWalEntries().get(0)), "foo");
  }

  @Test
  public void testReplicationSourceWALReaderThreadRecoveredQueue() throws Exception {
    appendEntriesToLog(3);
    log.rollWriter();
    appendEntriesToLog(2);

    long position;
    ReplicationSourceLogQueue tempQueue = new ReplicationSourceLogQueue(conf,
        getMockMetrics());
    for (Path path : getQueue()) {
      tempQueue.enqueueLog(path, fakeWalGroupId);
    }
    try (WALEntryStream entryStream = new WALEntryStream(tempQueue,
            fs, conf, new MetricsSource("1"), fakeWalGroupId)) {
      entryStream.next();
      entryStream.next();
      entryStream.next();
      entryStream.next();
      entryStream.next();
      position = entryStream.getPosition();
    }

    ReplicationSourceManager mockSourceManager = mock(ReplicationSourceManager.class);
    ReplicationSource source = Mockito.mock(ReplicationSource.class);
    when(source.isPeerEnabled()).thenReturn(true);
    when(mockSourceManager.getTotalBufferUsed()).thenReturn(new AtomicLong(0));
    ReplicationSourceWALReaderThread reader =
            new ReplicationSourceWALReaderThread(mockSourceManager, getRecoveredQueueInfo(),
            logQueue, 0, fs, conf, getDummyFilter(),
            new MetricsSource("1"), source, fakeWalGroupId);
    Path walPath = getQueue().toArray(new Path[2])[1];
    reader.start();
    WALEntryBatch entryBatch = reader.take();

    assertNotNull(entryBatch);
    assertEquals(5, entryBatch.getWalEntries().size());
    assertEquals(position, entryBatch.getLastWalPosition());
    assertEquals(walPath, entryBatch.getLastWalPath());
    assertFalse(entryBatch.hasMoreEntries());
  }

  @Test
  public void testWALKeySerialization() throws Exception {
    Map<String, byte[]> attributes = new HashMap<String, byte[]>();
    attributes.put("foo", Bytes.toBytes("foo-value"));
    attributes.put("bar", Bytes.toBytes("bar-value"));
    WALKey key = new WALKey(info.getEncodedNameAsBytes(), tableName,
      System.currentTimeMillis(), 0L, 0L, mvcc, attributes);
    assertEquals(attributes, key.getExtendedAttributes());

    WALProtos.WALKey.Builder builder = key.getBuilder(null);
    WALProtos.WALKey serializedKey = builder.build();

    WALKey deserializedKey = new WALKey();
    deserializedKey.readFieldsFromPb(serializedKey, null);

    //equals() only checks region name, sequence id and write time
    assertEquals(key, deserializedKey);
    //can't use Map.equals() because byte arrays use reference equality
    assertEquals(key.getExtendedAttributes().keySet(),
      deserializedKey.getExtendedAttributes().keySet());
    for (Map.Entry<String, byte[]> entry : deserializedKey.getExtendedAttributes().entrySet()) {
      assertArrayEquals(key.getExtendedAttribute(entry.getKey()), entry.getValue());
    }
  }

  @Test
  public void testReplicationSourceWALReaderThreadWithFilter() throws Exception {
    final byte[] notReplicatedCf = Bytes.toBytes("notReplicated");
    final Map<TableName, List<String>> tableCfs = new HashMap<>();
    tableCfs.put(tableName, Collections.singletonList(Bytes.toString(family)));
    ReplicationPeer peer = mock(ReplicationPeer.class);
    when(peer.getTableCFs()).thenReturn(tableCfs);
    WALEntryFilter filter = new ChainWALEntryFilter(new TableCfWALEntryFilter(peer));

    // add filterable entries
    appendToLogPlus(3, notReplicatedCf);
    appendToLogPlus(3, notReplicatedCf);
    appendToLogPlus(3, notReplicatedCf);

    // add non filterable entries
    appendEntriesToLog(2);

    ReplicationSourceManager mockSourceManager = mock(ReplicationSourceManager.class);
    ReplicationSource source = Mockito.mock(ReplicationSource.class);
    when(source.isPeerEnabled()).thenReturn(true);
    when(mockSourceManager.getTotalBufferUsed()).thenReturn(new AtomicLong(0));
    final ReplicationSourceWALReaderThread reader =
            new ReplicationSourceWALReaderThread(mockSourceManager, getQueueInfo(), logQueue,
                    0, fs, conf, filter, new MetricsSource("1"), source, fakeWalGroupId);
    reader.start();

    WALEntryBatch entryBatch = reader.take();

    assertNotNull(entryBatch);
    assertFalse(entryBatch.isEmpty());
    List<Entry> walEntries = entryBatch.getWalEntries();
    assertEquals(2, walEntries.size());
    for (Entry entry : walEntries) {
      ArrayList<Cell> cells = entry.getEdit().getCells();
      assertTrue(cells.size() == 1);
      assertTrue(CellUtil.matchingFamily(cells.get(0), family));
    }
  }

  @Test
  public void testReplicationSourceWALReaderThreadWithFilterWhenLogRolled() throws Exception {
    final byte[] notReplicatedCf = Bytes.toBytes("notReplicated");
    final Map<TableName, List<String>> tableCfs = new HashMap<>();
    tableCfs.put(tableName, Collections.singletonList(Bytes.toString(family)));
    ReplicationPeer peer = mock(ReplicationPeer.class);
    when(peer.getTableCFs()).thenReturn(tableCfs);
    WALEntryFilter filter = new ChainWALEntryFilter(new TableCfWALEntryFilter(peer));

    appendToLogPlus(3, notReplicatedCf);

    Path firstWAL = getQueue().peek();
    final long eof = getPosition(firstWAL);

    ReplicationSourceManager mockSourceManager = mock(ReplicationSourceManager.class);
    ReplicationSource source = Mockito.mock(ReplicationSource.class);
    when(source.isPeerEnabled()).thenReturn(true);
    when(mockSourceManager.getTotalBufferUsed()).thenReturn(new AtomicLong(0));
    final ReplicationSourceWALReaderThread reader =
            new ReplicationSourceWALReaderThread(mockSourceManager, getQueueInfo(), logQueue,
                    0, fs, conf, filter, new MetricsSource("1"), source, fakeWalGroupId);
    reader.start();

    // reader won't put any batch, even if EOF reached.
    Waiter.waitFor(conf, 20000, new Waiter.Predicate<Exception>() {
      @Override public boolean evaluate() {
        return reader.getLastReadPosition() >= eof;
      }
    });
    assertNull(reader.poll(0));

    log.rollWriter();

    // should get empty batch with current wal position, after wal rolled
    WALEntryBatch entryBatch = reader.take();

    Path lastWAL= getQueue().peek();
    long positionToBeLogged = getPosition(lastWAL);

    assertNotNull(entryBatch);
    assertTrue(entryBatch.isEmpty());
    assertEquals(1, getQueue().size());
    assertNotEquals(firstWAL, entryBatch.getLastWalPath());
    assertEquals(lastWAL, entryBatch.getLastWalPath());
    assertEquals(positionToBeLogged, entryBatch.getLastWalPosition());
  }

  private long getPosition(Path walPath) throws IOException {
    ReplicationSourceLogQueue tempQueue =
        new ReplicationSourceLogQueue(conf, getMockMetrics());
    String walPrefix = DefaultWALProvider.getWALPrefixFromWALName(walPath.getName());
    tempQueue.enqueueLog(walPath, walPrefix);
    WALEntryStream entryStream =
            new WALEntryStream(tempQueue, fs, conf, getMockMetrics(), walPrefix);
    entryStream.hasNext();
    return entryStream.getPosition();
  }

  private String getRow(WAL.Entry entry) {
    Cell cell = entry.getEdit().getCells().get(0);
    return Bytes.toString(cell.getRowArray(), cell.getRowOffset(), cell.getRowLength());
  }

  private void appendToLog(String key) throws IOException {
    final long txid = log.append(htd, info,
      new WALKey(info.getEncodedNameAsBytes(), tableName, System.currentTimeMillis(), mvcc),
      getWALEdit(key), true);
    log.sync(txid);
  }

  private void appendEntriesToLog(int count) throws IOException {
    for (int i = 0; i < count; i++) {
      appendToLog();
    }
  }

  private void appendToLog() throws IOException {
    appendToLogPlus(1);
  }

  private void appendToLogPlus(int count) throws IOException {
    appendToLogPlus(count, family, qualifier);
  }

  private void appendToLogPlus(int count, byte[] cf) throws IOException {
    appendToLogPlus(count, cf, qualifier);
  }

  private void appendToLogPlus(int count, byte[] cf, byte[] cq) throws IOException {
    final long txid = log.append(htd, info,
      new WALKey(info.getEncodedNameAsBytes(), tableName, System.currentTimeMillis(), mvcc),
      getWALEdits(count, cf, cq), true);
    log.sync(txid);
  }

  private WALEdit getWALEdits(int count, byte[] cf, byte[] cq) {
    WALEdit edit = new WALEdit();
    for (int i = 0; i < count; i++) {
      edit.add(new KeyValue(Bytes.toBytes(System.currentTimeMillis()), cf, cq,
          System.currentTimeMillis(), cq));
    }
    return edit;
  }

  private WALEdit getWALEdit(String row) {
    WALEdit edit = new WALEdit();
    edit.add(
      new KeyValue(Bytes.toBytes(row), family, qualifier, System.currentTimeMillis(), qualifier));
    return edit;
  }

  private WALEntryFilter getDummyFilter() {
    return new WALEntryFilter() {

      @Override
      public Entry filter(Entry entry) {
        return entry;
      }
    };
  }

  private ReplicationQueueInfo getRecoveredQueueInfo() {
    return getQueueInfo("1-1");
  }

  private ReplicationQueueInfo getQueueInfo() {
    return getQueueInfo("1");
  }

  private ReplicationQueueInfo getQueueInfo(String znode) {
    return new ReplicationQueueInfo(znode);
  }

  class PathWatcher extends WALActionsListener.Base {

    Path currentPath;

    @Override
    public void preLogRoll(Path oldPath, Path newPath) {
      logQueue.enqueueLog(newPath, fakeWalGroupId);
      currentPath = newPath;
    }
  }

  @Test
  public void testReplicationSourceWALReaderDisabled()
    throws IOException, InterruptedException, ExecutionException {
    for(int i=0; i<3; i++) {
      //append and sync
      appendToLog("key" + i);
    }
    // get ending position
    long position;
    try (WALEntryStream entryStream =
      new WALEntryStream(logQueue, fs, conf, 0, new MetricsSource("1"), fakeWalGroupId)) {
      entryStream.next();
      entryStream.next();
      entryStream.next();
      position = entryStream.getPosition();
    }

    // start up a reader
    Path walPath = getQueue().peek();
    ReplicationSource source = Mockito.mock(ReplicationSource.class);
    when(source.getSourceMetrics()).thenReturn(new MetricsSource("1"));

    final AtomicBoolean enabled = new AtomicBoolean(false);
    when(source.isPeerEnabled()).thenAnswer(new Answer<Boolean>() {
      @Override
      public Boolean answer(InvocationOnMock invocationOnMock) throws Throwable {
        return enabled.get();
      }
    });

    ReplicationSourceManager mockSourceManager = mock(ReplicationSourceManager.class);
    when(mockSourceManager.getTotalBufferUsed()).thenReturn(new AtomicLong(0));
    final ReplicationSourceWALReaderThread reader =
      new ReplicationSourceWALReaderThread(mockSourceManager, getQueueInfo(), logQueue,
        0, fs, conf, getDummyFilter(), new MetricsSource("1"), source, fakeWalGroupId);

    reader.start();
    Future<WALEntryBatch> future =
      Executors.newSingleThreadExecutor().submit(new Callable<WALEntryBatch>() {
        @Override
        public WALEntryBatch call() throws Exception {
          return reader.take();
        }
      });

    // make sure that the isPeerEnabled has been called several times
    verify(source, timeout(30000).atLeast(5)).isPeerEnabled();
    // confirm that we can read nothing if the peer is disabled
    assertFalse(future.isDone());
    // then enable the peer, we should get the batch
    enabled.set(true);
    WALEntryBatch entryBatch = future.get();

    // should've batched up our entries
    assertNotNull(entryBatch);
    assertEquals(3, entryBatch.getWalEntries().size());
    assertEquals(position, entryBatch.getLastWalPosition());
    assertEquals(walPath, entryBatch.getLastWalPath());
    assertEquals(3, entryBatch.getNbRowKeys());
  }

  /*
     Test removal of 0 length log from logQueue if the source is a recovered source and
     size of logQueue is only 1.
    */
  @Test
  public void testEOFExceptionForRecoveredQueue() throws Exception {
    // Create a 0 length log.
    Path emptyLog = new Path("emptyLog.1");
    FSDataOutputStream fsdos = fs.create(emptyLog);
    fsdos.close();
    assertEquals(0, fs.getFileStatus(emptyLog).getLen());

    ReplicationSource source = Mockito.mock(ReplicationSource.class);

    ReplicationSourceManager mockSourceManager = mock(ReplicationSourceManager.class);
    // Make it look like the source is from recovered source.
    when(mockSourceManager.getOldSources())
      .thenReturn(new ArrayList<>(Arrays.asList((ReplicationSourceInterface)source)));
    when(source.isPeerEnabled()).thenReturn(true);
    when(mockSourceManager.getTotalBufferUsed()).thenReturn(new AtomicLong(0));
    // Override the max retries multiplier to fail fast.
    conf.setInt("replication.source.maxretriesmultiplier", 1);
    conf.setBoolean("replication.source.eof.autorecovery", true);

    ReplicationSourceLogQueue localLogQueue =
        new ReplicationSourceLogQueue(conf, getMockMetrics());
    localLogQueue.enqueueLog(emptyLog, fakeWalGroupId);
    // Create a reader thread.
    ReplicationSourceWALReaderThread reader =
        new ReplicationSourceWALReaderThread(mockSourceManager, getRecoveredQueueInfo(),
        localLogQueue, 0, fs, conf, getDummyFilter(), getMockMetrics(), source, fakeWalGroupId);
    reader.run();
    assertEquals(0, localLogQueue.getQueueSize(fakeWalGroupId));
  }

  @Test
  public void testEOFExceptionForRecoveredQueueWithMultipleLogs() throws Exception {
    ReplicationSourceLogQueue localLogQueue = new ReplicationSourceLogQueue(conf, getMockMetrics());
    // Create a 0 length log.
    Path emptyLog = new Path("log.2");
    FSDataOutputStream fsdos = fs.create(emptyLog);
    fsdos.close();
    assertEquals(0, fs.getFileStatus(emptyLog).getLen());
    localLogQueue.enqueueLog(emptyLog, fakeWalGroupId);

    final Path log1 = new Path("log.1");
    WALProvider.Writer writer1 = WALFactory.createWALWriter(fs, log1, TEST_UTIL.getConfiguration());
    appendEntries(writer1, 3);
    localLogQueue.enqueueLog(log1, fakeWalGroupId);

    ReplicationSource source = Mockito.mock(ReplicationSource.class);
    ReplicationSourceManager mockSourceManager = mock(ReplicationSourceManager.class);
    // Make it look like the source is from recovered source.
    when(mockSourceManager.getOldSources())
      .thenReturn(new ArrayList<>(Arrays.asList((ReplicationSourceInterface)source)));
    when(source.isPeerEnabled()).thenReturn(true);
    when(mockSourceManager.getTotalBufferUsed()).thenReturn(new AtomicLong(0));
    // Override the max retries multiplier to fail fast.
    conf.setInt("replication.source.maxretriesmultiplier", 1);
    conf.setBoolean("replication.source.eof.autorecovery", true);
    // Create a reader thread.
    ReplicationSourceWALReaderThread reader =
      new ReplicationSourceWALReaderThread(mockSourceManager, getRecoveredQueueInfo(),
        localLogQueue, 0, fs, conf, getDummyFilter(), getMockMetrics(), source, fakeWalGroupId);
    assertEquals("Initial log queue size is not correct",
      2, localLogQueue.getQueueSize(fakeWalGroupId));
    reader.run();

    // ReplicationSourceWALReaderThread#handleEofException method will
    // remove empty log from logQueue.
    assertEquals("Log queue should be empty", 0, localLogQueue.getQueueSize(fakeWalGroupId));
  }

  private PriorityBlockingQueue<Path> getQueue() {
    return logQueue.getQueue(fakeWalGroupId);
  }

  private MetricsSource getMockMetrics() {
    MetricsSource source = mock(MetricsSource.class);
    doNothing().when(source).incrSizeOfLogQueue();
    doNothing().when(source).decrSizeOfLogQueue();
    doNothing().when(source).setOldestWalAge(Mockito.anyInt());
    return source;
  }

  private void appendEntries(WALProvider.Writer writer, int numEntries) throws IOException {
    for (int i = 0; i < numEntries; i++) {
      byte[] b = Bytes.toBytes(Integer.toString(i));
      KeyValue kv = new KeyValue(b,b,b);
      WALEdit edit = new WALEdit();
      edit.add(kv);
      WALKey key = new WALKey(b, TableName.valueOf(b), 0, 0,
        HConstants.DEFAULT_CLUSTER_ID);
      NavigableMap<byte[], Integer> scopes = new TreeMap<byte[], Integer>(Bytes.BYTES_COMPARATOR);
      scopes.put(b, HConstants.REPLICATION_SCOPE_GLOBAL);
      key.setScopes(scopes);
      writer.append(new WAL.Entry(key, edit));
      writer.sync(false);
    }
    writer.close();
  }

  /**
   * Tests size of log queue is incremented and decremented properly.
   */
  @Test
  public void testSizeOfLogQueue() throws Exception {
    // There should be always 1 log which is current wal.
    assertEquals(1, logQueue.getMetrics().getSizeOfLogQueue());
    appendToLog();
    log.rollWriter();
    // After rolling there will be 2 wals in the queue
    assertEquals(2, logQueue.getMetrics().getSizeOfLogQueue());

    try (WALEntryStream entryStream =
           new WALEntryStream(logQueue, fs, conf, logQueue.getMetrics(), fakeWalGroupId)) {
      // There's one edit in the log, read it.
      assertTrue(entryStream.hasNext());
      WAL.Entry entry = entryStream.next();
      assertNotNull(entry);
      assertFalse(entryStream.hasNext());
    }
    // After removing one wal, size of log queue will be 1 again.
    assertEquals(1, logQueue.getMetrics().getSizeOfLogQueue());
  }
}
