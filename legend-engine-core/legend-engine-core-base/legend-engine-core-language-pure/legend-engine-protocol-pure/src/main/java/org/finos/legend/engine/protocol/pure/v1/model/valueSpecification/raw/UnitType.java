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

package org.finos.legend.engine.protocol.pure.v1.model.valueSpecification.raw;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.One;
import org.finos.legend.engine.protocol.pure.v1.model.SourceInformation;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecification;
import org.finos.legend.engine.protocol.pure.m3.valuespecification.ValueSpecificationVisitor;

import java.io.IOException;

@JsonDeserialize(using = UnitType.UnitTypeDeserializer.class)
@Deprecated
public class UnitType extends One
{
    public String fullPath;

    public UnitType(String fullPath, SourceInformation sourceInformation)
    {
        this.fullPath = fullPath;
        this.sourceInformation = sourceInformation;
    }

    public UnitType(String fullPath)
    {
        this(fullPath, null);
    }

    @Override
    public <T> T accept(ValueSpecificationVisitor<T> visitor)
    {
        return visitor.visit(this);
    }

    public static class UnitTypeDeserializer extends JsonDeserializer<ValueSpecification>
    {
        @Override
        public ValueSpecification deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException
        {
            JsonNode node = jsonParser.getCodec().readTree(jsonParser);
            JsonNode unitType = node.get("unitType");
            String fullPath = ((unitType == null) ? node.get("fullPath") : unitType).asText();
            JsonNode sourceInformation = node.get("sourceInformation");
            SourceInformation sourceInfo = (sourceInformation == null) ? null : jsonParser.getCodec().treeToValue(sourceInformation, SourceInformation.class);
            return new UnitType(fullPath, sourceInfo);
        }
    }
}
