// Copyright 2024 Goldman Sachs
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

package org.finos.legend.engine.repl.relational.shared;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.eclipse.collections.impl.factory.Lists;
import org.eclipse.collections.impl.utility.ListIterate;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.stores.StoreType;
import org.finos.legend.engine.plan.execution.stores.relational.connection.manager.ConnectionManagerSelector;
import org.finos.legend.engine.plan.execution.stores.relational.exploration.SchemaExportation;
import org.finos.legend.engine.plan.execution.stores.relational.exploration.model.DatabaseBuilderInput;
import org.finos.legend.engine.plan.execution.stores.relational.exploration.model.DatabasePattern;
import org.finos.legend.engine.plan.execution.stores.relational.exploration.model.TargetDatabase;
import org.finos.legend.engine.plan.execution.stores.relational.plugin.RelationalStoreExecutor;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.PackageableConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseType;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.RelationalDatabaseConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Database;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.model.Table;
import org.finos.legend.engine.shared.core.identity.Identity;

public class ConnectionHelper
{
    private static ConnectionManagerSelector getConnectionManagerSelector(PlanExecutor planExecutor)
    {
        RelationalStoreExecutor r = (RelationalStoreExecutor) planExecutor.getExecutorsOfType(StoreType.Relational).getFirst();
        return r.getStoreState().getRelationalExecutor().getConnectionManager();
    }

    public static java.sql.Connection getConnection(DatabaseConnection connection, PlanExecutor planExecutor)
    {
        ConnectionManagerSelector connectionManager = getConnectionManagerSelector(planExecutor);
        return connection == null ? connectionManager.getTestDatabaseConnection() : connectionManager.getDatabaseConnection(new Identity("X"), connection);
    }

    public static Database getDatabase(RelationalDatabaseConnection connection, String _package, String name, PlanExecutor planExecutor)
    {
        try
        {
            SchemaExportation schemaExportation = SchemaExportation.newBuilder(ConnectionHelper.getConnectionManagerSelector(planExecutor));
            DatabaseBuilderInput databaseBuilderInput = new DatabaseBuilderInput();
            databaseBuilderInput.connection = connection;
            databaseBuilderInput.targetDatabase = new TargetDatabase(_package, name);
            databaseBuilderInput.config.patterns = Lists.fixedSize.of(new DatabasePattern(null, null));
            databaseBuilderInput.config.enrichTables = true;
            databaseBuilderInput.config.enrichColumns = true;
            databaseBuilderInput.config.enrichPrimaryKeys = true;
            Database database = schemaExportation.build(databaseBuilderInput, Identity.getAnonymousIdentity());
            if (connection.databaseType == DatabaseType.DuckDB)
            {
                database.schemas.stream().filter(x -> x.name.equals("main")).forEach(x -> x.name = "default");
            }
            return database;
        }
        catch (SQLException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static Stream<Table> getTables(RelationalDatabaseConnection connection, PlanExecutor planExecutor)
    {
        Database database = ConnectionHelper.getDatabase(connection, "", "", planExecutor);
        return database.schemas.stream().flatMap(x -> x.tables.stream());
    }

    public static RelationalDatabaseConnection getDatabaseConnection(PureModelContextData d, String path)
    {
        PackageableConnection packageableConnection = ListIterate.select(d.getElementsOfType(PackageableConnection.class), c -> c.getPath().equals(path)).getFirst();
        if (packageableConnection == null)
        {
            throw new RuntimeException("Error, the connection '" + path + "' can't be found!");
        }
        return (RelationalDatabaseConnection) packageableConnection.connectionValue;
    }
}
