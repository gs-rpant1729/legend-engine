// Copyright 2020 Goldman Sachs
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

package org.finos.legend.engine.language.pure.compiler.toPureGraph;

import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.utility.ListIterate;
import org.finos.legend.engine.protocol.pure.m3.SourceInformation;
import org.finos.legend.engine.protocol.pure.v1.model.context.EngineErrorType;
import org.finos.legend.engine.protocol.pure.v1.model.context.PackageableElementPointer;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.GenerationFeaturesConfig;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.RelationalQueryGenerationConfig;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.postprocessor.Mapper;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.postprocessor.MapperPostProcessor;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.postprocessor.RelationalMapperPostProcessor;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Database;
import org.finos.legend.engine.shared.core.operational.errorManagement.EngineException;
import org.finos.legend.pure.generated.*;

import java.util.List;
import java.util.stream.Collectors;

public class HelperRelationalDatabaseConnectionBuilder
{
    public static void addTestDataSetUp(Root_meta_external_store_relational_runtime_TestDatabaseConnection test, String testDataSetupCsv, java.util.List<String> testDataSetupSqls)
    {
        if (testDataSetupCsv != null)
        {
            test._testDataSetupCsv(testDataSetupCsv);
        }

        if (testDataSetupSqls != null)
        {
            test._testDataSetupSqls(FastList.newList(testDataSetupSqls));
        }
    }

    @Deprecated
    public static void addDatabaseConnectionProperties(Root_meta_external_store_relational_runtime_DatabaseConnection pureConnection, String element, SourceInformation elementSourceInformation, String connectionType, String timeZone, Boolean quoteIdentifiers, CompileContext context)
    {
        addDatabaseConnectionProperties(pureConnection, element, elementSourceInformation, connectionType, timeZone, null, quoteIdentifiers, context);
    }

    public static void addDatabaseConnectionProperties(Root_meta_external_store_relational_runtime_DatabaseConnection pureConnection, String element, SourceInformation elementSourceInformation, String connectionType, String timeZone, Integer queryTimeOutInSeconds, Boolean quoteIdentifiers, CompileContext context)
    {
        Root_meta_external_store_relational_runtime_DatabaseConnection connection = pureConnection._type(context.pureModel.getEnumValue("meta::relational::runtime::DatabaseType", connectionType));
        connection._timeZone(timeZone);
        if (queryTimeOutInSeconds != null)
        {
            connection._queryTimeOutInSeconds(Long.valueOf(queryTimeOutInSeconds));
        }
        connection._quoteIdentifiers(quoteIdentifiers);
        if (element != null)
        {
            try
            {
                HelperRelationalBuilder.resolveDatabase(element, elementSourceInformation, context);
            }
            catch (RuntimeException e)
            {
                Database db = new Database();
                db.name = element;
                db._package = "";
                context.processFirstPass(db);
            }
        }
    }

    public static Root_meta_pure_alloy_connections_MapperPostProcessor createMapperPostProcessor(MapperPostProcessor mapper, CompileContext context)
    {
        return createMapperPostProcessor(mapper.mappers, context);
    }

    public static Root_meta_pure_alloy_connections_RelationalMapperPostProcessor createRelationalMapperPostProcessor(RelationalMapperPostProcessor relationalMapper, CompileContext context)
    {
        return createRelationalMapperPostProcessor(relationalMapper.relationalMappers, context);
    }

    public static Root_meta_pure_alloy_connections_MapperPostProcessor createMapperPostProcessor(List<Mapper> mappers, CompileContext context)
    {
        Root_meta_pure_alloy_connections_MapperPostProcessor p = new Root_meta_pure_alloy_connections_MapperPostProcessor_Impl("", null, context.pureModel.getClass("meta::pure::alloy::connections::MapperPostProcessor"));
        p._mappers(createMappers(mappers, context));
        return p;
    }

    public static Root_meta_pure_alloy_connections_RelationalMapperPostProcessor createRelationalMapperPostProcessor(List<PackageableElementPointer> relationalMappers, CompileContext context)
    {
        Root_meta_pure_alloy_connections_RelationalMapperPostProcessor p = new Root_meta_pure_alloy_connections_RelationalMapperPostProcessor_Impl("", null, context.pureModel.getClass("meta::pure::alloy::connections::RelationalMapperPostProcessor"));
        MutableList<Root_meta_relational_metamodel_RelationalMapper> rm = ListIterate.collect(relationalMappers, r -> (Root_meta_relational_metamodel_RelationalMapper) context.pureModel.getPackageableElement(r.path));
        p._relationalMappers(rm);
        return p;
    }

