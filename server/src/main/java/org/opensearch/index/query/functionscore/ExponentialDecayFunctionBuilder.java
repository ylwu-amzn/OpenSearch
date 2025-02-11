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

package org.opensearch.index.query.functionscore;

import org.apache.lucene.search.Explanation;
import org.opensearch.common.Nullable;
import org.opensearch.common.bytes.BytesReference;
import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.lucene.search.function.Functions;

import java.io.IOException;

/**
 * Foundation builder for an exponential decay
 *
 * @opensearch.internal
 */
public class ExponentialDecayFunctionBuilder extends DecayFunctionBuilder<ExponentialDecayFunctionBuilder> {
    public static final String NAME = "exp";
    public static final ScoreFunctionParser<ExponentialDecayFunctionBuilder> PARSER = new DecayFunctionParser<>(
        ExponentialDecayFunctionBuilder::new
    );
    public static final DecayFunction EXP_DECAY_FUNCTION = new ExponentialDecayScoreFunction();

    public ExponentialDecayFunctionBuilder(String fieldName, Object origin, Object scale, Object offset, @Nullable String functionName) {
        super(fieldName, origin, scale, offset, functionName);
    }

    public ExponentialDecayFunctionBuilder(String fieldName, Object origin, Object scale, Object offset) {
        super(fieldName, origin, scale, offset);
    }

    public ExponentialDecayFunctionBuilder(String fieldName, Object origin, Object scale, Object offset, double decay) {
        super(fieldName, origin, scale, offset, decay);
    }

    public ExponentialDecayFunctionBuilder(
        String fieldName,
        Object origin,
        Object scale,
        Object offset,
        double decay,
        @Nullable String functionName
    ) {
        super(fieldName, origin, scale, offset, decay, functionName);
    }

    ExponentialDecayFunctionBuilder(String fieldName, BytesReference functionBytes) {
        super(fieldName, functionBytes);
    }

    /**
     * Read from a stream.
     */
    public ExponentialDecayFunctionBuilder(StreamInput in) throws IOException {
        super(in);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public DecayFunction getDecayFunction() {
        return EXP_DECAY_FUNCTION;
    }

    private static final class ExponentialDecayScoreFunction implements DecayFunction {

        @Override
        public double evaluate(double value, double scale) {
            return Math.exp(scale * value);
        }

        @Override
        public Explanation explainFunction(String valueExpl, double value, double scale, @Nullable String functionName) {
            return Explanation.match(
                (float) evaluate(value, scale),
                "exp(- " + valueExpl + " * " + -1 * scale + Functions.nameOrEmptyArg(functionName) + ")"
            );
        }

        @Override
        public double processScale(double scale, double decay) {
            return Math.log(decay) / scale;
        }

        @Override
        public int hashCode() {
            return this.getClass().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (super.equals(obj)) {
                return true;
            }
            return obj != null && getClass() != obj.getClass();
        }
    }
}
