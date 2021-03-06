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
package org.apache.hadoop.hbase.master;

import static org.apache.hadoop.hbase.HConstants.DEFAULT_HBASE_RPC_TIMEOUT;
import static org.apache.hadoop.hbase.HConstants.HBASE_RPC_TIMEOUT_KEY;
import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.ipc.HBaseRpcController;
import org.apache.hadoop.hbase.ipc.RpcClient;
import org.apache.hadoop.hbase.ipc.RpcClientFactory;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.testclassification.MasterTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.JVMClusterUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.ClientMetaService;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.GetClusterIdRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.GetClusterIdResponse;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.GetMetaRegionLocationsRequest;
import org.apache.hadoop.hbase.protobuf.generated.MasterProtos.GetMetaRegionLocationsResponse;

@Category({MediumTests.class, MasterTests.class})
public class TestClientMetaServiceRPCs {

  // Total number of masters (active + stand by) for the purpose of this test.
  private static final int MASTER_COUNT = 3;
  private static final int RS_COUNT = 3;
  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static Configuration conf;
  private static int rpcTimeout;
  private static RpcClient rpcClient;

  @BeforeClass
  public static void setUp() throws Exception {
    // Start the mini cluster with stand-by masters.
    TEST_UTIL.startMiniCluster(MASTER_COUNT, RS_COUNT);
    conf = TEST_UTIL.getConfiguration();
    rpcTimeout = (int) Math.min(Integer.MAX_VALUE, TimeUnit.MILLISECONDS.toNanos(
        conf.getLong(HBASE_RPC_TIMEOUT_KEY, DEFAULT_HBASE_RPC_TIMEOUT)));
    rpcClient = RpcClientFactory.createClient(conf,
        TEST_UTIL.getMiniHBaseCluster().getMaster().getClusterId());
  }

  @AfterClass
  public static void tearDown() throws Exception {
    if (rpcClient != null) {
      rpcClient.close();
    }
    TEST_UTIL.shutdownMiniCluster();
  }

  private static ClientMetaService.BlockingInterface getMasterStub(ServerName server)
      throws IOException {
    return ClientMetaService.newBlockingStub(
        rpcClient.createBlockingRpcChannel(server, User.getCurrent(), rpcTimeout));
  }

  private static HBaseRpcController getRpcController() {
    return RpcControllerFactory.instantiate(conf).newController();
  }

  /**
   * Verifies the cluster ID from all running masters.
   */
  @Test public void TestClusterID() throws Exception {
    HBaseRpcController rpcController = getRpcController();
    String clusterID = TEST_UTIL.getMiniHBaseCluster().getMaster().getClusterId();
    int rpcCount = 0;
    for (JVMClusterUtil.MasterThread masterThread:
        TEST_UTIL.getMiniHBaseCluster().getMasterThreads()) {
      ClientMetaService.BlockingInterface stub =
          getMasterStub(masterThread.getMaster().getServerName());
      GetClusterIdResponse resp =
          stub.getClusterId(rpcController, GetClusterIdRequest.getDefaultInstance());
      assertEquals(clusterID, resp.getClusterId());
      rpcCount++;
    }
    assertEquals(MASTER_COUNT, rpcCount);
  }

  /**
   * Verifies that the meta region locations RPC returns consistent results across all masters.
   */
  @Test public void TestMetaLocations() throws Exception {
    HBaseRpcController rpcController = getRpcController();
    List<HRegionLocation> metaLocations = TEST_UTIL.getMiniHBaseCluster().getMaster()
        .getMetaRegionLocationCache().getMetaRegionLocations();
    Collections.sort(metaLocations);
    int rpcCount = 0;
    for (JVMClusterUtil.MasterThread masterThread:
      TEST_UTIL.getMiniHBaseCluster().getMasterThreads()) {
      ClientMetaService.BlockingInterface stub =
          getMasterStub(masterThread.getMaster().getServerName());
      GetMetaRegionLocationsResponse resp = stub.getMetaRegionLocations(
          rpcController, GetMetaRegionLocationsRequest.getDefaultInstance());
      List<HRegionLocation> result = new ArrayList<>();
      for (HBaseProtos.RegionLocation location: resp.getMetaLocationsList()) {
        result.add(ProtobufUtil.toRegionLocation(location));
      }
      Collections.sort(result);
      assertEquals(metaLocations, result);
      rpcCount++;
    }
    assertEquals(MASTER_COUNT, rpcCount);
  }
}
