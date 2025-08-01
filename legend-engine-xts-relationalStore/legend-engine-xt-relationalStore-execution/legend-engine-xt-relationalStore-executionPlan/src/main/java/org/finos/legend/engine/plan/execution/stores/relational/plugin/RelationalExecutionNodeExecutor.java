// Copyright 2021 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.plan.execution.stores.relational.plugin;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.google.common.collect.Iterators;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;
import org.apache.commons.lang3.ClassUtils;
import org.eclipse.collections.api.block.function.Function2;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.tuple.Pair;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.MapAdapter;
import org.eclipse.collections.impl.tuple.Tuples;
import org.eclipse.collections.impl.utility.Iterate;
import org.finos.legend.engine.plan.dependencies.domain.dataQuality.BasicChecked;
import org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance;
import org.finos.legend.engine.plan.dependencies.store.relational.IRelationalCreateAndPopulateTempTableExecutionNodeSpecifics;
import org.finos.legend.engine.plan.dependencies.store.relational.IRelationalResult;
import org.finos.legend.engine.plan.dependencies.store.relational.classResult.IRelationalClassInstantiationNodeExecutor;
import org.finos.legend.engine.plan.dependencies.store.relational.graphFetch.IRelationalChildGraphNodeExecutor;
import org.finos.legend.engine.plan.dependencies.store.relational.graphFetch.IRelationalClassQueryTempTableGraphFetchExecutionNodeSpecifics;
import org.finos.legend.engine.plan.dependencies.store.relational.graphFetch.IRelationalCrossRootGraphNodeExecutor;
import org.finos.legend.engine.plan.dependencies.store.relational.graphFetch.IRelationalCrossRootQueryTempTableGraphFetchExecutionNodeSpecifics;
import org.finos.legend.engine.plan.dependencies.store.relational.graphFetch.IRelationalPrimitiveQueryGraphFetchExecutionNodeSpecifics;
import org.finos.legend.engine.plan.dependencies.store.relational.graphFetch.IRelationalRootGraphNodeExecutor;
import org.finos.legend.engine.plan.dependencies.store.relational.graphFetch.IRelationalRootQueryTempTableGraphFetchExecutionNodeSpecifics;
import org.finos.legend.engine.plan.dependencies.store.shared.IReferencedObject;
import org.finos.legend.engine.plan.execution.cache.ExecutionCache;
import org.finos.legend.engine.plan.execution.cache.graphFetch.GraphFetchCacheByEqualityKeys;
import org.finos.legend.engine.plan.execution.cache.graphFetch.GraphFetchCacheKey;
import org.finos.legend.engine.plan.execution.concurrent.ParallelGraphFetchExecutionExecutorPool;
import org.finos.legend.engine.plan.execution.graphFetch.AdaptiveBatching;
import org.finos.legend.engine.plan.execution.nodes.ExecutionNodeExecutor;
import org.finos.legend.engine.plan.execution.nodes.helpers.ExecutionNodeResultHelper;
import org.finos.legend.engine.plan.execution.nodes.helpers.platform.DefaultExecutionNodeContext;
import org.finos.legend.engine.plan.execution.nodes.helpers.platform.ExecutionNodeJavaPlatformHelper;
import org.finos.legend.engine.plan.execution.nodes.helpers.platform.JavaHelper;
import org.finos.legend.engine.plan.execution.nodes.state.ExecutionState;
import org.finos.legend.engine.plan.execution.nodes.state.GraphExecutionState;
import org.finos.legend.engine.plan.execution.result.ConstantResult;
import org.finos.legend.engine.plan.execution.result.Result;
import org.finos.legend.engine.plan.execution.result.ResultNormalizer;
import org.finos.legend.engine.plan.execution.result.StreamingResult;
import org.finos.legend.engine.plan.execution.result.builder._class.ClassBuilder;
import org.finos.legend.engine.plan.execution.result.graphFetch.DelayedGraphFetchResult;
import org.finos.legend.engine.plan.execution.result.graphFetch.DelayedGraphFetchResultWithExecInfo;
import org.finos.legend.engine.plan.execution.result.graphFetch.GraphFetchResult;
import org.finos.legend.engine.plan.execution.result.graphFetch.GraphObjectsBatch;
import org.finos.legend.engine.plan.execution.result.object.StreamingObjectResult;
import org.finos.legend.engine.plan.execution.result.serialization.CsvSerializer;
import org.finos.legend.engine.plan.execution.result.serialization.RequestIdGenerator;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.execution.result.serialization.TemporaryFile;
import org.finos.legend.engine.plan.execution.stores.StoreType;
import org.finos.legend.engine.plan.execution.stores.relational.RelationalDatabaseCommandsVisitorBuilder;
import org.finos.legend.engine.plan.execution.stores.relational.RelationalExecutor;
import org.finos.legend.engine.plan.execution.stores.relational.RelationalGraphFetchExecutor;
import org.finos.legend.engine.plan.execution.stores.relational.activity.AggregationAwareActivity;
import org.finos.legend.engine.plan.execution.stores.relational.blockConnection.BlockConnection;
import org.finos.legend.engine.plan.execution.stores.relational.blockConnection.BlockConnectionContext;
import org.finos.legend.engine.plan.execution.stores.relational.connection.driver.DatabaseManager;
import org.finos.legend.engine.plan.execution.stores.relational.connection.driver.commands.RelationalDatabaseCommands;
import org.finos.legend.engine.plan.execution.stores.relational.result.DatabaseIdentifiersCaseSensitiveVisitor;
import org.finos.legend.engine.plan.execution.stores.relational.result.DeferredRelationalResult;
import org.finos.legend.engine.plan.execution.stores.relational.result.FunctionHelper;
import org.finos.legend.engine.plan.execution.stores.relational.result.PreparedTempTableResult;
import org.finos.legend.engine.plan.execution.stores.relational.result.RealizedRelationalResult;
import org.finos.legend.engine.plan.execution.stores.relational.result.RelationalResult;
import org.finos.legend.engine.plan.execution.stores.relational.result.ResultColumn;
import org.finos.legend.engine.plan.execution.stores.relational.result.ResultInterpreterExtension;
import org.finos.legend.engine.plan.execution.stores.relational.result.ResultInterpreterExtensionLoader;
import org.finos.legend.engine.plan.execution.stores.relational.result.SQLExecutionResult;
import org.finos.legend.engine.plan.execution.stores.relational.result.SQLUpdateResult;
import org.finos.legend.engine.plan.execution.stores.relational.result.TempTableStreamingResult;
import org.finos.legend.engine.plan.execution.stores.relational.result.graphFetch.RelationalGraphObjectsBatch;
import org.finos.legend.engine.plan.execution.stores.relational.serialization.RealizedRelationalResultCSVSerializer;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.AggregationAwareExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.AllocationExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.ConstantExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.CreateAndPopulateTempTableExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.ErrorExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.ExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.ExecutionNodeVisitor;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.FreeMarkerConditionalExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.FunctionParametersValidationNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.GraphFetchM2MExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.JavaPlatformImplementation;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.MultiResultSequenceExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.PureExpressionPlatformExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.RelationalBlockExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.RelationalClassInstantiationExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.RelationalDataTypeInstantiationExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.RelationalExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.RelationalRelationDataInstantiationExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.RelationalSaveNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.RelationalTdsInstantiationExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.SQLExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.SequenceExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.GraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.LoadFromResultSetAsValueTuplesTempTableStrategy;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.LoadFromSubQueryTempTableStrategy;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.LoadFromTempFileTempTableStrategy;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.LocalGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalClassQueryTempTableGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalCrossRootGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalCrossRootQueryTempTableGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalPrimitiveQueryGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalRootGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalRootQueryTempTableGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.RelationalTempTableGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.StoreMappingGlobalGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.store.inMemory.InMemoryCrossStoreGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.store.inMemory.InMemoryPropertyGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.store.inMemory.InMemoryRootGraphFetchExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.nodes.graphFetch.store.inMemory.StoreStreamReadingExecutionNode;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.result.ClassResultType;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseConnection;
import org.finos.legend.engine.protocol.pure.dsl.graph.valuespecification.constant.classInstance.GraphFetchTree;
import org.finos.legend.engine.protocol.pure.dsl.graph.valuespecification.constant.classInstance.PropertyGraphFetchTree;
import org.finos.legend.engine.protocol.pure.dsl.graph.valuespecification.constant.classInstance.RootGraphFetchTree;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.collectionsExtensions.DoubleStrategyHashMap;
import org.finos.legend.engine.shared.core.identity.Identity;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class RelationalExecutionNodeExecutor implements ExecutionNodeVisitor<Result>
{
    private final ExecutionState executionState;
    private MutableList<Function2<ExecutionState, List<Map<String, Object>>, Result>> resultInterpreterExtensions;
    private Identity identity;
    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports();

    public RelationalExecutionNodeExecutor(ExecutionState executionState, Identity identity)
    {
        this.executionState = executionState;
        this.resultInterpreterExtensions = Iterate.addAllTo(ResultInterpreterExtensionLoader.extensions(), Lists.mutable.empty()).collect(ResultInterpreterExtension::additionalResultBuilder);
        this.identity = identity;
    }

    public boolean willExecuteMutation(RelationalBlockExecutionNode node)
    {
        return !Lists.mutable.withAll(node.childNodes()).select(n -> n instanceof SQLExecutionNode && ((SQLExecutionNode)n).isMutationSQL).isEmpty();
    }

    @Override
    public Result visit(ExecutionNode executionNode)
    {
        if (executionNode instanceof RelationalBlockExecutionNode)
        {
            RelationalBlockExecutionNode relationalBlockExecutionNode = (RelationalBlockExecutionNode) executionNode;
            ExecutionState connectionAwareState = new ExecutionState(this.executionState);
            ((RelationalStoreExecutionState) connectionAwareState.getStoreExecutionState(StoreType.Relational)).setRetainConnection(true);
            ((RelationalStoreExecutionState) connectionAwareState.getStoreExecutionState(StoreType.Relational)).setIsolationLevel(relationalBlockExecutionNode.isolationLevel);
            boolean willExecuteMutation = willExecuteMutation(relationalBlockExecutionNode);
            ((RelationalStoreExecutionState) connectionAwareState.getStoreExecutionState(StoreType.Relational)).setWillExecuteMutation(willExecuteMutation);

            try
            {
                Result res = new ExecutionNodeExecutor(this.identity, connectionAwareState).visit((SequenceExecutionNode) relationalBlockExecutionNode);
                ((RelationalStoreExecutionState) connectionAwareState.getStoreExecutionState(StoreType.Relational)).getBlockConnectionContext().unlockAllBlockConnections();
                return res;
            }
            catch (Exception e)
            {
                ((RelationalStoreExecutionState) connectionAwareState.getStoreExecutionState(StoreType.Relational)).getBlockConnectionContext().unlockAllBlockConnections();
                ((RelationalStoreExecutionState) connectionAwareState.getStoreExecutionState(StoreType.Relational)).getBlockConnectionContext().closeAllBlockConnections();
                throw e;
            }
        }
        else if (executionNode instanceof CreateAndPopulateTempTableExecutionNode)
        {
            CreateAndPopulateTempTableExecutionNode createAndPopulateTempTableExecutionNode = (CreateAndPopulateTempTableExecutionNode) executionNode;
            Stream<Result> results = createAndPopulateTempTableExecutionNode.inputVarNames.stream().map(this.executionState::getResult);
            Stream<?> inputStream = results.flatMap(result ->
            {
                if (result instanceof ConstantResult)
                {
                    Object value = ((ConstantResult) result).getValue();
                    if (value instanceof List)
                    {
                        return ((List<?>) value).stream();
                    }

                    if (ClassUtils.isPrimitiveOrWrapper(value.getClass()) || (value instanceof String))
                    {
                        return Stream.of(value);
                    }

                    if (value instanceof Stream)
                    {
                        return (Stream<?>) value;
                    }

                    throw new IllegalArgumentException("Result passed into CreateAndPopulateTempTableExecutionNode should be a stream");
                }
                if (result instanceof StreamingObjectResult)
                {
                    return ((StreamingObjectResult<?>) result).getObjectStream();
                }
                if (result instanceof RelationalResult)
                {
                    if (((RelationalResult) result).columnCount != 1)
                    {
                        throw new UnsupportedOperationException("Population of temp table via relational result with more than 1 column not supported");
                    }

                    // This logic handles scenario's where data is sourced from a relational query (specific use-case its targeting is when sourced list of identifiers are used with another query inside in clause)
                    // We might need to uplift this logic to handle scenario's where there are multiple columns. Since we have only 1 column for now we don't check association of relational result columns and temptable columns
                    String columnName = ((RelationalResult) result).sqlColumns.get(0);
                    return ((RelationalResult) result).toStream().map(m -> m.get(columnName).asText());
                }
                if (result instanceof DeferredRelationalResult)
                {
                    RelationalResult r = ((DeferredRelationalResult) result).evaluate();
                    return r.toStream().map(m -> m);
                }
                throw new IllegalArgumentException("Unexpected Result Type : " + result.getClass().getName());
            }).onClose(() -> results.forEach(Result::close));

            if (!(createAndPopulateTempTableExecutionNode.implementation instanceof JavaPlatformImplementation))
            {
                throw new RuntimeException("Only Java implementations are currently supported, found: " + createAndPopulateTempTableExecutionNode.implementation);
            }

            JavaPlatformImplementation javaPlatformImpl = (JavaPlatformImplementation) createAndPopulateTempTableExecutionNode.implementation;
            String executionClassName = JavaHelper.getExecutionClassFullName(javaPlatformImpl);
            Class<?> clazz = ExecutionNodeJavaPlatformHelper.getClassToExecute(createAndPopulateTempTableExecutionNode, executionClassName, this.executionState, this.identity);
            if (Arrays.asList(clazz.getInterfaces()).contains(IRelationalCreateAndPopulateTempTableExecutionNodeSpecifics.class))
            {
                try
                {
                    IRelationalCreateAndPopulateTempTableExecutionNodeSpecifics nodeSpecifics = (IRelationalCreateAndPopulateTempTableExecutionNodeSpecifics) clazz.newInstance();
                    createAndPopulateTempTableExecutionNode.tempTableColumnMetaData.forEach(t -> t.identifierForGetter = nodeSpecifics.getGetterNameForProperty(t.identifierForGetter));
                }
                catch (InstantiationException | IllegalAccessException e)
                {
                    throw new RuntimeException(e);
                }
            }
            else
            {
                // TODO Remove once platform version supports above and existing plans mitigated
                String executionMethodName = JavaHelper.getExecutionMethodName(javaPlatformImpl);
                createAndPopulateTempTableExecutionNode.tempTableColumnMetaData.forEach(t -> t.identifierForGetter = ExecutionNodeJavaPlatformHelper.executeStaticJavaMethod(createAndPopulateTempTableExecutionNode, executionClassName,
                        executionMethodName, Collections.singletonList(Result.class), Collections.singletonList(new ConstantResult(t.identifierForGetter)), this.executionState, this.identity));
            }

            RelationalDatabaseCommands databaseCommands = DatabaseManager.fromString(createAndPopulateTempTableExecutionNode.connection.type.name()).relationalDatabaseSupport();
            try (Connection connectionManagerConnection = this.getConnection(createAndPopulateTempTableExecutionNode, databaseCommands, this.identity, this.executionState))
            {
                TempTableStreamingResult tempTableStreamingResult = new TempTableStreamingResult(inputStream, createAndPopulateTempTableExecutionNode);
                String databaseTimeZone = createAndPopulateTempTableExecutionNode.connection.timeZone == null ? RelationalExecutor.DEFAULT_DB_TIME_ZONE : createAndPopulateTempTableExecutionNode.connection.timeZone;
                if (((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).willExecuteMutation())
                {
                    databaseCommands.accept(RelationalDatabaseCommandsVisitorBuilder.getStreamResultToTableVisitor(((RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().getRelationalExecutionConfiguration(), connectionManagerConnection, tempTableStreamingResult, createAndPopulateTempTableExecutionNode.tempTableName, databaseTimeZone));

                }
                else
                {
                    databaseCommands.accept(RelationalDatabaseCommandsVisitorBuilder.getStreamResultToTempTableVisitor(((RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().getRelationalExecutionConfiguration(), connectionManagerConnection, tempTableStreamingResult, createAndPopulateTempTableExecutionNode.tempTableName, databaseTimeZone));
                }
            }
            catch (SQLException e)
            {
                throw new RuntimeException(e);
            }
            return new ConstantResult("success");
        }
        else if (executionNode instanceof RelationalExecutionNode)
        {
            RelationalExecutionNode relationalExecutionNode = (RelationalExecutionNode) executionNode;
            Span topSpan = GlobalTracer.get().activeSpan();
            this.executionState.topSpan = topSpan;
            try (Scope scope = GlobalTracer.get().buildSpan("Relational DB Execution").startActive(true))
            {
                scope.span().setTag("databaseType", relationalExecutionNode.getDatabaseTypeName());
                scope.span().setTag("sql", relationalExecutionNode.sqlQuery());
                Result result = ((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().execute(relationalExecutionNode, this.identity, this.executionState);
                if (result instanceof RelationalResult && executionState.logSQLWithParamValues())
                {
                    scope.span().setTag("executedSql", ((RelationalResult) result).executedSQl);
                }
                if (relationalExecutionNode.implementation != null && !(ExecutionNodeResultHelper.isResultSizeRangeSet(relationalExecutionNode) && ExecutionNodeResultHelper.isSingleRecordResult(relationalExecutionNode)))
                {
                    return executeImplementation(relationalExecutionNode, result, this.executionState, this.identity);
                }
                return result;
            }
        }
        else if (executionNode instanceof SQLExecutionNode)
        {
            SQLExecutionNode SQLExecutionNode = (SQLExecutionNode) executionNode;
            this.executionState.topSpan = GlobalTracer.get().activeSpan();
            try (Scope scope = GlobalTracer.get().buildSpan("Relational DB Execution").startActive(true))
            {
                scope.span().setTag("databaseType", SQLExecutionNode.getDatabaseTypeName());
                scope.span().setTag("executionTraceID", this.executionState.execID);
                scope.span().setTag("sql", SQLExecutionNode.sqlQuery());
                Result result = ((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().execute(SQLExecutionNode, identity, executionState);
                if (result instanceof SQLExecutionResult && executionState.logSQLWithParamValues())
                {
                    scope.span().setTag("executedSql", ((SQLExecutionResult) result).getExecutedSql());
                }

                if (result instanceof SQLUpdateResult)
                {
                    return new ConstantResult(((SQLUpdateResult) result).getUpdateCount());
                }

                return result;
            }
        }
        else if (executionNode instanceof RelationalSaveNode)
        {
            RelationalSaveNode relationalSaveNode = (RelationalSaveNode) executionNode;
            Result sources = Iterate.getOnly(relationalSaveNode.childNodes()).accept(new ExecutionNodeExecutor(this.identity, this.executionState));
            if (!(sources instanceof StreamingObjectResult))
            {
                throw new RuntimeException("Only StreamingObjectResults can be save sources!");
            }
            StreamingObjectResult<?> streamingObjectResult = (StreamingObjectResult<?>) sources;
            try (Scope scope = GlobalTracer.get().buildSpan("Relational DB Execution").startActive(true))
            {
                // TODO HSO: Handle many, need to handle other result types?
                Iterator<?> sourcesIterator = streamingObjectResult.getObjectStream().iterator();
                if (!sourcesIterator.hasNext())
                {
                    throw new RuntimeException("No sources to save found!");
                }
                Object source = sourcesIterator.next();
                if (sourcesIterator.hasNext())
                {
                    throw new RuntimeException("Only a single save source is currently supported!");
                }

                // Process each object on stream using generated variable name
                this.executionState.addResult(relationalSaveNode.getGeneratedVariableName(), new ConstantResult(source));
                MapAdapter.adapt(relationalSaveNode.getColumnValueGenerators())
                        .forEachKeyValue((columnName, node) ->
                        {
                            Result columnValue = node.accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                            if (columnValue instanceof StreamingResult)
                            {
                                StreamingResult sr = ((StreamingResult)columnValue);
                                columnValue = new ConstantResult(sr.flush(sr.getSerializer(SerializationFormat.DEFAULT)));
                            }
                            Result realizedColumnValue = columnValue.realizeInMemory();
                            this.executionState.addResult(columnName, realizedColumnValue);
                        });

                this.executionState.topSpan = GlobalTracer.get().activeSpan();
                scope.span().setTag("databaseType", relationalSaveNode.getDatabaseTypeName());
                scope.span().setTag("executionTraceID", this.executionState.execID);
                scope.span().setTag("sql", relationalSaveNode.sqlQuery());
                SQLUpdateResult sqlUpdateResult = ((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().execute(relationalSaveNode, identity, executionState);
                return new ConstantResult(String.format("Success - %d rows updated!", sqlUpdateResult.getUpdateCount()));
            }
            finally
            {
                streamingObjectResult.getObjectStream().close();
            }
        }
        else if (executionNode instanceof RelationalTdsInstantiationExecutionNode)
        {
            RelationalTdsInstantiationExecutionNode relationalTdsInstantiationExecutionNode = (RelationalTdsInstantiationExecutionNode) executionNode;
            Result sqlExecutionResult = null;
            try
            {
                if (this.executionState.inAllocation && !this.executionState.realizeInMemory)
                {
                    return new DeferredRelationalResult((RelationalTdsInstantiationExecutionNode) executionNode, this.identity, this.executionState.copy());
                }

                sqlExecutionResult = this.visit(relationalTdsInstantiationExecutionNode.executionNodes.get(0));
                RelationalResult relationalTdsResult = new RelationalResult((SQLExecutionResult) sqlExecutionResult, relationalTdsInstantiationExecutionNode);

                if (this.executionState.inAllocation)
                {
                    if (!this.executionState.realizeInMemory)
                    {
                        return relationalTdsResult;
                    }
                    RealizedRelationalResult realizedRelationalResult = (RealizedRelationalResult) relationalTdsResult.realizeInMemory();
                    List<Map<String, Object>> rowValueMaps = realizedRelationalResult.getRowValueMaps(false);
                    Result res = RelationalExecutor.evaluateAdditionalExtractors(this.resultInterpreterExtensions, this.executionState, rowValueMaps);
                    if (res != null)
                    {
                        return res;
                    }
                    else
                    {
                        return new ConstantResult(rowValueMaps);
                    }
                }

                return relationalTdsResult;
            }
            catch (Exception e)
            {
                if (sqlExecutionResult != null)
                {
                    sqlExecutionResult.close();
                }
                throw e;
            }
        }
        else if (executionNode instanceof RelationalClassInstantiationExecutionNode)
        {
            RelationalClassInstantiationExecutionNode node = (RelationalClassInstantiationExecutionNode) executionNode;
            SQLExecutionResult sqlExecutionResult = null;
            try
            {
                SQLExecutionNode innerNode = (SQLExecutionNode) node.executionNodes.get(0);
                sqlExecutionResult = (SQLExecutionResult) this.visit(innerNode);
                RelationalResult relationalResult = new RelationalResult(sqlExecutionResult, node);

                boolean realizeAsConstant = this.executionState.inAllocation && ExecutionNodeResultHelper.isResultSizeRangeSet(node) && ExecutionNodeResultHelper.isSingleRecordResult(node);

                if (realizeAsConstant)
                {
                    RealizedRelationalResult realizedRelationalResult = (RealizedRelationalResult) relationalResult.realizeInMemory();
                    List<Map<String, Object>> rowValueMaps = realizedRelationalResult.getRowValueMaps(false);
                    if (rowValueMaps.size() == 1)
                    {
                        return new ConstantResult(rowValueMaps.get(0));
                    }
                    else
                    {
                        return new ConstantResult(rowValueMaps);
                    }
                }

                return this.getStreamingObjectResultFromRelationalResult(node, relationalResult, innerNode.connection);
            }
            catch (Exception e)
            {
                if (sqlExecutionResult != null)
                {
                    sqlExecutionResult.close();
                }
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        }
        else if (executionNode instanceof RelationalRelationDataInstantiationExecutionNode)
        {
            RelationalRelationDataInstantiationExecutionNode node = (RelationalRelationDataInstantiationExecutionNode) executionNode;
            SQLExecutionResult sqlExecutionResult = null;
            try
            {
                sqlExecutionResult = (SQLExecutionResult) this.visit((SQLExecutionNode) node.executionNodes.get(0));
                return new RelationalResult(sqlExecutionResult, node);
            }
            catch (Exception e)
            {
                if (sqlExecutionResult != null)
                {
                    sqlExecutionResult.close();
                }
                throw e;
            }
        }
        else if (executionNode instanceof RelationalDataTypeInstantiationExecutionNode)
        {
            RelationalDataTypeInstantiationExecutionNode node = (RelationalDataTypeInstantiationExecutionNode) executionNode;
            SQLExecutionResult sqlExecutionResult = null;
            try
            {
                sqlExecutionResult = (SQLExecutionResult) this.visit((SQLExecutionNode) node.executionNodes.get(0));
                RelationalResult relationalPrimitiveResult = new RelationalResult(sqlExecutionResult, node);

                if (this.executionState.inAllocation)
                {
                    if ((ExecutionNodeResultHelper.isResultSizeRangeSet(node) && !ExecutionNodeResultHelper.isSingleRecordResult(node)) && !this.executionState.realizeInMemory)
                    {
                        return relationalPrimitiveResult;
                    }

                    try
                    {
                        if (relationalPrimitiveResult.getResultSet().next())
                        {
                            List<org.eclipse.collections.api.block.function.Function<Object, Object>> transformers = relationalPrimitiveResult.getTransformers();
                            Object convertedValue = transformers.get(0).valueOf(relationalPrimitiveResult.getResultSet().getObject(1));
                            return new ConstantResult(convertedValue);
                        }
                        else
                        {
                            throw new RuntimeException("Result set is empty for allocation node");
                        }
                    }
                    finally
                    {
                        relationalPrimitiveResult.close();
                    }
                }
                return relationalPrimitiveResult;
            }
            catch (Exception e)
            {
                if (sqlExecutionResult != null)
                {
                    sqlExecutionResult.close();
                }
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        }
        else if (executionNode instanceof RelationalRootQueryTempTableGraphFetchExecutionNode)
        {
            return this.executeRelationalRootQueryTempTableGraphFetchExecutionNode((RelationalRootQueryTempTableGraphFetchExecutionNode) executionNode);
        }
        else if (executionNode instanceof RelationalCrossRootQueryTempTableGraphFetchExecutionNode)
        {
            return this.executeRelationalCrossRootQueryTempTableGraphFetchExecutionNode((RelationalCrossRootQueryTempTableGraphFetchExecutionNode) executionNode);
        }
        else if (executionNode instanceof RelationalPrimitiveQueryGraphFetchExecutionNode)
        {
            return this.executeRelationalPrimitiveQueryGraphFetchExecutionNode((RelationalPrimitiveQueryGraphFetchExecutionNode) executionNode);
        }
        else if (executionNode instanceof RelationalClassQueryTempTableGraphFetchExecutionNode)
        {
            return this.executeRelationalClassQueryTempTableGraphFetchExecutionNode((RelationalClassQueryTempTableGraphFetchExecutionNode) executionNode);
        }
        else if (executionNode instanceof RelationalRootGraphFetchExecutionNode)
        {
            RelationalRootGraphFetchExecutionNode node = (RelationalRootGraphFetchExecutionNode) executionNode;
            /* Fetch info from execution state */
            GraphExecutionState graphExecutionState = (GraphExecutionState) executionState;
            int batchSize = graphExecutionState.getBatchSize();
            SQLExecutionResult rootResult = (SQLExecutionResult) graphExecutionState.getRootResult();
            ResultSet rootResultSet = rootResult.getResultSet();

            /* Ensure all children run in the same connection */
            RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) graphExecutionState.getStoreExecutionState(StoreType.Relational);
            BlockConnectionContext oldBlockConnectionContext = relationalStoreExecutionState.getBlockConnectionContext();
            boolean oldRetainConnectionFlag = relationalStoreExecutionState.retainConnection();
            relationalStoreExecutionState.setBlockConnectionContext(new BlockConnectionContext());
            relationalStoreExecutionState.setRetainConnection(true);

            try (Scope ignored1 = GlobalTracer.get().buildSpan("Graph Query Relational: Execute Relational Root").startActive(true))
            {
                String databaseTimeZone = rootResult.getDatabaseTimeZone();
                String databaseConnectionString = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(rootResult.getSQLExecutionNode().connection);

                /* Get Java executor */
                Class<?> executeClass = this.getExecuteClass(node);
                if (Arrays.asList(executeClass.getInterfaces()).contains(IRelationalRootGraphNodeExecutor.class))
                {
                    IRelationalRootGraphNodeExecutor executor = (IRelationalRootGraphNodeExecutor) executeClass.getConstructor().newInstance();
                    List<Method> primaryKeyGetters = executor.primaryKeyGetters();
                    int primaryKeyCount = primaryKeyGetters.size();

                    /* Check if caching is enabled and fetch the cache if required */
                    boolean cachingEnabledForNode = false;
                    ExecutionCache<GraphFetchCacheKey, Object> graphCache = null;
                    RelationalGraphFetchUtils.RelationalSQLResultGraphFetchCacheKey rootResultCacheKey = null;

                    if ((this.executionState.graphFetchCaches != null) && executor.supportsCaching())
                    {
                        GraphFetchCacheByEqualityKeys graphFetchCacheByEqualityKeys = RelationalGraphFetchUtils.findCacheByEqualityKeys(
                                node.graphFetchTree,
                                executor.getMappingId(rootResultSet, databaseTimeZone, databaseConnectionString),
                                executor.getInstanceSetId(rootResultSet, databaseTimeZone, databaseConnectionString),
                                this.executionState.graphFetchCaches
                        );

                        if (graphFetchCacheByEqualityKeys != null)
                        {
                            List<String> parentSQLKeyColumns = executor.primaryKeyColumns();
                            List<Integer> parentPrimaryKeyIndices = FastList.newList();
                            for (String pkCol : parentSQLKeyColumns)
                            {
                                parentPrimaryKeyIndices.add(rootResultSet.findColumn(pkCol));
                            }

                            cachingEnabledForNode = true;
                            graphCache = graphFetchCacheByEqualityKeys.getExecutionCache();
                            rootResultCacheKey = new RelationalGraphFetchUtils.RelationalSQLResultGraphFetchCacheKey(rootResult, parentPrimaryKeyIndices);
                        }
                    }

                    /* Get the next batch of root records */
                    List<Object> resultObjectsBatch = new ArrayList<>();
                    List<org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance<?>> instancesToDeepFetch = new ArrayList<>();

                    int objectCount = 0;

                    try (Scope ignored2 = GlobalTracer.get().buildSpan("Graph Query Relational: Read Next Batch").startActive(true))
                    {
                        while (rootResultSet.next())
                        {
                            graphExecutionState.incrementRowCount();

                            boolean shouldDeepFetchOnThisInstance = true;
                            if (cachingEnabledForNode)
                            {
                                Object cachedObject = graphCache.getIfPresent(rootResultCacheKey);
                                if (cachedObject != null)
                                {
                                    resultObjectsBatch.add(executor.deepCopy(cachedObject));
                                    shouldDeepFetchOnThisInstance = false;
                                }
                            }

                            if (shouldDeepFetchOnThisInstance)
                            {
                                org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance<?> wrappedObject = executor.getObjectFromResultSet(rootResultSet, databaseTimeZone, databaseConnectionString);
                                instancesToDeepFetch.add(wrappedObject);
                                resultObjectsBatch.add(wrappedObject.getValue());
                            }
                            objectCount += 1;

                            if (objectCount >= batchSize)
                            {
                                break;
                            }
                        }
                    }

                    if (!instancesToDeepFetch.isEmpty())
                    {
                        boolean childrenExist = node.children != null && !node.children.isEmpty();

                        String tempTableName = node.tempTableName;
                        RealizedRelationalResult realizedRelationalResult = RealizedRelationalResult.emptyRealizedRelationalResult(node.columns);

                        /* Create and populate double strategy map with key being object with its PK getters */
                        DoubleStrategyHashMap<Object, Object, SQLExecutionResult> rootMap = new DoubleStrategyHashMap<>(
                                RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategyWithEmptySecondStrategy(primaryKeyGetters)
                        );
                        for (org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance<?> rootGraphInstance : instancesToDeepFetch)
                        {
                            Object rootObject = rootGraphInstance.getValue();
                            rootMap.put(rootObject, rootObject);
                            graphExecutionState.addObjectMemoryUtilization(rootGraphInstance.instanceSize());
                            if (childrenExist)
                            {
                                this.addKeyRowToRealizedRelationalResult(rootObject, primaryKeyGetters, realizedRelationalResult);
                            }
                        }

                        /* Execute store local children */
                        if (childrenExist)
                        {
                            this.executeRelationalChildren(node, tempTableName, realizedRelationalResult, rootResult.getSQLExecutionNode().connection, rootResult.getDatabaseType(), databaseTimeZone, rootMap, primaryKeyGetters);
                        }
                    }

                    graphExecutionState.setObjectsForNodeIndex(node.nodeIndex, resultObjectsBatch);

                    if (cachingEnabledForNode)
                    {
                        for (org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance<?> deepFetchedInstance : instancesToDeepFetch)
                        {
                            Object objectClone = executor.deepCopy(deepFetchedInstance.getValue());
                            graphCache.put(new RelationalGraphFetchUtils.RelationalObjectGraphFetchCacheKey(objectClone, primaryKeyGetters), objectClone);
                        }
                    }
                    return new ConstantResult(resultObjectsBatch);
                }
                else
                {
                    throw new RuntimeException("Unknown execute class " + executeClass.getCanonicalName());
                }
            }
            catch (RuntimeException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            finally
            {
                relationalStoreExecutionState.getBlockConnectionContext().unlockAllBlockConnections();
                relationalStoreExecutionState.getBlockConnectionContext().closeAllBlockConnectionsAsync();
                relationalStoreExecutionState.setBlockConnectionContext(oldBlockConnectionContext);
                relationalStoreExecutionState.setRetainConnection(oldRetainConnectionFlag);
            }
        }
        else if (executionNode instanceof RelationalCrossRootGraphFetchExecutionNode)
        {
            RelationalCrossRootGraphFetchExecutionNode node = (RelationalCrossRootGraphFetchExecutionNode) executionNode;
            GraphExecutionState graphExecutionState = (GraphExecutionState) executionState;
            List<?> parentObjects = graphExecutionState.getObjectsToGraphFetch();

            List<Object> childObjects = FastList.newList();
            graphExecutionState.setObjectsForNodeIndex(node.nodeIndex, childObjects);

            RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) graphExecutionState.getStoreExecutionState(StoreType.Relational);
            BlockConnectionContext oldBlockConnectionContext = relationalStoreExecutionState.getBlockConnectionContext();
            boolean oldRetainConnectionFlag = relationalStoreExecutionState.retainConnection();
            relationalStoreExecutionState.setBlockConnectionContext(new BlockConnectionContext());
            relationalStoreExecutionState.setRetainConnection(true);

            SQLExecutionResult childResult = null;
            try (Scope ignored1 = GlobalTracer.get().buildSpan("Graph Query Relational: Execute Relational Cross Root").startActive(true))
            {
                /* Get Java executor */
                Class<?> executeClass = this.getExecuteClass(node);
                if (Arrays.asList(executeClass.getInterfaces()).contains(IRelationalCrossRootGraphNodeExecutor.class))
                {
                    IRelationalCrossRootGraphNodeExecutor executor = (IRelationalCrossRootGraphNodeExecutor) executeClass.getConstructor().newInstance();

                    if (!parentObjects.isEmpty())
                    {
                        String parentTempTableName = node.parentTempTableName;
                        RealizedRelationalResult parentRealizedRelationalResult = RealizedRelationalResult.emptyRealizedRelationalResult(node.parentTempTableColumns);

                        List<Method> crossKeyGetters = executor.parentCrossKeyGetters();
                        int parentKeyCount = crossKeyGetters.size();

                        for (Object parentObject : parentObjects)
                        {
                            this.addKeyRowToRealizedRelationalResult(parentObject, crossKeyGetters, parentRealizedRelationalResult);
                        }
                        graphExecutionState.addResult(parentTempTableName, parentRealizedRelationalResult);

                        /* Execute relational node corresponding to the cross root */
                        childResult = (SQLExecutionResult) node.relationalNode.accept(new ExecutionNodeExecutor(this.identity, graphExecutionState));
                        ResultSet childResultSet = childResult.getResultSet();

                        boolean childrenExist = node.children != null && !node.children.isEmpty();


                        String tempTableName = childrenExist ? node.tempTableName : null;
                        RealizedRelationalResult realizedRelationalResult = childrenExist ?
                                RealizedRelationalResult.emptyRealizedRelationalResult(node.columns) :
                                null;
                        DatabaseConnection databaseConnection = childResult.getSQLExecutionNode().connection;
                        String databaseType = childResult.getDatabaseType();
                        String databaseTimeZone = childResult.getDatabaseTimeZone();


                        List<String> parentSQLKeyColumns = executor.parentSQLColumnsInResultSet(childResult.getResultColumns().stream().map(ResultColumn::getNonQuotedLabel).collect(Collectors.toList()));
                        List<Integer> parentCrossKeyIndices = FastList.newList();
                        for (String pkCol : parentSQLKeyColumns)
                        {
                            parentCrossKeyIndices.add(childResultSet.findColumn(pkCol));
                        }

                        DoubleStrategyHashMap<Object, List<Object>, SQLExecutionResult> parentMap = new DoubleStrategyHashMap<>(
                                RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategy(crossKeyGetters, parentCrossKeyIndices)
                        );

                        for (Object parentObject : parentObjects)
                        {
                            List<Object> mapped = parentMap.get(parentObject);
                            if (mapped == null)
                            {
                                parentMap.put(parentObject, FastList.newListWith(parentObject));
                            }
                            else
                            {
                                mapped.add(parentObject);
                            }
                        }

                        List<Method> primaryKeyGetters = executor.primaryKeyGetters();
                        final int primaryKeyCount = primaryKeyGetters.size();
                        DoubleStrategyHashMap<Object, Object, SQLExecutionResult> currentMap = new DoubleStrategyHashMap<>(RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategyWithEmptySecondStrategy(primaryKeyGetters));
                        String databaseConnectionString = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(childResult.getSQLExecutionNode().connection);
                        while (childResultSet.next())
                        {
                            graphExecutionState.incrementRowCount();
                            List<Object> parents = parentMap.getWithSecondKey(childResult);
                            if (parents == null)
                            {
                                throw new RuntimeException("No parent");
                            }

                            org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance<?> childGraphInstance = executor.getObjectFromResultSet(childResultSet, childResult.getDatabaseTimeZone(), databaseConnectionString);
                            Object child = childGraphInstance.getValue();
                            Object mapObject = currentMap.putIfAbsent(child, child);
                            if (mapObject == null)
                            {
                                mapObject = child;
                                childObjects.add(mapObject);
                                graphExecutionState.addObjectMemoryUtilization(childGraphInstance.instanceSize());
                                if (childrenExist)
                                {
                                    this.addKeyRowToRealizedRelationalResult(child, primaryKeyGetters, realizedRelationalResult);
                                }
                            }

                            for (Object parent : parents)
                            {
                                executor.addChildToParent(parent, mapObject, DefaultExecutionNodeContext.factory().create(graphExecutionState, null));
                            }
                        }

                        childResult.close();
                        childResult = null;

                        if (childrenExist)
                        {
                            this.executeRelationalChildren(node, tempTableName, realizedRelationalResult, databaseConnection, databaseType, databaseTimeZone, currentMap, primaryKeyGetters);
                        }
                    }
                }
                else
                {
                    throw new RuntimeException("Unknown execute class " + executeClass.getCanonicalName());
                }
            }
            catch (RuntimeException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            finally
            {
                if (childResult != null)
                {
                    childResult.close();
                }

                relationalStoreExecutionState.getBlockConnectionContext().unlockAllBlockConnections();
                relationalStoreExecutionState.getBlockConnectionContext().closeAllBlockConnectionsAsync();

                relationalStoreExecutionState.setBlockConnectionContext(oldBlockConnectionContext);
                relationalStoreExecutionState.setRetainConnection(oldRetainConnectionFlag);
            }

            return new ConstantResult(childObjects);
        }
        throw new RuntimeException("Not implemented!");
    }

    private void executeRelationalChildren(RelationalGraphFetchExecutionNode node, String tempTableNameFromNode, RealizedRelationalResult realizedRelationalResult, DatabaseConnection databaseConnection, String databaseType, String databaseTimeZone, DoubleStrategyHashMap<Object, Object, SQLExecutionResult> parentMap, List<Method> parentKeyGetters)
    {
        try (Scope ignored1 = GlobalTracer.get().buildSpan("Graph Query Relational: Execute Children").startActive(true))
        {
            RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational);
            String tempTableName = "temp_table_" + tempTableNameFromNode;
            DatabaseManager databaseManager = DatabaseManager.fromString(databaseType);

            try (Scope ignored2 = GlobalTracer.get().buildSpan("Graph Query Relational: Create Temp Table").startActive(true))
            {
                createTempTableFromRealizedRelationalResultInBlockConnection(realizedRelationalResult, tempTableName, databaseConnection, databaseType, databaseTimeZone, this.executionState, this.identity);
            }

            this.executionState.addResult(tempTableNameFromNode, new PreparedTempTableResult(tempTableName));

            for (RelationalGraphFetchExecutionNode childNode : node.children)
            {
                try (Scope ignored3 = GlobalTracer.get().buildSpan("Graph Query Relational: Execute Child").startActive(true))
                {
                    this.executeLocalRelationalGraphOperation(childNode, parentMap, parentKeyGetters);
                }
            }
        }
    }

    private static void createTempTableFromRealizedRelationalResultInBlockConnection(RealizedRelationalResult realizedRelationalResult, String tempTableName, DatabaseConnection databaseConnection, String databaseType, String databaseTimeZone, ExecutionState threadExecutionState, Identity identity)
    {
        try (Scope ignored = GlobalTracer.get().buildSpan("create temp table").withTag("tempTableName", tempTableName).withTag("databaseType", databaseType).startActive(true))
        {
            RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) threadExecutionState.getStoreExecutionState(StoreType.Relational);
            DatabaseManager databaseManager = DatabaseManager.fromString(databaseType);
            BlockConnection blockConnection = relationalStoreExecutionState.getBlockConnectionContext().getBlockConnection(relationalStoreExecutionState, databaseConnection, identity);
            databaseManager.relationalDatabaseSupport().accept(RelationalDatabaseCommandsVisitorBuilder.getStreamResultToTempTableVisitor(relationalStoreExecutionState.getRelationalExecutor().getRelationalExecutionConfiguration(), blockConnection, realizedRelationalResult, tempTableName, databaseTimeZone));
            blockConnection.addCommitQuery(databaseManager.relationalDatabaseSupport().dropTempTable(tempTableName));
            blockConnection.addRollbackQuery(databaseManager.relationalDatabaseSupport().dropTempTable(tempTableName));
            blockConnection.close();
        }
    }

    private static void createTempTableFromRealizedRelationalResultUsingTempTableStrategyInBlockConnection(RelationalTempTableGraphFetchExecutionNode node, RealizedRelationalResult realizedRelationalResult, String tempTableName, DatabaseConnection databaseConnection, String databaseType, String databaseTimeZone, ExecutionState threadExecutionState, Identity identity)
    {
        RelationalStoreExecutionState relationalStoreExecutionState = null;
        boolean tempTableCreated = false;
        try (Scope ignored = GlobalTracer.get().buildSpan("create temp table").withTag("tempTableName", tempTableName).withTag("databaseType", databaseType).startActive(true))
        {
            relationalStoreExecutionState = (RelationalStoreExecutionState) threadExecutionState.getStoreExecutionState(StoreType.Relational);
            relationalStoreExecutionState.setRetainConnection(true);

            node.tempTableStrategy.createTempTableNode.accept(new ExecutionNodeExecutor(identity, threadExecutionState));
            tempTableCreated = true;

            if (node.tempTableStrategy instanceof LoadFromSubQueryTempTableStrategy)
            {
                node.tempTableStrategy.loadTempTableNode.accept(new ExecutionNodeExecutor(identity, threadExecutionState));
            }
            else if (node.tempTableStrategy instanceof LoadFromResultSetAsValueTuplesTempTableStrategy)
            {
                loadValuesIntoTempTablesFromRelationalResult(node.tempTableStrategy.loadTempTableNode, realizedRelationalResult, ((LoadFromResultSetAsValueTuplesTempTableStrategy) node.tempTableStrategy).tupleBatchSize, ((LoadFromResultSetAsValueTuplesTempTableStrategy) node.tempTableStrategy).quoteCharacterReplacement, databaseTimeZone, threadExecutionState, identity);
            }
            else if (node.tempTableStrategy instanceof LoadFromTempFileTempTableStrategy)
            {
                String requestId = RequestIdGenerator.generateId();
                String tempTableNameForFileName = tempTableName.startsWith("\"") && tempTableName.endsWith("\"") ? tempTableName.substring(1, tempTableName.length() - 1) : tempTableName;
                String fileName = tempTableNameForFileName + requestId;
                try (TemporaryFile tempFile = new TemporaryFile(((RelationalStoreExecutionState) threadExecutionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().getRelationalExecutionConfiguration().tempPath, fileName))
                {
                    CsvSerializer csvSerializer = new RealizedRelationalResultCSVSerializer(realizedRelationalResult, databaseTimeZone, true, false);
                    tempFile.writeFile(csvSerializer);
                    prepareExecutionStateForTempTableExecution("csv_file_location", threadExecutionState, tempFile.getTemporaryPathForFile());
                    node.tempTableStrategy.loadTempTableNode.accept(new ExecutionNodeExecutor(identity, threadExecutionState));
                }
                catch (Exception e)
                {
                    throw new RuntimeException("Could not create Temp table", e);
                }
            }
            else
            {
                throw new RuntimeException("Unsupported temp table strategy");
            }
        }
        finally
        {
            if (relationalStoreExecutionState != null && tempTableCreated)
            {
                String dropTempTableSqlQuery = ((SQLExecutionNode) node.tempTableStrategy.dropTempTableNode.executionNodes.get(0)).sqlQuery;
                BlockConnection blockConnection = relationalStoreExecutionState.getBlockConnectionContext().getBlockConnection(relationalStoreExecutionState, databaseConnection, identity);
                blockConnection.addCommitQuery(dropTempTableSqlQuery);
                blockConnection.addRollbackQuery(dropTempTableSqlQuery);
                blockConnection.close();
            }
        }
    }

    static Function<Object, String> getNormalizer(String quoteCharacterReplacement, String databaseTimeZone)
    {
        return v ->
        {
            if (v == null)
            {
                return "null";
            }
            if (v instanceof Number)
            {
                return (String) ResultNormalizer.normalizeToSql(v, databaseTimeZone);
            }
            if (v instanceof String)
            {
                if (quoteCharacterReplacement != null)
                {
                    return "'" + ((String) ResultNormalizer.normalizeToSql(v, databaseTimeZone)).replace("'", quoteCharacterReplacement) + "'";
                }
                return "'" + ResultNormalizer.normalizeToSql(v, databaseTimeZone) + "'";
            }
            // TODO - Implement db specific processing for boolean type
            return "'" + ResultNormalizer.normalizeToSql(v, databaseTimeZone) + "'";
        };
    }

    private static void loadValuesIntoTempTablesFromRelationalResult(ExecutionNode node, RealizedRelationalResult realizedRelationalResult, int batchSize, String quoteCharacterReplacement, String databaseTimeZone, ExecutionState threadExecutionState, Identity identity)
    {
        final Function<Object, String> normalizer = getNormalizer(quoteCharacterReplacement, databaseTimeZone);

        Iterator<List<List<Object>>> rowBatchIterator = Iterators.partition(realizedRelationalResult.resultSetRows.iterator(), batchSize);
        while (rowBatchIterator.hasNext())
        {
            String valuesTuples = rowBatchIterator.next()
                    .stream()
                    .map(row -> row.stream().map(normalizer).collect(Collectors.joining(",", "(", ")")))
                    .collect(Collectors.joining(",", "", ""));
            RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) threadExecutionState.getStoreExecutionState(StoreType.Relational);
            relationalStoreExecutionState.setIgnoreFreeMarkerProcessing(true);
            try
            {
                ingestDataIntoTempTable(node, valuesTuples, threadExecutionState, identity);
            }
            catch (IOException e)
            {
                throw new RuntimeException("Unable to copy execution nodes and ingest data into temp tables. JSON serialization exception while ", e);
            }
            relationalStoreExecutionState.setIgnoreFreeMarkerProcessing(false);
        }
    }

    private static void ingestDataIntoTempTable(ExecutionNode executionNode, String result, ExecutionState executionState, Identity identity) throws IOException
    {
        if (executionNode instanceof SequenceExecutionNode)
        {
            SequenceExecutionNode sequenceExecutionNode = (SequenceExecutionNode) executionNode;
            TokenBuffer tb = new TokenBuffer(OBJECT_MAPPER, false);
            OBJECT_MAPPER.writeValue(tb, sequenceExecutionNode);
            SequenceExecutionNode copyOfSequenceExecutionNode = OBJECT_MAPPER.readValue(tb.asParser(), SequenceExecutionNode.class);
            for (ExecutionNode node : copyOfSequenceExecutionNode.executionNodes)
            {
                if (node instanceof SQLExecutionNode)
                {
                    SQLExecutionNode sqlExecutionNode = ((SQLExecutionNode) node);
                    String sqlQuery = sqlExecutionNode.sqlQuery;
                    if (sqlQuery.contains("${temp_table_rows_from_result_set}"))
                    {
                        sqlExecutionNode.sqlQuery = sqlExecutionNode.sqlQuery.replace("${temp_table_rows_from_result_set}", result);
                    }
                }
            }
            copyOfSequenceExecutionNode.accept(new ExecutionNodeExecutor(identity, executionState));
        }
        else
        {
            throw new RuntimeException("Inserting values into a temp table should be done using SequenceExecutionNode found:" + executionNode.getClass());
        }
    }

    public static void prepareExecutionStateForTempTableExecution(String key, ExecutionState state, String result)
    {
        state.addResult(key, new ConstantResult(result));
    }

    private void addKeyRowToRealizedRelationalResult(Object obj, List<Method> keyGetters, RealizedRelationalResult realizedRelationalResult) throws InvocationTargetException, IllegalAccessException
    {
        int keyCount = keyGetters.size();
        List<Object> pkRowTransformed = FastList.newList(keyCount);
        List<Object> pkRowNormalized = FastList.newList(keyCount);

        for (Method keyGetter : keyGetters)
        {
            Object key = keyGetter.invoke(RelationalGraphFetchUtils.resolveValueIfIChecked(obj));
            pkRowTransformed.add(key);
            pkRowNormalized.add(key);
        }

        realizedRelationalResult.addRow(pkRowNormalized, pkRowTransformed);
    }

    private int addXStoreKeyRowToRealizedRelationalResult(Object obj, List<Method> keyGetters, RealizedRelationalResult realizedRelationalResult, boolean isMultiEqualXStoreExpression) throws InvocationTargetException, IllegalAccessException
    {
        int keyCount = keyGetters.size();
        List<Object> pkRowTransformed = FastList.newList(keyCount);
        List<Object> pkRowNormalized = FastList.newList(keyCount);

        boolean shouldAddRow = !isMultiEqualXStoreExpression;

        for (Method keyGetter : keyGetters)
        {
            Object key = keyGetter.invoke(RelationalGraphFetchUtils.resolveValueIfIChecked(obj));
            if (key != null)
            {
                shouldAddRow = true;
            }
            pkRowTransformed.add(key);
            pkRowNormalized.add(key);
        }
        if (shouldAddRow)
        {
            realizedRelationalResult.addRow(pkRowNormalized, pkRowTransformed);
        }
        return shouldAddRow ? 1 : 0;
    }

    private Class<?> getExecuteClass(ExecutionNode node)
    {
        if (!(node.implementation instanceof JavaPlatformImplementation))
        {
            throw new RuntimeException("Only Java implementations are currently supported, found: " + node.implementation);
        }
        JavaPlatformImplementation javaPlatformImpl = (JavaPlatformImplementation) node.implementation;
        String executeClassName = JavaHelper.getExecutionClassFullName(javaPlatformImpl);
        return ExecutionNodeJavaPlatformHelper.getClassToExecute(node, executeClassName, this.executionState, this.identity);
    }

    private Result getStreamingObjectResultFromRelationalResult(ExecutionNode node, RelationalResult relationalResult, DatabaseConnection databaseConnection) throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException, JsonProcessingException
    {
        Class<?> executeClass = this.getExecuteClass(node);
        if (Arrays.asList(executeClass.getInterfaces()).contains(IRelationalClassInstantiationNodeExecutor.class))
        {
            IRelationalClassInstantiationNodeExecutor executor = (IRelationalClassInstantiationNodeExecutor) executeClass.getConstructor().newInstance();

            final ResultSet resultSet = relationalResult.getResultSet();
            final String databaseTimeZone = relationalResult.getRelationalDatabaseTimeZone();
            final String databaseConnectionString = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(databaseConnection);

            Iterator<Object> objectIterator = new Iterator<Object>()
            {
                private boolean cursorMove;
                private boolean hasNext;

                @Override
                public boolean hasNext()
                {
                    if (!this.cursorMove)
                    {
                        try
                        {
                            this.hasNext = resultSet.next();
                        }
                        catch (SQLException e)
                        {
                            throw new RuntimeException(e);
                        }
                        this.cursorMove = true;
                    }
                    return this.hasNext;
                }

                @Override
                public Object next()
                {
                    if (this.hasNext())
                    {
                        cursorMove = false;
                        try
                        {
                            return executor.getObjectFromResultSet(resultSet, databaseTimeZone, databaseConnectionString);
                        }
                        catch (RuntimeException e)
                        {
                            throw e;
                        }
                        catch (Exception e)
                        {
                            throw new RuntimeException(e);
                        }
                    }

                    throw new NoSuchElementException("End of result set reached!");
                }
            };
            Stream<Object> objectStream = StreamSupport.stream(Spliterators.spliteratorUnknownSize(objectIterator, Spliterator.ORDERED), false);
            ClassBuilder classBuilder = new ClassBuilder(node);
            return new StreamingObjectResult<>(objectStream, classBuilder, relationalResult);
        }
        else
        {
            throw new RuntimeException("Unknown execute class " + executeClass.getCanonicalName());
        }
    }

    @Deprecated
    @Override
    public Result visit(GraphFetchM2MExecutionNode graphFetchM2MExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(StoreStreamReadingExecutionNode storeStreamReadingExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(InMemoryRootGraphFetchExecutionNode inMemoryRootGraphFetchExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(InMemoryCrossStoreGraphFetchExecutionNode inMemoryCrossStoreGraphFetchExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(InMemoryPropertyGraphFetchExecutionNode inMemoryPropertyGraphFetchExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(AggregationAwareExecutionNode aggregationAwareExecutionNode)
    {
        Result last = null;
        for (ExecutionNode n : aggregationAwareExecutionNode.executionNodes())
        {
            ExecutionState state = new ExecutionState(this.executionState);
            state.activities.add(new AggregationAwareActivity(aggregationAwareExecutionNode.aggregationAwareActivity));
            last = n.accept(new ExecutionNodeExecutor(this.identity, state));
        }
        return last;
    }

    @Deprecated
    @Override
    public Result visit(GraphFetchExecutionNode graphFetchExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(StoreMappingGlobalGraphFetchExecutionNode storeMappingGlobalGraphFetchExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(ErrorExecutionNode errorExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(MultiResultSequenceExecutionNode multiResultSequenceExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(SequenceExecutionNode sequenceExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(FunctionParametersValidationNode functionParametersValidationNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(AllocationExecutionNode allocationExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(PureExpressionPlatformExecutionNode pureExpressionPlatformExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(ConstantExecutionNode constantExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(LocalGraphFetchExecutionNode localGraphFetchExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    @Override
    public Result visit(FreeMarkerConditionalExecutionNode localGraphFetchExecutionNode)
    {
        throw new RuntimeException("Not implemented!");
    }

    private Connection getConnection(CreateAndPopulateTempTableExecutionNode createAndPopulateTempTableExecutionNode, RelationalDatabaseCommands databaseCommands, Identity identity, ExecutionState executionState)
    {
        if (((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).retainConnection())
        {
            BlockConnection blockConnection = ((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).getBlockConnectionContext().getBlockConnection(((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)), createAndPopulateTempTableExecutionNode.connection, identity);
            try
            {
                int isolationLevel  = ((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).getIsolationLevel() > 0 ? ((RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational)).getIsolationLevel() : Connection.TRANSACTION_NONE;
                blockConnection.setTransactionIsolation(isolationLevel);
                blockConnection.addRollbackQuery(databaseCommands.dropTempTable(createAndPopulateTempTableExecutionNode.tempTableName));
                blockConnection.addCommitQuery(databaseCommands.dropTempTable(createAndPopulateTempTableExecutionNode.tempTableName));
                return blockConnection;
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("CreateAndPopulateTempTableExecutionNode should be used within RelationalBlockExecutionNode");
    }

    @JsonIgnore
    private Result executeImplementation(RelationalExecutionNode relationalExecutionNode, Result result, ExecutionState executionState, Identity identity)
    {
        if (!(result instanceof RelationalResult))
        {
            throw new RuntimeException("Unexpected result: " + result.getClass().getName());
        }
        RelationalResult relationalResult = (RelationalResult) result;
        try
        {
            if (!(relationalExecutionNode.implementation instanceof JavaPlatformImplementation))
            {
                throw new RuntimeException("Only Java implementations are currently supported, found: " + relationalExecutionNode.implementation);
            }
            JavaPlatformImplementation javaPlatformImpl = (JavaPlatformImplementation) relationalExecutionNode.implementation;
            String executionClassName = JavaHelper.getExecutionClassFullName(javaPlatformImpl);
            String executionMethodName = JavaHelper.getExecutionMethodName(javaPlatformImpl);
            String databaseConnectionString = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(relationalExecutionNode.connection);
            List<Pair<List<Class<?>>, List<Object>>> parameterTypesAndParametersAlternatives = Arrays.asList(
                    Tuples.pair(Arrays.asList(RelationalResult.class, DatabaseConnection.class), Arrays.asList(result, relationalExecutionNode.connection)),
                    Tuples.pair(Arrays.asList(RelationalResult.class, String.class), Arrays.asList(result, databaseConnectionString)),
                    Tuples.pair(Arrays.asList(IRelationalResult.class, String.class), Arrays.asList(result, databaseConnectionString)));
            Stream<?> transformedResult = ExecutionNodeJavaPlatformHelper.executeStaticJavaMethod(relationalExecutionNode, executionClassName, executionMethodName, parameterTypesAndParametersAlternatives, executionState, identity);
            return new StreamingObjectResult<>(transformedResult, relationalResult.builder, relationalResult);
        }
        catch (Exception e)
        {
            try
            {
                return this.getStreamingObjectResultFromRelationalResult(relationalExecutionNode, relationalResult, relationalExecutionNode.connection);
            }
            catch (Exception other)
            {
                result.close();
                other.addSuppressed(e);
                throw (other instanceof RuntimeException) ? (RuntimeException) other : new RuntimeException(other);
            }
        }
    }

    private void executeLocalRelationalGraphOperation(RelationalGraphFetchExecutionNode node, DoubleStrategyHashMap<Object, Object, SQLExecutionResult> parentMap, List<Method> parentKeyGetters)
    {
        GraphExecutionState graphExecutionState = (GraphExecutionState) executionState;

        List<Object> childObjects = FastList.newList();
        graphExecutionState.setObjectsForNodeIndex(node.nodeIndex, childObjects);

        SQLExecutionResult childResult = null;
        try
        {
            /* Get Java executor */
            Class<?> executeClass = this.getExecuteClass(node);
            if (Arrays.asList(executeClass.getInterfaces()).contains(IRelationalChildGraphNodeExecutor.class))
            {
                IRelationalChildGraphNodeExecutor executor = (IRelationalChildGraphNodeExecutor) executeClass.getConstructor().newInstance();

                /* Execute relational node corresponding to the child */
                childResult = (SQLExecutionResult) node.relationalNode.accept(new ExecutionNodeExecutor(identity, executionState));

                boolean nonPrimitiveNode = node.resultType instanceof ClassResultType;
                boolean childrenExist = node.children != null && !node.children.isEmpty();

                /* Change the second strategy to suit the primary key indices of parent PKs in the child result set*/
                List<String> parentSQLKeyColumns = executor.parentSQLColumnsInResultSet(childResult.getResultColumns().stream().map(ResultColumn::getNonQuotedLabel).collect(Collectors.toList()));
                List<Integer> parentPrimaryKeyIndices = FastList.newList();
                for (String pkCol : parentSQLKeyColumns)
                {
                    parentPrimaryKeyIndices.add(childResult.getResultSet().findColumn(pkCol));
                }
                RelationalGraphFetchUtils.switchSecondKeyHashingStrategy(parentMap, parentKeyGetters, parentPrimaryKeyIndices);
                String databaseConnectionString = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(childResult.getSQLExecutionNode().connection);

                if (nonPrimitiveNode)
                {
                    List<Method> primaryKeyGetters = executor.primaryKeyGetters();
                    int primaryKeyCount = primaryKeyGetters.size();
                    DoubleStrategyHashMap<Object, Object, SQLExecutionResult> currentMap = new DoubleStrategyHashMap<>(
                            RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategyWithEmptySecondStrategy(primaryKeyGetters)
                    );

                    String tempTableName = childrenExist ? ((RelationalTempTableGraphFetchExecutionNode) node).tempTableName : null;
                    RealizedRelationalResult realizedRelationalResult = childrenExist ?
                            RealizedRelationalResult.emptyRealizedRelationalResult(((RelationalTempTableGraphFetchExecutionNode) node).columns) :
                            null;
                    DatabaseConnection databaseConnection = childResult.getSQLExecutionNode().connection;
                    String databaseType = childResult.getDatabaseType();
                    String databaseTimeZone = childResult.getDatabaseTimeZone();

                    ResultSet childResultSet = childResult.getResultSet();

                    try (Scope ignored1 = GlobalTracer.get().buildSpan("Graph Query Relational: Read Child Batch").startActive(true))
                    {
                        while (childResultSet.next())
                        {
                            graphExecutionState.incrementRowCount();
                            Object parent = parentMap.getWithSecondKey(childResult);
                            if (parent == null)
                            {
                                throw new RuntimeException("No parent");
                            }

                            org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance<?> childGraphInstance = executor.getObjectFromResultSet(childResultSet, childResult.getDatabaseTimeZone(), databaseConnectionString);
                            Object child = childGraphInstance.getValue();
                            Object mapObject = currentMap.putIfAbsent(child, child);
                            if (mapObject == null)
                            {
                                mapObject = child;
                                graphExecutionState.addObjectMemoryUtilization(childGraphInstance.instanceSize());
                                childObjects.add(mapObject);
                                if (childrenExist)
                                {
                                    this.addKeyRowToRealizedRelationalResult(child, primaryKeyGetters, realizedRelationalResult);
                                }
                            }

                            executor.addChildToParent(parent, mapObject, DefaultExecutionNodeContext.factory().create(graphExecutionState, null));
                        }
                    }

                    childResult.close();
                    childResult = null;

                    if (childrenExist)
                    {
                        this.executeRelationalChildren(node, tempTableName, realizedRelationalResult, databaseConnection, databaseType, databaseTimeZone, currentMap, primaryKeyGetters);
                    }
                }
                else
                {
                    ResultSet childResultSet = childResult.getResultSet();
                    while (childResultSet.next())
                    {
                        Object parent = parentMap.getWithSecondKey(childResult);
                        if (parent == null)
                        {
                            throw new RuntimeException("No parent");
                        }

                        org.finos.legend.engine.plan.dependencies.domain.graphFetch.IGraphInstance<?> childGraphInstance = executor.getObjectFromResultSet(childResultSet, childResult.getDatabaseTimeZone(), databaseConnectionString);
                        Object child = childGraphInstance.getValue();
                        childObjects.add(child);
                        graphExecutionState.addObjectMemoryUtilization(childGraphInstance.instanceSize());

                        executor.addChildToParent(parent, child, DefaultExecutionNodeContext.factory().create(graphExecutionState, null));
                    }

                    childResult.close();
                    childResult = null;
                }
            }
            else
            {
                throw new RuntimeException("Unknown execute class " + executeClass.getCanonicalName());
            }
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (childResult != null)
            {
                childResult.close();
            }
        }
    }

    private Result executeRelationalRootQueryTempTableGraphFetchExecutionNode(RelationalRootQueryTempTableGraphFetchExecutionNode node)
    {
        boolean isLeaf = node.children == null || node.children.isEmpty();
        Result rootResult = null;

        Span graphFetchSpan = GlobalTracer.get().buildSpan("graph fetch").withTag("rootStoreType", "relational").start();
        GlobalTracer.get().activateSpan(graphFetchSpan);
        try
        {
            rootResult = node.executionNodes.get(0).accept(new ExecutionNodeExecutor(this.identity, this.executionState));
            SQLExecutionResult sqlExecutionResult = (SQLExecutionResult) rootResult;
            DatabaseConnection databaseConnection = sqlExecutionResult.getSQLExecutionNode().connection;
            ResultSet rootResultSet = ((SQLExecutionResult) rootResult).getResultSet();

            IRelationalRootQueryTempTableGraphFetchExecutionNodeSpecifics nodeSpecifics = ExecutionNodeJavaPlatformHelper.getNodeSpecificsInstance(node, this.executionState, this.identity);

            List<Method> primaryKeyGetters = nodeSpecifics.primaryKeyGetters();

            /* Check if caching is enabled and fetch caches if required */
            List<Pair<String, String>> allInstanceSetImplementations = nodeSpecifics.allInstanceSetImplementations();
            int setIdCount = allInstanceSetImplementations.size();
            RelationalMultiSetExecutionCacheWrapper multiSetCache = new RelationalMultiSetExecutionCacheWrapper(setIdCount);
            boolean cachingEnabledForNode = this.checkForCachingAndPopulateCachingHelpers(allInstanceSetImplementations, nodeSpecifics.supportsCaching(), node.graphFetchTree, sqlExecutionResult, nodeSpecifics::primaryKeyColumns, multiSetCache);

            /* Prepare for reading */
            nodeSpecifics.prepare(rootResultSet, sqlExecutionResult.getDatabaseTimeZone(), ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(databaseConnection));

            boolean isUnion = setIdCount > 1;
            AtomicLong batchIndex = new AtomicLong(0L);
            Spliterator<GraphObjectsBatch> graphObjectsBatchSpliterator = new Spliterators.AbstractSpliterator<GraphObjectsBatch>(Long.MAX_VALUE, Spliterator.ORDERED)
            {
                @Override
                public boolean tryAdvance(Consumer<? super GraphObjectsBatch> action)
                {
                    long batchSize;
                    boolean useAdaptiveBatching = executionState.getGraphFetchExecutionConfiguration().shouldUseAdaptiveBatching() && (node.batchSize == null);
                    batchSize = useAdaptiveBatching ? AdaptiveBatching.getAdaptiveBatchSize(executionState) : (node.batchSize == null ? executionState.getGraphFetchExecutionConfiguration().getGraphFetchDefaultBatchSize() : node.batchSize);

                    /* Ensure all children run in the same connection */
                    RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) executionState.getStoreExecutionState(StoreType.Relational);
                    BlockConnectionContext oldBlockConnectionContext = relationalStoreExecutionState.getBlockConnectionContext();
                    boolean oldRetainConnectionFlag = relationalStoreExecutionState.retainConnection();
                    relationalStoreExecutionState.setBlockConnectionContext(new BlockConnectionContext());
                    relationalStoreExecutionState.setRetainConnection(true);

                    long currentBatch = batchIndex.incrementAndGet();
                    try (Scope ignored = GlobalTracer.get().buildSpan("graph fetch batch").withTag("storeType", "relational").withTag("batchIndex", currentBatch).withTag("batchSizeConfig", batchSize).withTag("class", ((RootGraphFetchTree) node.graphFetchTree)._class).asChildOf(graphFetchSpan).startActive(true))
                    {
                        RelationalGraphObjectsBatch relationalGraphObjectsBatch;
                        if (useAdaptiveBatching)
                        {
                            relationalGraphObjectsBatch = new RelationalGraphObjectsBatch(currentBatch, executionState.getGraphFetchExecutionConfiguration().getGraphFetchBatchMemoryHardLimit());
                        }
                        else
                        {
                            relationalGraphObjectsBatch = new RelationalGraphObjectsBatch(currentBatch, executionState.getGraphFetchExecutionConfiguration().getGraphFetchBatchMemorySoftLimit());
                        }

                        List<Object> resultObjects = new ArrayList<>();
                        List<Pair<IGraphInstance<? extends IReferencedObject>, ExecutionCache<GraphFetchCacheKey, Object>>> instancesToDeepFetchAndCache = new ArrayList<>();
                        // stores a list of concrete objects you want to fetch. cache in case multiple parents map to the same child.
                        int objectCount = 0;
                        while ((!rootResultSet.isClosed()) && rootResultSet.next())
                        {
                            relationalGraphObjectsBatch.incrementRowCount();

                            int setIndex = isUnion ? rootResultSet.getInt(1) : 0;
                            Object cachedObject = checkAndReturnCachedObject(cachingEnabledForNode, setIndex, multiSetCache);
                            boolean shouldDeepFetchOnThisInstance = cachedObject == null;
                            Object object;
                            if (shouldDeepFetchOnThisInstance)
                            {
                                IGraphInstance<? extends IReferencedObject> wrappedObject = nodeSpecifics.nextGraphInstance(); // nodeSpecifics holds reference to the resultSet and reads the object
                                instancesToDeepFetchAndCache.add(Tuples.pair(wrappedObject, multiSetCache.setCaches.get(setIndex)));
                                object = wrappedObject.getValue();
                            }
                            else
                            {
                                object = cachedObject;
                            }
                            if (node.checked != null && node.checked)
                            {
                                resultObjects.add(BasicChecked.newChecked(object, null));
                            }
                            else
                            {
                                resultObjects.add(object);
                            }

                            objectCount += 1;
                            if (objectCount >= batchSize)
                            {
                                break;
                            }
                        }

                        // for each node index, we are storing a list of java Objects. the objects are not fully populated yet and references to the parent need to be added.
                        relationalGraphObjectsBatch.setObjectsForNodeIndex(node.nodeIndex, resultObjects);

                        if (!instancesToDeepFetchAndCache.isEmpty())
                        {
                            RealizedRelationalResult realizedRelationalResult = RealizedRelationalResult.emptyRealizedRelationalResult(node.columns);
                            DoubleStrategyHashMap<Object, Object, SQLExecutionResult> rootMap = new DoubleStrategyHashMap<>(RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategyWithEmptySecondStrategy(primaryKeyGetters));
                            for (Pair<IGraphInstance<? extends IReferencedObject>, ExecutionCache<GraphFetchCacheKey, Object>> instanceAndCache : instancesToDeepFetchAndCache)
                            {
                                IGraphInstance<? extends IReferencedObject> rootGraphInstance = instanceAndCache.getOne();
                                Object rootObject = rootGraphInstance.getValue();
                                rootMap.put(rootObject, rootObject);
                                relationalGraphObjectsBatch.addObjectMemoryUtilization(rootGraphInstance.instanceSize());
                                if (!isLeaf) // objects which aren't fetched completely and need to be added in a temp table
                                {
                                    RelationalExecutionNodeExecutor.this.addKeyRowToRealizedRelationalResult(rootObject, primaryKeyGetters, realizedRelationalResult);
                                }
                            }

                            /* Execute store local children */
                            if (!isLeaf)
                            {
                                executionState.graphObjectsBatch = relationalGraphObjectsBatch;
                                RelationalExecutionNodeExecutor.this.executeRootTempTableNodeChildren(node, realizedRelationalResult, databaseConnection, sqlExecutionResult.getDatabaseType(), sqlExecutionResult.getDatabaseTimeZone(), rootMap, primaryKeyGetters);
                            }
                        }

                        instancesToDeepFetchAndCache.stream().filter(x -> x.getTwo() != null).forEach(x ->
                        {
                            Object object = x.getOne().getValue();
                            x.getTwo().put(new RelationalGraphFetchUtils.RelationalObjectGraphFetchCacheKey(object, primaryKeyGetters), object);
                        });

                        action.accept(relationalGraphObjectsBatch);

                        return !resultObjects.isEmpty();
                    }
                    catch (SQLException | InvocationTargetException | IllegalAccessException e)
                    {
                        throw new RuntimeException(e);
                    }
                    finally
                    {
                        relationalStoreExecutionState.getBlockConnectionContext().unlockAllBlockConnections();
                        relationalStoreExecutionState.getBlockConnectionContext().closeAllBlockConnectionsAsync();
                        relationalStoreExecutionState.setBlockConnectionContext(oldBlockConnectionContext);
                        relationalStoreExecutionState.setRetainConnection(oldRetainConnectionFlag);
                    }
                }
            };

            Stream<GraphObjectsBatch> graphObjectsBatchStream = StreamSupport.stream(graphObjectsBatchSpliterator, false);
            return new GraphFetchResult(graphObjectsBatchStream, rootResult).withGraphFetchSpan(graphFetchSpan);
        }
        catch (RuntimeException e)
        {
            if (rootResult != null)
            {
                rootResult.close();
            }
            if (graphFetchSpan != null)
            {
                graphFetchSpan.finish();
            }
            throw e;
        }
        catch (Exception e)
        {
            if (rootResult != null)
            {
                rootResult.close();
            }
            if (graphFetchSpan != null)
            {
                graphFetchSpan.finish();
            }
            throw new RuntimeException(e);
        }
    }

    private DelayedGraphFetchResult executeRelationalPrimitiveQueryGraphFetchExecutionNode(RelationalPrimitiveQueryGraphFetchExecutionNode node)
    {
        IRelationalPrimitiveQueryGraphFetchExecutionNodeSpecifics nodeSpecifics;
        Result childResult = null;
        List<Object> childObjects = new ArrayList<>();
        List<Object> parentObjects = new ArrayList<>();

        String property = ((PropertyGraphFetchTree) node.graphFetchTree).property;
        try (Scope ignored = GlobalTracer.get().buildSpan("local property graph fetch (" + property + ")").withTag("storeType", "relational").withTag("property", property).startActive(true))
        {
            childResult = node.executionNodes.get(0).accept(new ExecutionNodeExecutor(this.identity, this.executionState));

            RelationalGraphObjectsBatch relationalGraphObjectsBatch = (RelationalGraphObjectsBatch) this.executionState.graphObjectsBatch;

            SQLExecutionResult childSqlResult = (SQLExecutionResult) childResult;
            ResultSet childResultSet = childSqlResult.getResultSet();

            nodeSpecifics = ExecutionNodeJavaPlatformHelper.getNodeSpecificsInstance(node, this.executionState, this.identity); // pass state to determine which java compiler to use. okay to use thread state.
            DatabaseConnection databaseConnection = childSqlResult.getSQLExecutionNode().connection;
            DoubleStrategyHashMap<Object, Object, SQLExecutionResult> parentMap = switchedParentHashMapPerChildResult(
                    relationalGraphObjectsBatch, node.parentIndex, childResultSet,
                    () -> nodeSpecifics.parentPrimaryKeyColumns(childSqlResult.getResultColumns().stream().map(ResultColumn::getNonQuotedLabel).collect(Collectors.toList())),
                    databaseConnection
            );

            /* Prepare for reading */
            nodeSpecifics.prepare(childResultSet, childSqlResult.getDatabaseTimeZone(), ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(childSqlResult.getSQLExecutionNode().connection));

            while (childResultSet.next())
            {
                Object parent = parentMap.getWithSecondKey(childSqlResult);
                if (parent == null)
                {
                    throw new RuntimeException("Cannot find the parent for child");
                }

                IGraphInstance<?> childGraphInstance = nodeSpecifics.nextGraphInstance();
                Object child = childGraphInstance.getValue();
                childObjects.add(child);
                parentObjects.add(parent);

                relationalGraphObjectsBatch.incrementRowCount();
                relationalGraphObjectsBatch.addObjectMemoryUtilization(childGraphInstance.instanceSize());
            }

            childResult.close();
            childResult = null;

            relationalGraphObjectsBatch.setObjectsForNodeIndex(node.nodeIndex, childObjects);

            Runnable parentAdder = () ->
            {
                for (int i = 0; i < childObjects.size(); i++)
                {
                    Object child = childObjects.get(i);
                    Object parent = parentObjects.get(i);
                    nodeSpecifics.addChildToParent(parent, child, DefaultExecutionNodeContext.factory().create(this.executionState, null));
                }
                return;
            };

            Callable<List<DelayedGraphFetchResultWithExecInfo>> executeTempTableChild = () ->
            {
                return new ArrayList<>();
            };

            return new DelayedGraphFetchResult(parentAdder, executeTempTableChild);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (childResult != null)
            {
                childResult.close();
            }
        }
    }

    private DelayedGraphFetchResult executeRelationalClassQueryTempTableGraphFetchExecutionNode(RelationalClassQueryTempTableGraphFetchExecutionNode node)
    {
        Result childResult = null;

        String property = ((PropertyGraphFetchTree) node.graphFetchTree).property;
        try (Scope ignored = GlobalTracer.get().buildSpan("local property graph fetch (" + property + ")").withTag("storeType", "relational").withTag("property", property).startActive(true))
        {
            List<Object> parentObjects = new ArrayList<>();
            List<Object> childObjects = new ArrayList<>();
            IRelationalClassQueryTempTableGraphFetchExecutionNodeSpecifics nodeSpecifics;

            RealizedRelationalResult realizedRelationalResult;
            DatabaseConnection databaseConnection;
            String databaseType;
            String databaseTimeZone;

            boolean isLeaf = node.children == null || node.children.isEmpty();
            List<Pair<IGraphInstance<? extends IReferencedObject>, ExecutionCache<GraphFetchCacheKey, Object>>> childInstancesToDeepFetchAndCache = new ArrayList<>();

            childResult = node.executionNodes.get(0).accept(new ExecutionNodeExecutor(this.identity, this.executionState)); // relational execution node to fetch properties for the level

            SQLExecutionResult childSqlResult = (SQLExecutionResult) childResult;
            databaseConnection = childSqlResult.getSQLExecutionNode().connection;
            databaseType = childSqlResult.getDatabaseType();
            databaseTimeZone = childSqlResult.getDatabaseTimeZone();
            ResultSet childResultSet = childSqlResult.getResultSet();

            nodeSpecifics = ExecutionNodeJavaPlatformHelper.getNodeSpecificsInstance(node, this.executionState, this.identity); // nodeSpecifics deal with how to create and manage the java impls.

            List<Pair<String, String>> allInstanceSetImplementations = nodeSpecifics.allInstanceSetImplementations();
            int setIdCount = allInstanceSetImplementations.size();
            boolean isUnion = setIdCount > 1;
            RelationalMultiSetExecutionCacheWrapper multiSetCache = new RelationalMultiSetExecutionCacheWrapper(setIdCount);
            boolean cachingEnabledForNode = this.checkForCachingAndPopulateCachingHelpers(allInstanceSetImplementations, nodeSpecifics.supportsCaching(), node.graphFetchTree, childSqlResult, nodeSpecifics::primaryKeyColumns, multiSetCache);

            RelationalGraphObjectsBatch relationalGraphObjectsBatch = (RelationalGraphObjectsBatch) this.executionState.graphObjectsBatch;
            DoubleStrategyHashMap<Object, Object, SQLExecutionResult> parentMap = switchedParentHashMapPerChildResult(
                    relationalGraphObjectsBatch, node.parentIndex, childResultSet,
                    () -> nodeSpecifics.parentPrimaryKeyColumns(childSqlResult.getResultColumns().stream().map(ResultColumn::getNonQuotedLabel).collect(Collectors.toList())),
                    databaseConnection
            ); // child to parent map.

            List<Method> primaryKeyGetters = nodeSpecifics.primaryKeyGetters();
            DoubleStrategyHashMap<Object, Object, SQLExecutionResult> currentMap = new DoubleStrategyHashMap<>(RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategyWithEmptySecondStrategy(primaryKeyGetters)); // for the children
            realizedRelationalResult = RealizedRelationalResult.emptyRealizedRelationalResult(node.columns);

            /* Prepare for reading */
            nodeSpecifics.prepare(childResultSet, childSqlResult.getDatabaseTimeZone(), ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(databaseConnection));

            while (childResultSet.next())
            {
                Object child;
                int setIndex = isUnion ? childResultSet.getInt(1) : 0;
                Object cachedObject = checkAndReturnCachedObject(cachingEnabledForNode, setIndex, multiSetCache);
                boolean shouldDeepFetchOnThisInstance = cachedObject == null;

                if (shouldDeepFetchOnThisInstance)
                {
                    IGraphInstance<? extends IReferencedObject> wrappedObject = nodeSpecifics.nextGraphInstance();
                    Object wrappedValue = wrappedObject.getValue();

                    Object mapObject = currentMap.putIfAbsent(wrappedValue, wrappedValue);

                    if (mapObject == null) // didn't exist in currentMap yet, so add it to the cache and current realized result.
                    {
                        child = wrappedValue;
                        childInstancesToDeepFetchAndCache.add(Tuples.pair(wrappedObject, multiSetCache.setCaches.get(setIndex)));
                        if (!isLeaf)
                        {
                            this.addKeyRowToRealizedRelationalResult(child, primaryKeyGetters, realizedRelationalResult);
                        }

                        relationalGraphObjectsBatch.incrementRowCount();
                        relationalGraphObjectsBatch.addObjectMemoryUtilization(wrappedObject.instanceSize());
                    }
                    else
                    {
                        child = mapObject;
                    }
                }
                else
                {
                    child = cachedObject;
                }
                childObjects.add(child);

                Object parent = parentMap.getWithSecondKey(childSqlResult);
                if (parent == null)
                {
                    throw new RuntimeException("Cannot find the parent for child");
                }
                parentObjects.add(parent);
            }
            relationalGraphObjectsBatch.setObjectsForNodeIndex(node.nodeIndex, childObjects);

            childResult.close();
            childResult = null;

            childInstancesToDeepFetchAndCache.stream().filter(x -> x.getTwo() != null).forEach(x ->
            {
                Object object = x.getOne().getValue();
                x.getTwo().put(new RelationalGraphFetchUtils.RelationalObjectGraphFetchCacheKey(object, primaryKeyGetters), object);
            });

            Runnable parentAdder = () ->
            {
                for (int i = 0; i < childObjects.size(); i++)
                {
                    Object child = childObjects.get(i);
                    Object parent = parentObjects.get(i);
                    nodeSpecifics.addChildToParent(parent, child, DefaultExecutionNodeContext.factory().create(this.executionState, null));
                }
            };

            Callable<List<DelayedGraphFetchResultWithExecInfo>> executeTempTableChild = () ->
            {
                if (isLeaf)
                {
                    return new ArrayList<>();
                }
                if (realizedRelationalResult.resultSetRows.isEmpty())
                {
                    node.children.forEach(x -> this.recursivelyPopulateEmptyResultsInGraphObjectsBatch(x, relationalGraphObjectsBatch));
                    return new ArrayList<>();
                }
                relationalGraphObjectsBatch.setNodeObjectsHashMap(node.nodeIndex, currentMap);
                relationalGraphObjectsBatch.setNodePrimaryKeyGetters(node.nodeIndex, primaryKeyGetters);

                return submitTasksToExecutorIfPossible(node, realizedRelationalResult, databaseConnection, databaseType, databaseTimeZone, relationalGraphObjectsBatch);
            };

            return new DelayedGraphFetchResult(parentAdder, executeTempTableChild);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (childResult != null)
            {
                childResult.close();
            }
        }
    }

    private SQLExecutionNode getFirstSQLExecutionNode(ExecutionNode node)
    {
        if (node instanceof SQLExecutionNode)
        {
            return (SQLExecutionNode) node;
        }
        for (ExecutionNode child : node.childNodes())
        {
            SQLExecutionNode result = getFirstSQLExecutionNode(child);
            if (result != null)
            {
                return result;
            }
        }
        return null;
    }

    private List<DelayedGraphFetchResultWithExecInfo> submitTasksToExecutorIfPossible(RelationalTempTableGraphFetchExecutionNode node, RealizedRelationalResult realizedRelationalResult, DatabaseConnection databaseConnection, String databaseType, String databaseTimeZone, RelationalGraphObjectsBatch relationalGraphObjectsBatch)
    {
        List<DelayedGraphFetchResultWithExecInfo> submittedTasks = FastList.newList();
        ParallelGraphFetchExecutionExecutorPool graphFetchExecutionNodeExecutorPool = this.executionState.getGraphFetchExecutionNodeExecutorPool();
        RelationalExecutor relationalExecutor = ((RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational))
                .getRelationalExecutor();
        String dbConnectionKey = relationalExecutor.getConnectionManager().generateKeyFromDatabaseConnection(databaseConnection).shortId();
        String dbConnectionKeyWithIdentity = dbConnectionKey + "_" + identity.getName();

        RelationalGraphFetchExecutor relationalGraphFetchExecutor = ((RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational))
                .getRelationalGraphFetchExecutor();

        boolean parallelizationEnabled = this.executionState.getGraphFetchExecutionConfiguration().canExecuteInParallel() && relationalGraphFetchExecutor.canExecuteInParallel();
        boolean tempTableCreatedInParentConnection = false;
        try
        {
            Span currentActiveSpan = GlobalTracer.get().activeSpan();

            for (ExecutionNode child : node.children)
            {
                if (parallelizationEnabled && relationalGraphFetchExecutor.acquireThreads(graphFetchExecutionNodeExecutorPool, dbConnectionKeyWithIdentity, 1, databaseType))
                {
                    Callable<DelayedGraphFetchResult> task = () -> executeTempTableChildInNewConnection(node, child, realizedRelationalResult, databaseConnection, databaseType, databaseTimeZone, relationalGraphObjectsBatch, currentActiveSpan);
                    submittedTasks.add(new DelayedGraphFetchResultWithExecInfo(graphFetchExecutionNodeExecutorPool.submit(task), true, dbConnectionKeyWithIdentity));
                }
                else
                {
                    if (!tempTableCreatedInParentConnection)
                    {
                        createTempTableForChild(node, realizedRelationalResult, databaseConnection, databaseType, databaseTimeZone, this.executionState, this.identity);
                        tempTableCreatedInParentConnection = true;
                    }
                    DelayedGraphFetchResult res = (DelayedGraphFetchResult) child.accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                    submittedTasks.add(new DelayedGraphFetchResultWithExecInfo(CompletableFuture.completedFuture(res), false, dbConnectionKeyWithIdentity));
                }
            }
            return submittedTasks;
        }
        catch (Exception e)
        {
            // orchestrator is unaware of these tasks.
            if (parallelizationEnabled && graphFetchExecutionNodeExecutorPool != null)
            {
                submittedTasks.forEach(task -> task.cancel(relationalGraphFetchExecutor, graphFetchExecutionNodeExecutorPool));
            }
            throw new RuntimeException(e);
        }
        finally
        {
            if (tempTableCreatedInParentConnection)
            {
                RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational);
                relationalStoreExecutionState.getBlockConnectionContext().unlockAllBlockConnections();
                relationalStoreExecutionState.getBlockConnectionContext().closeAllBlockConnectionsAsync();
                relationalStoreExecutionState.setBlockConnectionContext(new BlockConnectionContext());
            }
        }

    }

    private Result executeRelationalCrossRootQueryTempTableGraphFetchExecutionNode(RelationalCrossRootQueryTempTableGraphFetchExecutionNode node)
    {
        boolean isLeaf = node.children == null || node.children.isEmpty();
        List<Object> childObjects = new ArrayList<>();
        Result childResult = null;

        RelationalStoreExecutionState relationalStoreExecutionState = (RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational);
        BlockConnectionContext oldBlockConnectionContext = relationalStoreExecutionState.getBlockConnectionContext();
        boolean oldRetainConnectionFlag = relationalStoreExecutionState.retainConnection();
        relationalStoreExecutionState.setBlockConnectionContext(new BlockConnectionContext());
        relationalStoreExecutionState.setRetainConnection(true);

        String property = ((PropertyGraphFetchTree) node.graphFetchTree).property;
        try (Scope ignored = GlobalTracer.get().buildSpan("cross property graph fetch (" + property + ")").withTag("storeType", "relational").withTag("property", property).startActive(true))
        {
            IRelationalCrossRootQueryTempTableGraphFetchExecutionNodeSpecifics nodeSpecifics = ExecutionNodeJavaPlatformHelper.getNodeSpecificsInstance(node, this.executionState, this.identity);

            RelationalGraphObjectsBatch relationalGraphObjectsBatch = new RelationalGraphObjectsBatch(this.executionState.graphObjectsBatch);
            List<?> parentObjects = relationalGraphObjectsBatch.getObjectsForNodeIndex(node.parentIndex);

            if ((parentObjects != null) && !parentObjects.isEmpty())
            {
                GraphFetchTree nodeSubTree = node.graphFetchTree;

                boolean cachingEnabled = false;
                ExecutionCache<GraphFetchCacheKey, List<Object>> crossCache = relationalGraphObjectsBatch.getXStorePropertyCacheForNodeIndex(node.nodeIndex);
                List<Method> parentCrossKeyGettersOrderedPerTargetProperties = null;
                if (crossCache != null)
                {
                    cachingEnabled = true;
                    parentCrossKeyGettersOrderedPerTargetProperties = nodeSpecifics.parentCrossKeyGettersOrderedByTargetProperties();
                }

                List<Object> parentsToDeepFetch = new ArrayList<>();
                for (Object parent : parentObjects)
                {
                    if (cachingEnabled)
                    {
                        List<Object> children = crossCache.getIfPresent(new RelationalGraphFetchUtils.RelationalCrossObjectGraphFetchCacheKey(parent, parentCrossKeyGettersOrderedPerTargetProperties));
                        if (children == null)
                        {
                            parentsToDeepFetch.add(parent);
                        }
                        else
                        {
                            for (Object child : children)
                            {
                                childObjects.add(child);
                                nodeSpecifics.addChildToParent(parent, child, DefaultExecutionNodeContext.factory().create(this.executionState, null));
                            }
                        }
                    }
                    else
                    {
                        parentsToDeepFetch.add(parent);
                    }
                }

                if (!parentsToDeepFetch.isEmpty())
                {
                    Map<Object, List<Object>> parentToChildMap = new HashMap<>();

                    RealizedRelationalResult parentRealizedRelationalResult = RealizedRelationalResult.emptyRealizedRelationalResult(node.parentTempTableColumns);
                    List<Method> crossKeyGetters = nodeSpecifics.parentCrossKeyGetters();

                    long rowCount = 0;
                    for (Object parentObject : parentsToDeepFetch)
                    {
                        rowCount += this.addXStoreKeyRowToRealizedRelationalResult(parentObject, crossKeyGetters, parentRealizedRelationalResult, nodeSpecifics.supportsCrossCaching());
                        parentToChildMap.put(parentObject, new ArrayList<>());
                    }
                    if (rowCount == 0)
                    {
                        if (cachingEnabled)
                        {
                            List<Method> getters = parentCrossKeyGettersOrderedPerTargetProperties;
                            parentToChildMap.forEach((p, cs) ->
                            {
                                crossCache.put(
                                        new RelationalGraphFetchUtils.RelationalCrossObjectGraphFetchCacheKey(p, getters),
                                        cs
                                );
                            });
                        }
                        return new ConstantResult(childObjects);
                    }

                    if (node.parentTempTableStrategy != null)
                    {
                        SQLExecutionNode firstSQLExecutionNode = getFirstSQLExecutionNode(node);
                        try (Scope ignored1 = GlobalTracer.get().buildSpan("create temp table").withTag("parent tempTableName", node.parentTempTableName).withTag("databaseType", firstSQLExecutionNode.getDatabaseTypeName()).startActive(true))
                        {
                            String databaseTimeZone = firstSQLExecutionNode.getDatabaseTimeZone();
                            if (node.parentTempTableStrategy instanceof LoadFromResultSetAsValueTuplesTempTableStrategy)
                            {
                                try
                                {
                                    node.parentTempTableStrategy.dropTempTableNode.accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                                }
                                catch (Exception ignored2)
                                {
                                }
                                node.parentTempTableStrategy.createTempTableNode.accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                                loadValuesIntoTempTablesFromRelationalResult(node.parentTempTableStrategy.loadTempTableNode, parentRealizedRelationalResult, ((LoadFromResultSetAsValueTuplesTempTableStrategy) node.parentTempTableStrategy).tupleBatchSize, ((LoadFromResultSetAsValueTuplesTempTableStrategy) node.parentTempTableStrategy).quoteCharacterReplacement, databaseTimeZone, this.executionState, this.identity);
                            }
                            else if (node.parentTempTableStrategy instanceof LoadFromTempFileTempTableStrategy)
                            {
                                try
                                {
                                    node.parentTempTableStrategy.dropTempTableNode.accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                                }
                                catch (Exception ignored2)
                                {
                                }
                                node.parentTempTableStrategy.createTempTableNode.accept(new ExecutionNodeExecutor(this.identity, this.executionState));

                                String requestId = new RequestIdGenerator().generateId();
                                String fileName = node.parentTempTableName + requestId;
                                try (TemporaryFile tempFile = new TemporaryFile(((RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational)).getRelationalExecutor().getRelationalExecutionConfiguration().tempPath, fileName))
                                {
                                    CsvSerializer csvSerializer = new RealizedRelationalResultCSVSerializer(parentRealizedRelationalResult, databaseTimeZone, true, false);
                                    tempFile.writeFile(csvSerializer);
                                    prepareExecutionStateForTempTableExecution("csv_file_location", this.executionState, tempFile.getTemporaryPathForFile());
                                    node.parentTempTableStrategy.loadTempTableNode.accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                                }
                                catch (Exception e)
                                {
                                    throw new RuntimeException("Could not create Temp table", e);
                                }
                            }
                            else
                            {
                                throw new RuntimeException("Unsupported temp table strategy for parent temp table");
                            }
                            this.executionState.addResult(node.parentTempTableName, new PreparedTempTableResult(node.processedParentTempTableName));
                        }
                    }
                    else
                    {
                        this.executionState.addResult(node.parentTempTableName, parentRealizedRelationalResult);
                    }

                    childResult = node.executionNodes.get(0).accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                    SQLExecutionResult childSqlResult = (SQLExecutionResult) childResult;
                    DatabaseConnection databaseConnection = childSqlResult.getSQLExecutionNode().connection;
                    ResultSet childResultSet = childSqlResult.getResultSet();

                    List<String> parentSQLKeyColumns = nodeSpecifics.parentCrossKeyColumns(childSqlResult.getResultColumns().stream().map(ResultColumn::getNonQuotedLabel).collect(Collectors.toList()));
                    List<Integer> parentCrossKeyIndices = parentSQLKeyColumns.stream().map(FunctionHelper.unchecked(childResultSet::findColumn)).collect(Collectors.toList());

                    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
                    DoubleStrategyHashMap<Object, List<Object>, SQLExecutionResult> parentMap = new DoubleStrategyHashMap<>(
                            RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategy(crossKeyGetters, parentCrossKeyIndices)
                    );
                    parentsToDeepFetch.forEach((o) -> parentMap.getIfAbsentPut(o, ArrayList::new).add(o));

                    RealizedRelationalResult realizedRelationalResult = RealizedRelationalResult.emptyRealizedRelationalResult(node.columns);

                    List<Method> primaryKeyGetters = nodeSpecifics.primaryKeyGetters();
                    DoubleStrategyHashMap<Object, Object, SQLExecutionResult> currentMap = new DoubleStrategyHashMap<>(
                            RelationalGraphFetchUtils.objectSQLResultDoubleHashStrategyWithEmptySecondStrategy(primaryKeyGetters)
                    );

                    /* Prepare for reading */
                    nodeSpecifics.prepare(childResultSet, childSqlResult.getDatabaseTimeZone(), ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports().writeValueAsString(databaseConnection));

                    while (childResultSet.next())
                    {
                        relationalGraphObjectsBatch.incrementRowCount();

                        List<Object> parents = parentMap.getWithSecondKey(childSqlResult);
                        if (parents == null)
                        {
                            throw new RuntimeException("Cannot find the parent for child");
                        }

                        IGraphInstance<? extends IReferencedObject> childGraphInstance = nodeSpecifics.nextGraphInstance();
                        Object child = childGraphInstance.getValue();
                        Object mapObject = currentMap.putIfAbsent(child, child);
                        if (mapObject == null)
                        {
                            mapObject = child;
                            childObjects.add(mapObject);
                            relationalGraphObjectsBatch.addObjectMemoryUtilization(childGraphInstance.instanceSize());
                            if (!isLeaf)
                            {
                                this.addKeyRowToRealizedRelationalResult(child, primaryKeyGetters, realizedRelationalResult);
                            }
                        }

                        for (Object parentObj : parents)
                        {
                            Object parent = RelationalGraphFetchUtils.resolveValueIfIChecked(parentObj);
                            if (!parentToChildMap.containsKey(parent))
                            {
                                parentToChildMap.put(parent, new ArrayList<>());
                            }
                            parentToChildMap.get(parent).add(mapObject);
                            nodeSpecifics.addChildToParent(parent, mapObject, DefaultExecutionNodeContext.factory().create(this.executionState, null));
                        }
                    }

                    relationalGraphObjectsBatch.setObjectsForNodeIndex(node.nodeIndex, childObjects);

                    childResult.close();
                    childResult = null;

                    if (node.parentTempTableStrategy != null)
                    {
                        node.parentTempTableStrategy.dropTempTableNode.accept(new ExecutionNodeExecutor(this.identity, this.executionState));
                    }

                    /* Execute store local children */
                    if (!isLeaf)
                    {
                        executionState.graphObjectsBatch = relationalGraphObjectsBatch;
                        this.executeRootTempTableNodeChildren(node, realizedRelationalResult, databaseConnection, childSqlResult.getDatabaseType(), childSqlResult.getDatabaseTimeZone(), currentMap, primaryKeyGetters);
                    }

                    if (cachingEnabled)
                    {
                        List<Method> getters = parentCrossKeyGettersOrderedPerTargetProperties;
                        parentToChildMap.forEach((p, cs) ->
                        {
                            crossCache.put(
                                    new RelationalGraphFetchUtils.RelationalCrossObjectGraphFetchCacheKey(p, getters),
                                    cs
                            );
                        });
                    }
                }
            }

            return new ConstantResult(childObjects);
        }
        catch (RuntimeException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (childResult != null)
            {
                childResult.close();
            }

            relationalStoreExecutionState.getBlockConnectionContext().unlockAllBlockConnections();
            relationalStoreExecutionState.getBlockConnectionContext().closeAllBlockConnectionsAsync();
            relationalStoreExecutionState.setBlockConnectionContext(oldBlockConnectionContext);
            relationalStoreExecutionState.setRetainConnection(oldRetainConnectionFlag);
        }
    }

    private void orchestrate(Queue<DelayedGraphFetchResultWithExecInfo> jobs)
    {
        ParallelGraphFetchExecutionExecutorPool graphFetchExecutionNodeExecutorPool = this.executionState.getGraphFetchExecutionNodeExecutorPool();
        RelationalGraphFetchExecutor relationalGraphFetchExecutor = ((RelationalStoreExecutionState) this.executionState.getStoreExecutionState(StoreType.Relational))
                .getRelationalGraphFetchExecutor();

        try
        {
            while (!jobs.isEmpty())
            {
                DelayedGraphFetchResultWithExecInfo currentResult = jobs.poll();
                List<DelayedGraphFetchResultWithExecInfo> childResults = currentResult.consume(relationalGraphFetchExecutor, graphFetchExecutionNodeExecutorPool);
                jobs.addAll(childResults);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            while (!jobs.isEmpty())
            {
                DelayedGraphFetchResultWithExecInfo currentResult = jobs.poll();
                currentResult.cancel(relationalGraphFetchExecutor, graphFetchExecutionNodeExecutorPool);
            }
        }
    }

    private void executeRootTempTableNodeChildren(RelationalTempTableGraphFetchExecutionNode node, RealizedRelationalResult realizedRelationalResult, DatabaseConnection databaseConnection, String databaseType, String databaseTimeZone, DoubleStrategyHashMap<Object, Object, SQLExecutionResult> nodeObjectsMap, List<Method> nodePrimaryKeyGetters)
    {
        RelationalGraphObjectsBatch relationalGraphObjectsBatch = (RelationalGraphObjectsBatch) this.executionState.graphObjectsBatch;

        if (realizedRelationalResult.resultSetRows.isEmpty())
        {
            node.children.forEach(x -> this.recursivelyPopulateEmptyResultsInGraphObjectsBatch(x, relationalGraphObjectsBatch));
        }
        else
        {
            relationalGraphObjectsBatch.setNodeObjectsHashMap(node.nodeIndex, nodeObjectsMap);
            relationalGraphObjectsBatch.setNodePrimaryKeyGetters(node.nodeIndex, nodePrimaryKeyGetters);

            Queue<DelayedGraphFetchResultWithExecInfo> submittedTasks = new LinkedList<>();
            submittedTasks.addAll(submitTasksToExecutorIfPossible(node, realizedRelationalResult, databaseConnection, databaseType, databaseTimeZone, relationalGraphObjectsBatch));
            orchestrate(submittedTasks);
        }
    }

    private DelayedGraphFetchResult executeTempTableChildInNewConnection(RelationalTempTableGraphFetchExecutionNode node, ExecutionNode child, RealizedRelationalResult realizedRelationalResult, DatabaseConnection databaseConnection, String databaseType, String databaseTimeZone, RelationalGraphObjectsBatch relationalGraphObjectsBatch, Span currentActiveSpan)
    {
        RelationalStoreExecutionState relationalStoreExecutionStateForThread = null;
        try (Scope scope = GlobalTracer.get().buildSpan(String.format("Child temp table execution - %d", ((RelationalGraphFetchExecutionNode) child).nodeIndex)).asChildOf(currentActiveSpan).startActive(true))
        {
            ExecutionState executionStateForThread = executionState.copy();
            executionStateForThread.setGraphObjectsBatch(relationalGraphObjectsBatch);      // one thread per node, stored in concurrentMap.
            executionStateForThread.setGraphFetchCaches(executionState.graphFetchCaches);   // updated in synchronized block

            relationalStoreExecutionStateForThread = (RelationalStoreExecutionState) executionStateForThread.getStoreExecutionState(StoreType.Relational);
            relationalStoreExecutionStateForThread.setBlockConnectionContext(new BlockConnectionContext());
            relationalStoreExecutionStateForThread.setRetainConnection(true);

            createTempTableForChild(node, realizedRelationalResult, databaseConnection, databaseType, databaseTimeZone, executionStateForThread, this.identity);

            return (DelayedGraphFetchResult) child.accept(new ExecutionNodeExecutor(this.identity, executionStateForThread));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            if (relationalStoreExecutionStateForThread != null)
            {
                relationalStoreExecutionStateForThread.getBlockConnectionContext().unlockAllBlockConnections();     // done with the current thread. give connection back to the pool
                relationalStoreExecutionStateForThread.getBlockConnectionContext().closeAllBlockConnectionsAsync();
                relationalStoreExecutionStateForThread.setBlockConnectionContext(new BlockConnectionContext());
            }
        }
    }

    private static void createTempTableForChild(RelationalTempTableGraphFetchExecutionNode node, RealizedRelationalResult realizedRelationalResult, DatabaseConnection databaseConnection, String databaseType, String databaseTimeZone, ExecutionState executionState, Identity identity)
    {
        String tempTableName;
        if (node.tempTableStrategy != null)
        {
            tempTableName = node.processedTempTableName;
            createTempTableFromRealizedRelationalResultUsingTempTableStrategyInBlockConnection(node, realizedRelationalResult, tempTableName, databaseConnection, databaseType, databaseTimeZone, executionState, identity);
        }
        else
        {
            tempTableName = DatabaseManager.fromString(databaseType).relationalDatabaseSupport().processTempTableName(node.tempTableName);
            createTempTableFromRealizedRelationalResultInBlockConnection(realizedRelationalResult, tempTableName, databaseConnection, databaseType, databaseTimeZone, executionState, identity);
        }
        executionState.addResult(node.tempTableName, new PreparedTempTableResult(tempTableName));
    }

    private void recursivelyPopulateEmptyResultsInGraphObjectsBatch(RelationalGraphFetchExecutionNode node, RelationalGraphObjectsBatch relationalGraphObjectsBatch)
    {
        relationalGraphObjectsBatch.setObjectsForNodeIndex(node.nodeIndex, Collections.emptyList());
        if (node.children != null && !node.children.isEmpty())
        {
            node.children.forEach(x -> this.recursivelyPopulateEmptyResultsInGraphObjectsBatch(x, relationalGraphObjectsBatch));
        }
    }

    private static DoubleStrategyHashMap<Object, Object, SQLExecutionResult> switchedParentHashMapPerChildResult(RelationalGraphObjectsBatch relationalGraphObjectsBatch, int parentIndex, ResultSet childResultSet, Supplier<List<String>> parentPrimaryKeyColumnsSupplier, DatabaseConnection databaseConnection)
    {
        List<String> parentPrimaryKeyColumnNames = UpperCaseColumnsIfDbConnectionIsNotCaseSensitive(parentPrimaryKeyColumnsSupplier.get(), databaseConnection);
        List<Integer> parentPrimaryKeyIndices = parentPrimaryKeyColumnNames.stream().map(FunctionHelper.unchecked(childResultSet::findColumn)).collect(Collectors.toList());
        DoubleStrategyHashMap<Object, Object, SQLExecutionResult> parentMap = relationalGraphObjectsBatch.getNodeObjectsHashMap(parentIndex);
        RelationalGraphFetchUtils.switchSecondKeyHashingStrategy(parentMap, relationalGraphObjectsBatch.getNodePrimaryKeyGetters(parentIndex), parentPrimaryKeyIndices);
        return parentMap;
    }

    private static List<String> UpperCaseColumnsIfDbConnectionIsNotCaseSensitive(List<String> columnNames, DatabaseConnection databaseConnection)
    {
        boolean isDatabaseIdentifiersCaseSensitive = databaseConnection.accept(new DatabaseIdentifiersCaseSensitiveVisitor());
        if (!isDatabaseIdentifiersCaseSensitive)
        {
            return columnNames.stream().map(key -> key.toUpperCase()).collect(Collectors.toList());
        }
        return columnNames;
    }

    private synchronized boolean checkForCachingAndPopulateCachingHelpers(List<Pair<String, String>> allInstanceSetImplementations, boolean nodeSupportsCaching, GraphFetchTree nodeSubTree, SQLExecutionResult sqlExecutionResult, Function<Integer, List<String>> pkColumnsFunction, RelationalMultiSetExecutionCacheWrapper multiSetCaches)
    {
        boolean cachingEnabledForNode = (this.executionState.graphFetchCaches != null) && nodeSupportsCaching && RelationalGraphFetchUtils.subTreeValidForCaching(nodeSubTree);
        ResultSet sqlResultSet = sqlExecutionResult.getResultSet();

        if (cachingEnabledForNode)
        {
            int i = 0;
            for (Pair<String, String> setImpl : allInstanceSetImplementations)
            {
                GraphFetchCacheByEqualityKeys cache = RelationalGraphFetchUtils.findCacheByEqualityKeys(nodeSubTree, setImpl.getOne(), setImpl.getTwo(), this.executionState.graphFetchCaches);
                if (cache != null)
                {
                    List<Integer> primaryKeyIndices = pkColumnsFunction.apply(i).stream().map(FunctionHelper.unchecked(sqlResultSet::findColumn)).collect(Collectors.toList());
                    multiSetCaches.addNextValidCache(cache.getExecutionCache(), new RelationalGraphFetchUtils.RelationalSQLResultGraphFetchCacheKey(sqlExecutionResult, primaryKeyIndices));
                }
                else
                {
                    multiSetCaches.addNextEmptyCache();
                }
                i += 1;
            }
        }
        else
        {
            allInstanceSetImplementations.forEach((x) -> multiSetCaches.addNextEmptyCache());
        }

        return cachingEnabledForNode;
    }

    private static Object checkAndReturnCachedObject(boolean cachingEnabledForNode, int setIndex, RelationalMultiSetExecutionCacheWrapper multiSetCache)
    {
        if (cachingEnabledForNode && multiSetCache.setCachingEnabled.get(setIndex))
        {
            return multiSetCache.setCaches.get(setIndex).getIfPresent(multiSetCache.sqlResultCacheKeys.get(setIndex));
        }
        return null;
    }

    private static class RelationalMultiSetExecutionCacheWrapper
    {
        List<Boolean> setCachingEnabled;
        List<ExecutionCache<GraphFetchCacheKey, Object>> setCaches;
        List<RelationalGraphFetchUtils.RelationalSQLResultGraphFetchCacheKey> sqlResultCacheKeys;

        RelationalMultiSetExecutionCacheWrapper(int setIdCount)
        {
            this.setCachingEnabled = new ArrayList<>(setIdCount);
            this.setCaches = new ArrayList<>(setIdCount);
            this.sqlResultCacheKeys = new ArrayList<>(setIdCount);
        }

        void addNextValidCache(ExecutionCache<GraphFetchCacheKey, Object> cache, RelationalGraphFetchUtils.RelationalSQLResultGraphFetchCacheKey cacheKey)
        {
            this.setCachingEnabled.add(true);
            this.setCaches.add(cache);
            this.sqlResultCacheKeys.add(cacheKey);
        }

        void addNextEmptyCache()
        {
            this.setCachingEnabled.add(false);
            this.setCaches.add(null);
            this.sqlResultCacheKeys.add(null);
        }
    }
}
