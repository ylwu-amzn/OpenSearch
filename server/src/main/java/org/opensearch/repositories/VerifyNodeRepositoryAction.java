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

package org.opensearch.repositories;

import com.carrotsearch.hppc.ObjectContainer;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.tasks.Task;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.EmptyTransportResponseHandler;
import org.opensearch.transport.TransportChannel;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestHandler;
import org.opensearch.transport.TransportResponse;
import org.opensearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Action to verify a node repository
 *
 * @opensearch.internal
 */
public class VerifyNodeRepositoryAction {

    private static final Logger logger = LogManager.getLogger(VerifyNodeRepositoryAction.class);

    public static final String ACTION_NAME = "internal:admin/repository/verify";

    private final TransportService transportService;

    private final ClusterService clusterService;

    private final RepositoriesService repositoriesService;

    public VerifyNodeRepositoryAction(
        TransportService transportService,
        ClusterService clusterService,
        RepositoriesService repositoriesService
    ) {
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.repositoriesService = repositoriesService;
        transportService.registerRequestHandler(
            ACTION_NAME,
            ThreadPool.Names.SNAPSHOT,
            VerifyNodeRepositoryRequest::new,
            new VerifyNodeRepositoryRequestHandler()
        );
    }

    public void verify(String repository, String verificationToken, final ActionListener<List<DiscoveryNode>> listener) {
        final DiscoveryNodes discoNodes = clusterService.state().nodes();
        final DiscoveryNode localNode = discoNodes.getLocalNode();

        final ObjectContainer<DiscoveryNode> masterAndDataNodes = discoNodes.getMasterAndDataNodes().values();
        final List<DiscoveryNode> nodes = new ArrayList<>();
        for (ObjectCursor<DiscoveryNode> cursor : masterAndDataNodes) {
            DiscoveryNode node = cursor.value;
            if (RepositoriesService.isDedicatedVotingOnlyNode(node.getRoles()) == false) {
                nodes.add(node);
            }
        }
        final CopyOnWriteArrayList<VerificationFailure> errors = new CopyOnWriteArrayList<>();
        final AtomicInteger counter = new AtomicInteger(nodes.size());
        for (final DiscoveryNode node : nodes) {
            if (node.equals(localNode)) {
                try {
                    doVerify(repository, verificationToken, localNode);
                } catch (Exception e) {
                    logger.warn(() -> new ParameterizedMessage("[{}] failed to verify repository", repository), e);
                    errors.add(new VerificationFailure(node.getId(), e));
                }
                if (counter.decrementAndGet() == 0) {
                    finishVerification(repository, listener, nodes, errors);
                }
            } else {
                transportService.sendRequest(
                    node,
                    ACTION_NAME,
                    new VerifyNodeRepositoryRequest(repository, verificationToken),
                    new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {
                        @Override
                        public void handleResponse(TransportResponse.Empty response) {
                            if (counter.decrementAndGet() == 0) {
                                finishVerification(repository, listener, nodes, errors);
                            }
                        }

                        @Override
                        public void handleException(TransportException exp) {
                            errors.add(new VerificationFailure(node.getId(), exp));
                            if (counter.decrementAndGet() == 0) {
                                finishVerification(repository, listener, nodes, errors);
                            }
                        }
                    }
                );
            }
        }
    }

    private static void finishVerification(
        String repositoryName,
        ActionListener<List<DiscoveryNode>> listener,
        List<DiscoveryNode> nodes,
        CopyOnWriteArrayList<VerificationFailure> errors
    ) {
        if (errors.isEmpty() == false) {
            listener.onFailure(new RepositoryVerificationException(repositoryName, errors.toString()));
        } else {
            listener.onResponse(nodes);
        }
    }

    private void doVerify(String repositoryName, String verificationToken, DiscoveryNode localNode) {
        Repository repository = repositoriesService.repository(repositoryName);
        repository.verify(verificationToken, localNode);
    }

    public static class VerifyNodeRepositoryRequest extends TransportRequest {

        private String repository;
        private String verificationToken;

        public VerifyNodeRepositoryRequest(StreamInput in) throws IOException {
            super(in);
            repository = in.readString();
            verificationToken = in.readString();
        }

        VerifyNodeRepositoryRequest(String repository, String verificationToken) {
            this.repository = repository;
            this.verificationToken = verificationToken;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(repository);
            out.writeString(verificationToken);
        }
    }

    class VerifyNodeRepositoryRequestHandler implements TransportRequestHandler<VerifyNodeRepositoryRequest> {
        @Override
        public void messageReceived(VerifyNodeRepositoryRequest request, TransportChannel channel, Task task) throws Exception {
            DiscoveryNode localNode = clusterService.state().nodes().getLocalNode();
            try {
                doVerify(request.repository, request.verificationToken, localNode);
            } catch (Exception ex) {
                logger.warn(() -> new ParameterizedMessage("[{}] failed to verify repository", request.repository), ex);
                throw ex;
            }
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }
    }

}
