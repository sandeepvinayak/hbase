/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.replication;

public class ReplicationSourceDummyWithNoTermination extends ReplicationSourceDummy {
  volatile boolean firstTime = true;
  @Override
  public void terminate(String reason) {
    // This is to block the zk listener to close the queues
    // to simulate the znodes getting deleted without zk listener getting invoked
    if (firstTime) {
      firstTime = false;
      throw new RuntimeException(fakeExceptionMessage);
    }
  }
}