    public static MutableList<Root_meta_pure_alloy_connections_Mapper> createMappers(List<Mapper> mappers, CompileContext context)
    {
        return ListIterate.collect(mappers, m ->
        {
            if (m instanceof org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.postprocessor.TableNameMapper)
            {
                Root_meta_pure_alloy_connections_TableNameMapper nameMapper = new Root_meta_pure_alloy_connections_TableNameMapper_Impl("", null, context.pureModel.getClass("meta::pure::alloy::connections::TableNameMapper"));
                Root_meta_pure_alloy_connections_SchemaNameMapper schemaNameMapper = new Root_meta_pure_alloy_connections_SchemaNameMapper_Impl("", null, context.pureModel.getClass("meta::pure::alloy::connections::SchemaNameMapper"));

                schemaNameMapper._from(((org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.postprocessor.TableNameMapper) m).schema.from);
                schemaNameMapper._to(((org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.postprocessor.TableNameMapper) m).schema.to);

                nameMapper._from(m.from);
                nameMapper._to(m.to);
                nameMapper._schema(schemaNameMapper);

                return nameMapper;
            }
            else if (m instanceof org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.postprocessor.SchemaNameMapper)
            {
                Root_meta_pure_alloy_connections_SchemaNameMapper schemaNameMapper = new Root_meta_pure_alloy_connections_SchemaNameMapper_Impl("", null, context.pureModel.getClass("meta::pure::alloy::connections::SchemaNameMapper"));
                schemaNameMapper._from(m.from);
                schemaNameMapper._to(m.to);

                return schemaNameMapper;
            }
            throw new IllegalArgumentException("Unknown mapper " + m.getClass().getSimpleName());
        });
    }

    public static void addRelationalQueryGenerationConfigs(DatabaseConnection databaseConnection, Root_meta_external_store_relational_runtime_DatabaseConnection pureConnection, CompileContext context)
    {
        if ((databaseConnection.queryGenerationConfigs != null) && (!databaseConnection.queryGenerationConfigs.isEmpty()))
        {
            MutableList<Root_meta_external_store_relational_runtime_GenerationFeaturesConfig> pureConfigs = Lists.mutable.ofInitialCapacity(databaseConnection.queryGenerationConfigs.size());
            for (RelationalQueryGenerationConfig config : databaseConnection.queryGenerationConfigs)
            {
                if (config instanceof GenerationFeaturesConfig)
                {
                    GenerationFeaturesConfig generationFeaturesConfig = (GenerationFeaturesConfig) config;
                    if (generationFeaturesConfig.enabled != null)
                    {
                        if (generationFeaturesConfig.disabled != null)
                        {
                            generationFeaturesConfig.enabled.forEach(f ->
                            {
                                if (generationFeaturesConfig.disabled.contains(f))
                                {
                                    throw new EngineException("Feature duplicated in enabled and disabled features lists: " + f, config.sourceInformation, EngineErrorType.COMPILATION);
                                }
                            });
                        }
                    }
                    MutableList<? extends String> knownFeatures = core_relational_relational_runtime_relationalRuntimeExtension.Root_meta_external_store_relational_runtime_knownGenerationFeatures__String_MANY_(context.getExecutionSupport()).toList();
                    Root_meta_external_store_relational_runtime_GenerationFeaturesConfig pureGenerationFeaturesConfig = new Root_meta_external_store_relational_runtime_GenerationFeaturesConfig_Impl("", null, context.pureModel.getClass("meta::external::store::relational::runtime::GenerationFeaturesConfig"));
                    if (generationFeaturesConfig.enabled != null)
                    {
                        generationFeaturesConfig.enabled.forEach(f ->
                        {
                            if (!knownFeatures.contains(f))
                            {
                                throw new EngineException("Unknown relational generation feature: " + f + ". Known features are: " + knownFeatures.stream().collect(Collectors.joining(", ", "[", "]")), config.sourceInformation, EngineErrorType.COMPILATION);
                            }
                        });
                        pureGenerationFeaturesConfig._enabled(Lists.mutable.withAll(generationFeaturesConfig.enabled));
                    }
                    if (generationFeaturesConfig.disabled != null)
                    {
                        generationFeaturesConfig.disabled.forEach(f ->
                        {
                            if (!knownFeatures.contains(f))
                            {
                                throw new EngineException("Unknown relational generation feature: " + f, config.sourceInformation, EngineErrorType.COMPILATION);
                            }
                        });
                        pureGenerationFeaturesConfig._disabled(Lists.mutable.withAll(generationFeaturesConfig.disabled));
                    }
                    pureConfigs.add(pureGenerationFeaturesConfig);
                }
                else
                {
                    throw new EngineException("Unhandled relational query config of type: " + config.getClass().getCanonicalName(), config.sourceInformation, EngineErrorType.COMPILATION);
                }
            }
            pureConnection._queryGenerationConfigs(pureConfigs);
        }
    }
}
