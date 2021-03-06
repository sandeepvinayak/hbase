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
package org.apache.hadoop.hbase.client;

import java.io.IOException;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.zookeeper.MasterAddressTracker;
import org.apache.hadoop.hbase.zookeeper.MetaTableLocator;
import org.apache.hadoop.hbase.zookeeper.ZKClusterId;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.zookeeper.KeeperException;

/**
 * A cluster registry that stores to zookeeper.
 */
class ZKConnectionRegistry implements ConnectionRegistry {
  private static final Log LOG = LogFactory.getLog(ZKConnectionRegistry.class);
  // Needs an instance of hci to function.  Set after construct this instance.
  ConnectionManager.HConnectionImplementation hci;

  @Override
  public void init(Connection connection) {
    if (!(connection instanceof ConnectionManager.HConnectionImplementation)) {
      throw new RuntimeException("This registry depends on HConnectionImplementation");
    }
    this.hci = (ConnectionManager.HConnectionImplementation)connection;
  }

  @Override
  public ServerName getActiveMaster() throws IOException {
    ServerName sn;
    try (ZooKeeperKeepAliveConnection zkw = hci.getKeepAliveZooKeeperWatcher()) {
      sn = MasterAddressTracker.getMasterAddress(zkw);
    } catch (KeeperException e) {
      throw new HBaseIOException(e);
    }
    return sn;
  }

  @Override
  public RegionLocations getMetaRegionLocations() throws IOException {
    try (ZooKeeperKeepAliveConnection zkw = hci.getKeepAliveZooKeeperWatcher();) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("Looking up meta region location in ZK," + " connection=" + this);
      }
      List<ServerName> servers = new MetaTableLocator().blockUntilAvailable(zkw, hci.rpcTimeout,
          hci.getConfiguration());
      if (LOG.isTraceEnabled()) {
        if (servers == null) {
          LOG.trace("Looked up meta region location, connection=" + this +
            "; servers = null");
        } else {
          StringBuilder str = new StringBuilder();
          for (ServerName s : servers) {
            str.append(s.toString());
            str.append(" ");
          }
          LOG.trace("Looked up meta region location, connection=" + this +
            "; servers = " + str.toString());
        }
      }
      if (servers == null) return null;
      HRegionLocation[] locs = new HRegionLocation[servers.size()];
      int i = 0;
      for (ServerName server : servers) {
        HRegionInfo h = RegionReplicaUtil.getRegionInfoForReplica(
                HRegionInfo.FIRST_META_REGIONINFO, i);
        if (server == null) locs[i++] = null;
        else locs[i++] = new HRegionLocation(h, server, 0);
      }
      return new RegionLocations(locs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private String clusterId = null;

  @Override
  public String getClusterId() {
    if (this.clusterId != null) return this.clusterId;
    // No synchronized here, worse case we will retrieve it twice, that's
    //  not an issue.
    try (ZooKeeperKeepAliveConnection zkw = hci.getKeepAliveZooKeeperWatcher()) {
      this.clusterId = ZKClusterId.readClusterIdZNode(zkw);
      if (this.clusterId == null) {
        LOG.info("ClusterId read in ZooKeeper is null");
      }
    } catch (KeeperException | IOException e) {
      LOG.warn("Can't retrieve clusterId from Zookeeper", e);
    }
    return this.clusterId;
  }

  @Override
  public int getCurrentNrHRS() throws IOException {
    try (ZooKeeperKeepAliveConnection zkw = hci.getKeepAliveZooKeeperWatcher()) {
      // We go to zk rather than to master to get count of regions to avoid
      // HTable having a Master dependency.  See HBase-2828
      return ZKUtil.getNumberOfChildren(zkw, zkw.rsZNode);
    } catch (KeeperException ke) {
      throw new IOException("Unexpected ZooKeeper exception", ke);
    }
  }

  @Override
  public void close() {
  }
}
