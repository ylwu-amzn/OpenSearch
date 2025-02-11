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

package org.opensearch.index.engine;

import org.apache.lucene.index.SegmentInfos;
import org.opensearch.common.collect.MapBuilder;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.io.stream.Writeable;
import org.opensearch.common.lucene.Lucene;
import org.opensearch.common.xcontent.ToXContentFragment;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

/**
 * a class the returns dynamic information with respect to the last commit point of this shard
 *
 * @opensearch.internal
 */
public final class CommitStats implements Writeable, ToXContentFragment {

    private final Map<String, String> userData;
    private final long generation;
    private final String id; // lucene commit id in base 64;
    private final int numDocs;

    public CommitStats(SegmentInfos segmentInfos) {
        // clone the map to protect against concurrent changes
        userData = MapBuilder.<String, String>newMapBuilder().putAll(segmentInfos.getUserData()).immutableMap();
        // lucene calls the current generation, last generation.
        generation = segmentInfos.getLastGeneration();
        id = Base64.getEncoder().encodeToString(segmentInfos.getId());
        numDocs = Lucene.getNumDocs(segmentInfos);
    }

    CommitStats(StreamInput in) throws IOException {
        MapBuilder<String, String> builder = MapBuilder.newMapBuilder();
        for (int i = in.readVInt(); i > 0; i--) {
            builder.put(in.readString(), in.readString());
        }
        userData = builder.immutableMap();
        generation = in.readLong();
        id = in.readOptionalString();
        numDocs = in.readInt();
    }

    public static CommitStats readOptionalCommitStatsFrom(StreamInput in) throws IOException {
        return in.readOptionalWriteable(CommitStats::new);
    }

    public Map<String, String> getUserData() {
        return userData;
    }

    public long getGeneration() {
        return generation;
    }

    /** base64 version of the commit id (see {@link SegmentInfos#getId()} */
    public String getId() {
        return id;
    }

    /**
     * Returns the number of documents in the in this commit
     */
    public int getNumDocs() {
        return numDocs;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(userData.size());
        for (Map.Entry<String, String> entry : userData.entrySet()) {
            out.writeString(entry.getKey());
            out.writeString(entry.getValue());
        }
        out.writeLong(generation);
        out.writeOptionalString(id);
        out.writeInt(numDocs);
    }

    static final class Fields {
        static final String GENERATION = "generation";
        static final String USER_DATA = "user_data";
        static final String ID = "id";
        static final String COMMIT = "commit";
        static final String NUM_DOCS = "num_docs";

    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(Fields.COMMIT);
        builder.field(Fields.ID, id);
        builder.field(Fields.GENERATION, generation);
        builder.field(Fields.USER_DATA, userData);
        builder.field(Fields.NUM_DOCS, numDocs);
        builder.endObject();
        return builder;
    }
}
