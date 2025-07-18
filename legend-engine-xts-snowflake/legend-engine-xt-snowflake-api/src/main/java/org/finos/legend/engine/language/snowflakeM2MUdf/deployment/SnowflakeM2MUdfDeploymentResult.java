// Copyright 2023 Goldman Sachs
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

package org.finos.legend.engine.language.snowflakeM2MUdf.deployment;

import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.engine.protocol.functionActivator.deployment.DeploymentResult;

public class SnowflakeM2MUdfDeploymentResult extends DeploymentResult
{

    public MutableList<String> errors;

    public SnowflakeM2MUdfDeploymentResult(String activatorIdentifier, boolean result, String deploymentLocation)
    {
      this.successful = result;
      this.activatorIdentifier = activatorIdentifier;
      this.deploymentLocation = deploymentLocation;
    }

    public SnowflakeM2MUdfDeploymentResult(MutableList<String> errors)
    {
        this.errors = errors;
    }

    @Override
    public String toString()
    {
        if (!successful)
        {
            return "Deployment failed. Reason(s): " + this.errors.makeString("[", ",", "]");
        }
        return super.toString();
    }
}
