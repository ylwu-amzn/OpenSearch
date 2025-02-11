/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.discovery;

import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.coordination.JoinHelper;
import org.opensearch.cluster.coordination.PublicationTransportHandler;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.disruption.NetworkDisruption;
import org.opensearch.test.disruption.ServiceDisruptionScheme;
import org.opensearch.test.disruption.SlowClusterStateProcessing;
import org.opensearch.test.transport.MockTransportService;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportService;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.opensearch.cluster.metadata.IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING;
import static org.opensearch.cluster.metadata.IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING;

/**
 * Tests for discovery during disruptions.
 */
@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 0)
public class DiscoveryDisruptionIT extends AbstractDisruptionTestCase {

    /**
     * Test cluster join with issues in cluster state publishing *
     */
    public void testClusterJoinDespiteOfPublishingIssues() throws Exception {
        String masterNode = internalCluster().startMasterOnlyNode();
        String nonMasterNode = internalCluster().startDataOnlyNode();

        DiscoveryNodes discoveryNodes = internalCluster().getInstance(ClusterService.class, nonMasterNode).state().nodes();

        TransportService masterTranspotService = internalCluster().getInstance(
            TransportService.class,
            discoveryNodes.getMasterNode().getName()
        );

        logger.info("blocking requests from non master [{}] to master [{}]", nonMasterNode, masterNode);
        MockTransportService nonMasterTransportService = (MockTransportService) internalCluster().getInstance(
            TransportService.class,
            nonMasterNode
        );
        nonMasterTransportService.addFailToSendNoConnectRule(masterTranspotService);

        assertNoMaster(nonMasterNode);

        logger.info("blocking cluster state publishing from master [{}] to non master [{}]", masterNode, nonMasterNode);
        MockTransportService masterTransportService = (MockTransportService) internalCluster().getInstance(
            TransportService.class,
            masterNode
        );
        TransportService localTransportService = internalCluster().getInstance(
            TransportService.class,
            discoveryNodes.getLocalNode().getName()
        );
        if (randomBoolean()) {
            masterTransportService.addFailToSendNoConnectRule(localTransportService, PublicationTransportHandler.PUBLISH_STATE_ACTION_NAME);
        } else {
            masterTransportService.addFailToSendNoConnectRule(localTransportService, PublicationTransportHandler.COMMIT_STATE_ACTION_NAME);
        }

        logger.info("allowing requests from non master [{}] to master [{}], waiting for two join request", nonMasterNode, masterNode);
        final CountDownLatch countDownLatch = new CountDownLatch(2);
        nonMasterTransportService.addSendBehavior(masterTransportService, (connection, requestId, action, request, options) -> {
            if (action.equals(JoinHelper.JOIN_ACTION_NAME)) {
                countDownLatch.countDown();
            }
            connection.sendRequest(requestId, action, request, options);
        });

        nonMasterTransportService.addConnectBehavior(masterTransportService, Transport::openConnection);

        countDownLatch.await();

        logger.info("waiting for cluster to reform");
        masterTransportService.clearOutboundRules(localTransportService);
        nonMasterTransportService.clearOutboundRules(localTransportService);

        ensureStableCluster(2);

        // shutting down the nodes, to avoid the leakage check tripping
        // on the states associated with the commit requests we may have dropped
        internalCluster().stopRandomNonMasterNode();
    }

    public void testClusterFormingWithASlowNode() {

        SlowClusterStateProcessing disruption = new SlowClusterStateProcessing(random(), 0, 0, 1000, 2000);

        // don't wait for initial state, we want to add the disruption while the cluster is forming
        internalCluster().startNodes(3);

        logger.info("applying disruption while cluster is forming ...");

        internalCluster().setDisruptionScheme(disruption);
        disruption.startDisrupting();

        ensureStableCluster(3);
    }

    public void testElectMasterWithLatestVersion() throws Exception {
        final Set<String> nodes = new HashSet<>(internalCluster().startNodes(3));
        ensureStableCluster(3);
        ServiceDisruptionScheme isolateAllNodes = new NetworkDisruption(
            new NetworkDisruption.IsolateAllNodes(nodes),
            NetworkDisruption.DISCONNECT
        );
        internalCluster().setDisruptionScheme(isolateAllNodes);

        logger.info("--> forcing a complete election to make sure \"preferred\" master is elected");
        isolateAllNodes.startDisrupting();
        for (String node : nodes) {
            assertNoMaster(node);
        }
        internalCluster().clearDisruptionScheme();
        ensureStableCluster(3);
        final String preferredMasterName = internalCluster().getMasterName();
        final DiscoveryNode preferredMaster = internalCluster().clusterService(preferredMasterName).localNode();

        logger.info("--> preferred master is {}", preferredMaster);
        final Set<String> nonPreferredNodes = new HashSet<>(nodes);
        nonPreferredNodes.remove(preferredMasterName);
        final ServiceDisruptionScheme isolatePreferredMaster = isolateMasterDisruption(NetworkDisruption.DISCONNECT);
        internalCluster().setDisruptionScheme(isolatePreferredMaster);
        isolatePreferredMaster.startDisrupting();

        client(randomFrom(nonPreferredNodes)).admin()
            .indices()
            .prepareCreate("test")
            .setSettings(
                Settings.builder().put(INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1).put(INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0)
            )
            .get();

        internalCluster().clearDisruptionScheme(false);
        internalCluster().setDisruptionScheme(isolateAllNodes);

        logger.info("--> forcing a complete election again");
        isolateAllNodes.startDisrupting();
        for (String node : nodes) {
            assertNoMaster(node);
        }

        isolateAllNodes.stopDisrupting();

        final ClusterState state = client().admin().cluster().prepareState().get().getState();
        if (state.metadata().hasIndex("test") == false) {
            fail("index 'test' was lost. current cluster state: " + state);
        }

    }

    /**
     * Adds an asymmetric break between a master and one of the nodes and makes
     * sure that the node is removed form the cluster, that the node start pinging and that
     * the cluster reforms when healed.
     */
    public void testNodeNotReachableFromMaster() throws Exception {
        startCluster(3);

        String masterNode = internalCluster().getMasterName();
        String nonMasterNode = null;
        while (nonMasterNode == null) {
            nonMasterNode = randomFrom(internalCluster().getNodeNames());
            if (nonMasterNode.equals(masterNode)) {
                nonMasterNode = null;
            }
        }

        logger.info("blocking request from master [{}] to [{}]", masterNode, nonMasterNode);
        MockTransportService masterTransportService = (MockTransportService) internalCluster().getInstance(
            TransportService.class,
            masterNode
        );
        if (randomBoolean()) {
            masterTransportService.addUnresponsiveRule(internalCluster().getInstance(TransportService.class, nonMasterNode));
        } else {
            masterTransportService.addFailToSendNoConnectRule(internalCluster().getInstance(TransportService.class, nonMasterNode));
        }

        logger.info("waiting for [{}] to be removed from cluster", nonMasterNode);
        ensureStableCluster(2, masterNode);

        logger.info("waiting for [{}] to have no cluster-manager", nonMasterNode);
        assertNoMaster(nonMasterNode);

        logger.info("healing partition and checking cluster reforms");
        masterTransportService.clearAllRules();

        ensureStableCluster(3);
    }

}
