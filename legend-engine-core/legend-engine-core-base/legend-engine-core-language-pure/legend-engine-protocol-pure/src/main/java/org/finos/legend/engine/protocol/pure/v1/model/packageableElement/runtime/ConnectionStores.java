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

package org.finos.legend.engine.protocol.pure.v1.model.packageableElement.runtime;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.finos.legend.engine.protocol.pure.m3.SourceInformation;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.Connection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.ConnectionPointer;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.StoreProviderPointer;

import java.util.ArrayList;
import java.util.List;

public class ConnectionStores
{
    public ConnectionPointer connectionPointer;
    // Only used for TestRunners when they need to generate a test connection in protocol to replace the pointed connection
    @JsonIgnore
    public Connection connection;
    public List<StoreProviderPointer> storePointers = new ArrayList();
    public SourceInformation sourceInformation;
}
