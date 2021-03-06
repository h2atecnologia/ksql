/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.structured;

import io.confluent.ksql.execution.context.QueryContext;
import io.confluent.ksql.execution.expression.tree.FunctionCall;
import io.confluent.ksql.execution.plan.ExecutionStep;
import io.confluent.ksql.execution.plan.KGroupedStreamHolder;
import io.confluent.ksql.execution.plan.KTableHolder;
import io.confluent.ksql.execution.streams.ExecutionStepFactory;
import io.confluent.ksql.execution.streams.StepSchemaResolver;
import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.parser.tree.WindowExpression;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.serde.InternalFormats;
import io.confluent.ksql.serde.KeyFormat;
import io.confluent.ksql.serde.SerdeFeatures;
import io.confluent.ksql.serde.ValueFormat;
import io.confluent.ksql.util.KsqlConfig;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class SchemaKGroupedStream {

  final ExecutionStep<KGroupedStreamHolder> sourceStep;
  final LogicalSchema schema;
  final KeyFormat keyFormat;
  final KsqlConfig ksqlConfig;
  final FunctionRegistry functionRegistry;

  SchemaKGroupedStream(
      final ExecutionStep<KGroupedStreamHolder> sourceStep,
      final LogicalSchema schema,
      final KeyFormat keyFormat,
      final KsqlConfig ksqlConfig,
      final FunctionRegistry functionRegistry
  ) {
    this.sourceStep = sourceStep;
    this.schema = Objects.requireNonNull(schema, "schema");
    this.keyFormat = Objects.requireNonNull(keyFormat, "keyFormat");
    this.ksqlConfig = Objects.requireNonNull(ksqlConfig, "ksqlConfig");
    this.functionRegistry = functionRegistry;
  }

  public ExecutionStep<KGroupedStreamHolder> getSourceStep() {
    return sourceStep;
  }

  @SuppressWarnings("unchecked")
  public SchemaKTable<?> aggregate(
      final List<ColumnName> nonAggregateColumns,
      final List<FunctionCall> aggregations,
      final Optional<WindowExpression> windowExpression,
      final ValueFormat valueFormat,
      final QueryContext.Stacker contextStacker
  ) {
    final ExecutionStep<? extends KTableHolder<?>> step;
    final KeyFormat keyFormat;

    if (windowExpression.isPresent()) {
      keyFormat = getKeyFormat(windowExpression.get());
      step = ExecutionStepFactory.streamWindowedAggregate(
          contextStacker,
          sourceStep,
          InternalFormats.of(keyFormat, valueFormat),
          nonAggregateColumns,
          aggregations,
          windowExpression.get().getKsqlWindowExpression()
      );
    } else {
      keyFormat = this.keyFormat;
      step = ExecutionStepFactory.streamAggregate(
          contextStacker,
          sourceStep,
          InternalFormats.of(keyFormat, valueFormat),
          nonAggregateColumns,
          aggregations
      );
    }

    return new SchemaKTable(
        step,
        resolveSchema(step),
        keyFormat,
        ksqlConfig,
        functionRegistry
    );
  }

  private KeyFormat getKeyFormat(final WindowExpression windowExpression) {
    return KeyFormat.windowed(
        keyFormat.getFormatInfo(),
        SerdeFeatures.of(),
        windowExpression.getKsqlWindowExpression().getWindowInfo()
    );
  }

  LogicalSchema resolveSchema(final ExecutionStep<?> step) {
    return new StepSchemaResolver(ksqlConfig, functionRegistry).resolve(step, schema);
  }
}
