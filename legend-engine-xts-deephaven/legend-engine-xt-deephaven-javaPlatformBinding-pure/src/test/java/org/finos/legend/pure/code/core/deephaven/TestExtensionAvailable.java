//  Copyright 2025 Goldman Sachs
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.


package org.finos.legend.pure.code.core.deephaven;

import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.pure.code.core.LegendPureCoreExtension;
import org.finos.legend.engine.pure.code.core.PureCoreExtensionLoader;
import org.finos.legend.pure.code.core.DeephavenJavaBindingLegendPureCoreExtension;
import org.finos.legend.pure.generated.Root_meta_pure_extension_Extension;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class TestExtensionAvailable
{
    @Test
    public void testServiceAvailable()
    {
        MutableList<LegendPureCoreExtension> extensions =  PureCoreExtensionLoader.extensions();
        Assert.assertEquals(2, extensions.selectInstancesOf(DeephavenJavaBindingLegendPureCoreExtension.class).get(0).extraPureCoreExtensions(PureModel.getCorePureModel().getExecutionSupport()).size());
        List<String> vals = extensions.get(0).extraPureCoreExtensions(PureModel.getCorePureModel().getExecutionSupport()).toList().collect(Root_meta_pure_extension_Extension::_type).sortThis();
        Assert.assertEquals("PlatformBinding - LegendJava - Deephaven", vals.get(0));
        Assert.assertEquals("deephaven", vals.get(1));
    }
}
