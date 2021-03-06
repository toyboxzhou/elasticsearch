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

package org.elasticsearch.search.aggregations.bucket.geogrid;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.aggregations.AggregatorFactories.Builder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.bucket.BucketUtils;
import org.elasticsearch.search.aggregations.bucket.MultiBucketAggregationBuilder;
import org.elasticsearch.search.aggregations.support.ValueType;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregationBuilder;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.support.ValuesSourceParserHelper;
import org.elasticsearch.search.aggregations.support.ValuesSourceType;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public abstract class GeoGridAggregationBuilder extends ValuesSourceAggregationBuilder<ValuesSource.GeoPoint, GeoGridAggregationBuilder>
        implements MultiBucketAggregationBuilder {
    /* recognized field names in JSON */
    static final ParseField FIELD_PRECISION = new ParseField("precision");
    static final ParseField FIELD_SIZE = new ParseField("size");
    static final ParseField FIELD_SHARD_SIZE = new ParseField("shard_size");

    protected int precision;
    protected int requiredSize;
    protected int shardSize;

    @FunctionalInterface
    protected interface PrecisionParser {
        int parse(XContentParser parser) throws IOException;
    }

    public static ObjectParser<GeoGridAggregationBuilder, Void> createParser(String name, PrecisionParser precisionParser) {
        ObjectParser<GeoGridAggregationBuilder, Void> parser = new ObjectParser<>(name);
        ValuesSourceParserHelper.declareGeoFields(parser, false, false);
        parser.declareField((p, builder, context) -> builder.precision(precisionParser.parse(p)), FIELD_PRECISION,
            org.elasticsearch.common.xcontent.ObjectParser.ValueType.INT);
        parser.declareInt(GeoGridAggregationBuilder::size, FIELD_SIZE);
        parser.declareInt(GeoGridAggregationBuilder::shardSize, FIELD_SHARD_SIZE);
        return parser;
    }

    public GeoGridAggregationBuilder(String name) {
        super(name, ValuesSourceType.GEOPOINT, ValueType.GEOPOINT);
    }

    protected GeoGridAggregationBuilder(GeoGridAggregationBuilder clone, Builder factoriesBuilder, Map<String, Object> metaData) {
        super(clone, factoriesBuilder, metaData);
        this.precision = clone.precision;
        this.requiredSize = clone.requiredSize;
        this.shardSize = clone.shardSize;

    }

    /**
     * Read from a stream.
     */
    public GeoGridAggregationBuilder(StreamInput in) throws IOException {
        super(in, ValuesSourceType.GEOPOINT, ValueType.GEOPOINT);
        precision = in.readVInt();
        requiredSize = in.readVInt();
        shardSize = in.readVInt();
    }

    @Override
    protected void innerWriteTo(StreamOutput out) throws IOException {
        out.writeVInt(precision);
        out.writeVInt(requiredSize);
        out.writeVInt(shardSize);
    }

    /**
     * method to validate and set the precision value
     * @param precision the precision to set for the aggregation
     * @return the {@link GeoGridAggregationBuilder} builder
     */
    public abstract GeoGridAggregationBuilder precision(int precision);

    /**
     * Creates a new instance of the {@link ValuesSourceAggregatorFactory}-derived class specific to the geo aggregation.
     */
    protected abstract ValuesSourceAggregatorFactory<ValuesSource.GeoPoint,?> createFactory(
        String name, ValuesSourceConfig<ValuesSource.GeoPoint> config, int precision, int requiredSize, int shardSize,
        SearchContext context, AggregatorFactory<?> parent, Builder subFactoriesBuilder, Map<String, Object> metaData
    ) throws IOException;

    public int precision() {
        return precision;
    }

    public GeoGridAggregationBuilder size(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException(
                    "[size] must be greater than 0. Found [" + size + "] in [" + name + "]");
        }
        this.requiredSize = size;
        return this;
    }

    public int size() {
        return requiredSize;
    }

    public GeoGridAggregationBuilder shardSize(int shardSize) {
        if (shardSize <= 0) {
            throw new IllegalArgumentException(
                    "[shardSize] must be greater than 0. Found [" + shardSize + "] in [" + name + "]");
            }
        this.shardSize = shardSize;
        return this;
        }

    public int shardSize() {
        return shardSize;
    }

    @Override
    protected ValuesSourceAggregatorFactory<ValuesSource.GeoPoint, ?> innerBuild(SearchContext context,
            ValuesSourceConfig<ValuesSource.GeoPoint> config, AggregatorFactory<?> parent, Builder subFactoriesBuilder)
                    throws IOException {
        int shardSize = this.shardSize;

        int requiredSize = this.requiredSize;

        if (shardSize < 0) {
            // Use default heuristic to avoid any wrong-ranking caused by
            // distributed counting
            shardSize = BucketUtils.suggestShardSideQueueSize(requiredSize);
        }

        if (requiredSize <= 0 || shardSize <= 0) {
            throw new ElasticsearchException(
                    "parameters [required_size] and [shard_size] must be > 0 in " + getType() + " aggregation [" + name + "].");
        }

        if (shardSize < requiredSize) {
            shardSize = requiredSize;
        }
        return createFactory(name, config, precision, requiredSize, shardSize, context, parent,
                subFactoriesBuilder, metaData);
    }

    @Override
    protected XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        builder.field(FIELD_PRECISION.getPreferredName(), precision);
        builder.field(FIELD_SIZE.getPreferredName(), requiredSize);
        if (shardSize > -1) {
            builder.field(FIELD_SHARD_SIZE.getPreferredName(), shardSize);
        }
        return builder;
    }

    @Override
    protected boolean innerEquals(Object obj) {
        GeoGridAggregationBuilder other = (GeoGridAggregationBuilder) obj;
        if (precision != other.precision) {
            return false;
        }
        if (requiredSize != other.requiredSize) {
            return false;
        }
        if (shardSize != other.shardSize) {
            return false;
        }
        return true;
    }

    @Override
    protected int innerHashCode() {
        return Objects.hash(precision, requiredSize, shardSize);
    }
}
