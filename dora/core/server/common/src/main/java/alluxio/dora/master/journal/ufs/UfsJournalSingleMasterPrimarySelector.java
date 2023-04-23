/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.dora.master.journal.ufs;

import alluxio.dora.master.AbstractPrimarySelector;
import alluxio.grpc.NodeState;

import java.net.InetSocketAddress;

/**
 * Primary selector when using a single master deployment with UFS journal.
 */
public class UfsJournalSingleMasterPrimarySelector extends AbstractPrimarySelector {
  @Override
  public void start(InetSocketAddress localAddress) {
    setState(NodeState.PRIMARY);
  }

  @Override
  public void stop() {
    // do nothing
  }
}
