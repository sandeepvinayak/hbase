/**
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
package org.apache.hadoop.hbase.replication;

import java.util.Map;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.regionserver.HRegionServer;
import org.apache.hadoop.hbase.replication.regionserver.ReplicationStatus;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.testclassification.ReplicationTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({ ReplicationTests.class, MediumTests.class })
public class TestReplicationMetricsforUI extends TestReplicationBase {

  private static final byte[] qualName = Bytes.toBytes("q");

  @Test
  public void testReplicationMetrics() throws Exception {
    try (Admin hbaseAdmin = utility1.getConnection().getAdmin()) {
      Put p = new Put(Bytes.toBytes("starter"));
      p.addColumn(famName, qualName, Bytes.toBytes("value help to test replication delay"));
      htable1.put(p);
      // make sure replication done
      while (htable2.get(new Get(Bytes.toBytes("starter"))).size() == 0) {
        Thread.sleep(500);
      }
      // sleep 5 seconds to make sure timePassedAfterLastShippedOp > 2 * ageOfLastShippedOp
      Thread.sleep(5000);
      HRegionServer rs = utility1.getRSForFirstRegionInTable(tableName);
      Map<String, ReplicationStatus> metrics = rs.getWalGroupsReplicationStatus();
      Assert.assertEquals("metric size ", 1, metrics.size());
      for (Map.Entry<String, ReplicationStatus> metric : metrics.entrySet()) {
        Assert.assertEquals("peerId", PEER_ID2, metric.getValue().getPeerId());
        Assert.assertEquals("queue length", 1, metric.getValue().getQueueSize());
        Assert.assertEquals("replication delay", 0, metric.getValue().getReplicationDelay());
        long pos = metric.getValue().getCurrentPosition();
        // Semantics are a bit different in branch-1: If not started, pos will be -1
        if (pos == -1) {
          pos = 0;
        }
        Assert.assertTrue("current position should be >= 0, is " + pos, pos >= 0);
      }
      for (int i = 0; i < NB_ROWS_IN_BATCH; i++) {
        p = new Put(Bytes.toBytes("" + Integer.toString(i)));
        p.addColumn(famName, qualName, Bytes.toBytes("value help to test replication delay " + i));
        htable1.put(p);
      }
      while (htable2.get(new Get(Bytes.toBytes("" + Integer.toString(NB_ROWS_IN_BATCH - 1))))
          .size() == 0) {
        Thread.sleep(500);
      }
      rs = utility1.getRSForFirstRegionInTable(tableName);
      metrics = rs.getWalGroupsReplicationStatus();
      Path lastPath = null;
      for (Map.Entry<String, ReplicationStatus> metric : metrics.entrySet()) {
        lastPath = metric.getValue().getCurrentPath();
        Assert.assertEquals("peerId", PEER_ID2, metric.getValue().getPeerId());
        Assert.assertTrue("age of Last Shipped Op should be > 0 ",
          metric.getValue().getAgeOfLastShippedOp() > 0);
        long pos = metric.getValue().getCurrentPosition();
        Assert.assertTrue("current position should be >= 0, is " + pos, pos >= 0);
      }

      hbaseAdmin.rollWALWriter(rs.getServerName());
      p = new Put(Bytes.toBytes("trigger"));
      p.addColumn(famName, qualName, Bytes.toBytes("value help to test replication delay"));
      htable1.put(p);
      // make sure replication rolled to a new log
      while (htable2.get(new Get(Bytes.toBytes("trigger"))).size() == 0) {
        Thread.sleep(500);
      }
      // sleep 5 seconds to make sure timePassedAfterLastShippedOp > 2 * ageOfLastShippedOp
      Thread.sleep(5000);
      metrics = rs.getWalGroupsReplicationStatus();
      for (Map.Entry<String, ReplicationStatus> metric : metrics.entrySet()) {
        Assert.assertEquals("replication delay", 0, metric.getValue().getReplicationDelay());
        long pos = metric.getValue().getCurrentPosition();
        Assert.assertTrue("current position should be >= 0, is " + pos, pos >= 0);
        Assert.assertNotEquals("current path", lastPath, metric.getValue().getCurrentPath());
      }
    }
  }
}