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

package org.opensearch.index.warmer;

import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.io.stream.Writeable;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.ToXContentFragment;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * Stats collected about the warmer
 *
 * @opensearch.internal
 */
public class WarmerStats implements Writeable, ToXContentFragment {

    private long current;

    private long total;

    private long totalTimeInMillis;

    public WarmerStats() {

    }

    public WarmerStats(StreamInput in) throws IOException {
        current = in.readVLong();
        total = in.readVLong();
        totalTimeInMillis = in.readVLong();
    }

    public WarmerStats(long current, long total, long totalTimeInMillis) {
        this.current = current;
        this.total = total;
        this.totalTimeInMillis = totalTimeInMillis;
    }

    public void add(long current, long total, long totalTimeInMillis) {
        this.current += current;
        this.total += total;
        this.totalTimeInMillis += totalTimeInMillis;
    }

    public void add(WarmerStats warmerStats) {
        if (warmerStats == null) {
            return;
        }
        this.current += warmerStats.current;
        this.total += warmerStats.total;
        this.totalTimeInMillis += warmerStats.totalTimeInMillis;
    }

    public long current() {
        return this.current;
    }

    /**
     * The total number of warmer executed.
     */
    public long total() {
        return this.total;
    }

    /**
     * The total time warmer have been executed (in milliseconds).
     */
    public long totalTimeInMillis() {
        return this.totalTimeInMillis;
    }

    /**
     * The total time warmer have been executed.
     */
    public TimeValue totalTime() {
        return new TimeValue(totalTimeInMillis);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(Fields.WARMER);
        builder.field(Fields.CURRENT, current);
        builder.field(Fields.TOTAL, total);
        builder.humanReadableField(Fields.TOTAL_TIME_IN_MILLIS, Fields.TOTAL_TIME, totalTime());
        builder.endObject();
        return builder;
    }

    static final class Fields {
        static final String WARMER = "warmer";
        static final String CURRENT = "current";
        static final String TOTAL = "total";
        static final String TOTAL_TIME = "total_time";
        static final String TOTAL_TIME_IN_MILLIS = "total_time_in_millis";
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(current);
        out.writeVLong(total);
        out.writeVLong(totalTimeInMillis);
    }
}
