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

package org.finos.legend.engine.application.query.api;

import org.eclipse.collections.api.block.function.Function0;
import org.eclipse.collections.api.factory.Lists;
import org.finos.legend.engine.application.query.model.*;
import org.finos.legend.engine.application.query.utils.TestMongoClientProvider;
import org.finos.legend.engine.protocol.pure.m3.extension.StereotypePtr;
import org.finos.legend.engine.protocol.pure.m3.extension.TagPtr;
import org.finos.legend.engine.protocol.pure.m3.extension.TaggedValue;
import org.finos.legend.engine.shared.core.vault.TestVaultImplementation;
import org.finos.legend.engine.shared.core.vault.Vault;
import org.junit.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class TestQueryStoreManager
{
    static class TestQuerySearchSpecificationBuilder
    {
        public QuerySearchTermSpecification searchTermSpecification;
        public List<QueryProjectCoordinates> projectCoordinates;
        public List<TaggedValue> taggedValues;
        public List<StereotypePtr> stereotypes;
        public Integer limit;
        public Boolean showCurrentUserQueriesOnly;
        public Boolean combineTaggedValuesCondition;
        public QuerySearchSortBy sortByOption;

        TestQuerySearchSpecificationBuilder withSearchTerm(String searchTerm)
        {
            if (this.searchTermSpecification == null)
            {
                this.searchTermSpecification = new QuerySearchTermSpecification();
            }
            this.searchTermSpecification.searchTerm = searchTerm;
            return this;
        }

        TestQuerySearchSpecificationBuilder withProjectCoordinates(List<QueryProjectCoordinates> projectCoordinates)
        {
            this.projectCoordinates = projectCoordinates;
            return this;
        }

        TestQuerySearchSpecificationBuilder withTaggedValues(List<TaggedValue> taggedValues)
        {
            this.taggedValues = taggedValues;
            return this;
        }

        TestQuerySearchSpecificationBuilder withStereotypes(List<StereotypePtr> stereotypes)
        {
            this.stereotypes = stereotypes;
            return this;
        }

        TestQuerySearchSpecificationBuilder withLimit(Integer limit)
        {
            this.limit = limit;
            return this;
        }

        TestQuerySearchSpecificationBuilder withShowCurrentUserQueriesOnly(Boolean showCurrentUserQueriesOnly)
        {
            this.showCurrentUserQueriesOnly = showCurrentUserQueriesOnly;
            return this;
        }

        TestQuerySearchSpecificationBuilder withExactNameSearch(Boolean exactMatchName)
        {
            if (this.searchTermSpecification == null)
            {
                this.searchTermSpecification = new QuerySearchTermSpecification();
            }
            this.searchTermSpecification.exactMatchName = exactMatchName;
            return this;
        }

        TestQuerySearchSpecificationBuilder withIncludeOwner(Boolean includeOwner)
        {
            if (this.searchTermSpecification == null)
            {
                this.searchTermSpecification = new QuerySearchTermSpecification();
            }
            this.searchTermSpecification.includeOwner = includeOwner;
            return this;
        }

        TestQuerySearchSpecificationBuilder withCombineTaggedValuesCondition(Boolean combineTaggedValuesCondition)
        {
            this.combineTaggedValuesCondition = combineTaggedValuesCondition;
            return this;
        }

        TestQuerySearchSpecificationBuilder withSortByOption(QuerySearchSortBy sortByOption)
        {
            this.sortByOption = sortByOption;
            return this;
        }

        QuerySearchSpecification build()
        {
            QuerySearchSpecification searchSpecification = new QuerySearchSpecification();
            searchSpecification.searchTermSpecification = this.searchTermSpecification;
            searchSpecification.projectCoordinates = this.projectCoordinates;
            searchSpecification.taggedValues = this.taggedValues;
            searchSpecification.stereotypes = this.stereotypes;
            searchSpecification.limit = this.limit;
            searchSpecification.showCurrentUserQueriesOnly = this.showCurrentUserQueriesOnly;
            searchSpecification.combineTaggedValuesCondition = this.combineTaggedValuesCondition;
            searchSpecification.sortByOption = this.sortByOption;
            return searchSpecification;
        }
    }

    static class TestQueryBuilder
    {
        public String id;
        public String name;
        public String owner;
        public String groupId = "test.group";
        public String artifactId = "test-artifact";
        public String versionId = "0.0.0";
        public String originalVersionId = "0.0.0";
        public String description = "description";
        public String mapping;
        public String runtime;
        public String content = "content";
        public List<TaggedValue> taggedValues = Collections.emptyList();
        public List<StereotypePtr> stereotypes = Collections.emptyList();
        public List<QueryParameterValue> parameterValues = Collections.emptyList();
        public Map<String, ?> gridConfigs;

        public QueryExecutionContext executionContext;

        static TestQueryBuilder create(String id, String name, String owner)
        {
            TestQueryBuilder queryBuilder = new TestQueryBuilder();
            queryBuilder.id = id;
            queryBuilder.name = name;
            queryBuilder.owner = owner;
            return queryBuilder;
        }

        TestQueryBuilder withLegacyExecutionContext()
        {
            this.mapping = "mapping";
            this.runtime = "runtime";
            return this;
        }


        TestQueryBuilder withExplicitExecution()
        {
            QueryExplicitExecutionContext explicitExecutionContext = new QueryExplicitExecutionContext();
            explicitExecutionContext.runtime = "runtime";
            explicitExecutionContext.mapping = "mapping";
            this.executionContext = explicitExecutionContext;
            return this;
        }


        TestQueryBuilder withDataSpaceExecution(String dataSpace, String key)
        {
            QueryDataSpaceExecutionContext dataSpaceExecutionContext = new QueryDataSpaceExecutionContext();
            dataSpaceExecutionContext.dataSpacePath = dataSpace;
            dataSpaceExecutionContext.executionKey = key;
            this.executionContext = dataSpaceExecutionContext;
            return this;
        }

        TestQueryBuilder withDataSpaceExecution(String dataSpace)
        {
            return this.withDataSpaceExecution(dataSpace, null);
        }

        TestQueryBuilder withGroupId(String groupId)
        {
            this.groupId = groupId;
            return this;
        }

        TestQueryBuilder withArtifactId(String artifactId)
        {
            this.artifactId = artifactId;
            return this;
        }

        TestQueryBuilder withVersionId(String versionId)
        {
            this.versionId = versionId;
            return this;
        }

        TestQueryBuilder withTaggedValues(List<TaggedValue> taggedValues)
        {
            this.taggedValues = taggedValues;
            return this;
        }

        TestQueryBuilder withStereotypes(List<StereotypePtr> stereotypes)
        {
            this.stereotypes = stereotypes;
            return this;
        }

        TestQueryBuilder withParameterValues(List<QueryParameterValue> parameterValues)
        {
            this.parameterValues = parameterValues;
            return this;
        }

        TestQueryBuilder withGridConfigs(Map<String, ?> gridConfigs)
        {
            this.gridConfigs = gridConfigs;
            return this;
        }

        Query build()
        {
            Query query = new Query();
            query.id = this.id;
            query.name = this.name;
            query.owner = this.owner;
            query.groupId = this.groupId;
            query.artifactId = this.artifactId;
            query.versionId = this.versionId;
            query.originalVersionId = this.originalVersionId;
            query.mapping = this.mapping;
            query.runtime = this.runtime;
            query.content = this.content;
            query.description = this.description;
            query.taggedValues = this.taggedValues;
            query.stereotypes = this.stereotypes;
            query.defaultParameterValues = this.parameterValues;
            query.gridConfig = this.gridConfigs;
            query.executionContext = this.executionContext;
            return query;
        }
    }

    private static QueryProjectCoordinates createTestQueryProjectCoordinate(String groupId, String artifactId, String version)
    {
        QueryProjectCoordinates coordinate = new QueryProjectCoordinates();
        coordinate.groupId = groupId;
        coordinate.artifactId = artifactId;
        coordinate.version = version;
        return coordinate;
    }

    private static TaggedValue createTestTaggedValue(String profile, String tag, String value)
    {
        TaggedValue taggedValue = new TaggedValue();
        taggedValue.tag = new TagPtr();
        taggedValue.tag.profile = profile;
        taggedValue.tag.value = tag;
        taggedValue.value = value;
        return taggedValue;
    }

    private static StereotypePtr createTestStereotype(String profile, String stereotype)
    {
        StereotypePtr taggedValue = new StereotypePtr();
        taggedValue.profile = profile;
        taggedValue.value = stereotype;
        return taggedValue;
    }

    private static QueryParameterValue createTestParameterValue(String name, String value)
    {
        QueryParameterValue queryParameterValue = new QueryParameterValue();
        queryParameterValue.content = value;
        queryParameterValue.name = name;
        return queryParameterValue;
    }

    private TestMongoClientProvider testMongoClientProvider = new TestMongoClientProvider();
    private final QueryStoreManager store = new QueryStoreManager(testMongoClientProvider.mongoClient);
    private static final TestVaultImplementation testVaultImplementation = new TestVaultImplementation();

    @BeforeClass
    public static void setupClass()
    {
        testVaultImplementation.setValue("query.mongo.database", "test");
        testVaultImplementation.setValue("query.mongo.collection.query", "query");
        testVaultImplementation.setValue("query.mongo.collection.queryEvent", "query-event");
        Vault.INSTANCE.registerImplementation(testVaultImplementation);
    }

    @AfterClass
    public static void cleanUpClass()
    {
        Vault.INSTANCE.unregisterImplementation(testVaultImplementation);
    }

    @Before
    public void setup()
    {
        this.testMongoClientProvider = new TestMongoClientProvider();
    }

    @After
    public void cleanUp()
    {
        this.testMongoClientProvider.cleanUp();
    }

    @Test
    public void testValidateQuery()
    {
        String currentUser = "testUser";
        Function0<Query> _createTestQuery = () -> TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query goodQuery = _createTestQuery.get();
        QueryStoreManager.validateQuery(goodQuery);

        // ID
        Query queryWithInvalidId = _createTestQuery.get();
        queryWithInvalidId.id = null;
        Assert.assertEquals("Query ID is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidId)).getMessage());
        queryWithInvalidId.id = "";
        Assert.assertEquals("Query ID is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidId)).getMessage());

        // Name
        Query queryWithInvalidName = _createTestQuery.get();
        queryWithInvalidName.name = null;
        Assert.assertEquals("Query name is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidName)).getMessage());
        queryWithInvalidId.name = "";
        Assert.assertEquals("Query name is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidName)).getMessage());

        // Group ID
        Query queryWithInvalidGroupId = _createTestQuery.get();
        queryWithInvalidGroupId.groupId = null;
        Assert.assertEquals("Query project group ID is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidGroupId)).getMessage());
        queryWithInvalidGroupId.groupId = "";
        Assert.assertEquals("Query project group ID is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidGroupId)).getMessage());
        queryWithInvalidGroupId.groupId = "group-test";
        Assert.assertEquals("Query project group ID is invalid", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidGroupId)).getMessage());
        queryWithInvalidGroupId.groupId = "12314";
        Assert.assertEquals("Query project group ID is invalid", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidGroupId)).getMessage());

        // Artifact ID
        Query queryWithInvalidArtifactId = _createTestQuery.get();
        queryWithInvalidArtifactId.artifactId = null;
        Assert.assertEquals("Query project artifact ID is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidArtifactId)).getMessage());
        queryWithInvalidArtifactId.artifactId = "";
        Assert.assertEquals("Query project artifact ID is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidArtifactId)).getMessage());
        queryWithInvalidArtifactId.artifactId = "Group";
        Assert.assertEquals("Query project artifact ID is invalid", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidArtifactId)).getMessage());
        queryWithInvalidArtifactId.artifactId = "someArtifact";
        Assert.assertEquals("Query project artifact ID is invalid", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidArtifactId)).getMessage());

        // Version
        Query queryWithInvalidVersionId = _createTestQuery.get();
        queryWithInvalidVersionId.versionId = null;
        Assert.assertEquals("Query project version is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidVersionId)).getMessage());
        queryWithInvalidVersionId.versionId = "";
        Assert.assertEquals("Query project version is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidVersionId)).getMessage());

        // Legacy Mapping/Runtime
        Query queryWithInvalidMapping = TestQueryBuilder.create("1", "query1", "testUser").build();
        Assert.assertEquals("Query mapping is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidMapping)).getMessage());
        queryWithInvalidMapping.mapping = "";
        Assert.assertEquals("Query mapping is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidMapping)).getMessage());
        Query queryWithInvalidRuntime = _createTestQuery.get();
        queryWithInvalidRuntime.runtime = null;
        Assert.assertEquals("Query runtime is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidRuntime)).getMessage());
        queryWithInvalidRuntime.runtime = "";
        Assert.assertEquals("Query runtime is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidRuntime)).getMessage());


        // Mapping/Runtime
        Query queryWithExplicitExecution = TestQueryBuilder.create("1", "query1", "testUser").withExplicitExecution().build();
        QueryStoreManager.validateQuery(queryWithExplicitExecution);
        QueryExplicitExecutionContext queryExplicitExecutionContext = (QueryExplicitExecutionContext) queryWithExplicitExecution.executionContext;
        queryExplicitExecutionContext.mapping = null;
        Assert.assertEquals("Query mapping is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithExplicitExecution)).getMessage());
        queryExplicitExecutionContext.mapping = "";
        Assert.assertEquals("Query mapping is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithExplicitExecution)).getMessage());
        Query queryWithExplicitExecutionInvalidRuntime = TestQueryBuilder.create("1", "query1", "testUser").withExplicitExecution().build();
        queryExplicitExecutionContext = (QueryExplicitExecutionContext) queryWithExplicitExecutionInvalidRuntime.executionContext;
        queryExplicitExecutionContext.runtime = null;
        Assert.assertEquals("Query runtime is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithExplicitExecutionInvalidRuntime)).getMessage());
        queryExplicitExecutionContext.runtime = "";
        Assert.assertEquals("Query runtime is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithExplicitExecutionInvalidRuntime)).getMessage());

        // DataSpace exec context
        Query queryWithDataSpaceExec = TestQueryBuilder.create("1", "query1", "testUser").withDataSpaceExecution("my::dataspace").build();
        QueryStoreManager.validateQuery(queryWithDataSpaceExec);
        QueryDataSpaceExecutionContext queryDataSpaceExecutionContext = (QueryDataSpaceExecutionContext) queryWithDataSpaceExec.executionContext;
        queryDataSpaceExecutionContext.dataSpacePath = null;
        Assert.assertEquals("Query data Space execution context dataSpace path is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithDataSpaceExec)).getMessage());
        queryDataSpaceExecutionContext.dataSpacePath = "";
        Assert.assertEquals("Query data Space execution context dataSpace path is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithDataSpaceExec)).getMessage());

        // Content
        Query queryWithInvalidContent = _createTestQuery.get();
        queryWithInvalidContent.content = null;
        Assert.assertEquals("Query content is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidContent)).getMessage());
        queryWithInvalidContent.content = "";
        Assert.assertEquals("Query content is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> QueryStoreManager.validateQuery(queryWithInvalidContent)).getMessage());
    }

    @Test
    public void testSearchQueries() throws Exception
    {
        String currentUser = "testUser";
        Query newQuery = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().withGroupId("test.group").withArtifactId("test-artifact").build();
        store.createQuery(newQuery, currentUser);
        List<Query> queries = store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser);
        Assert.assertEquals(1, queries.size());
        Query lightQuery = queries.get(0);
        Assert.assertEquals("test-artifact", lightQuery.artifactId);
        Assert.assertEquals("test.group", lightQuery.groupId);
        Assert.assertEquals("1", lightQuery.id);
        Assert.assertEquals("query1", lightQuery.name);
        Assert.assertEquals("0.0.0", lightQuery.versionId);
        Assert.assertEquals("0.0.0", lightQuery.originalVersionId);
        Assert.assertNotNull(lightQuery.createdAt);
        Assert.assertNotNull(lightQuery.lastUpdatedAt);
        Assert.assertNull(lightQuery.content);
        Assert.assertNull(lightQuery.description);
        Assert.assertNull(lightQuery.mapping);
        Assert.assertNull(lightQuery.runtime);
        Assert.assertNull(lightQuery.stereotypes);
        Assert.assertNull(lightQuery.taggedValues);
    }

    @Test
    public void testMatchExactNameQuery() throws Exception
    {
        String currentUser = "testUser";
        Query newQuery = TestQueryBuilder.create("1", "Test Query 1", currentUser).withLegacyExecutionContext().withGroupId("test.group").withArtifactId("test-artifact").build();
        Query newQueryTwo = TestQueryBuilder.create("2", "Test Query 12", currentUser).withLegacyExecutionContext().withGroupId("test.group").withArtifactId("test-artifact").build();
        Query newQueryThree = TestQueryBuilder.create("3", "Test Query 13", currentUser).withLegacyExecutionContext().withGroupId("test.group").withArtifactId("test-artifact").build();
        store.createQuery(newQuery, currentUser);
        store.createQuery(newQueryTwo, currentUser);
        store.createQuery(newQueryThree, currentUser);
        List<Query> queriesGeneralSearch = store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("Test Query 1").build(), currentUser);
        Assert.assertEquals(3, queriesGeneralSearch.size());
        List<Query> queriesExactSearch = store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("Test Query 1").withExactNameSearch(true).build(), currentUser);
        Assert.assertEquals(1, queriesExactSearch.size());
    }

    @Test
    public void testGetQueryStats() throws Exception
    {
        String currentUser = "testUser";
        Assert.assertEquals(Long.valueOf(0), this.store.getQueryStoreStats().getQueryCount());
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(Long.valueOf(2), this.store.getQueryStoreStats().getQueryCount());
    }

    @Test
    public void testGetQueryStatsWithDataSpaceQueryCount() throws Exception
    {
        String currentUser = "testUser";
        TaggedValue taggedValue1 = createTestTaggedValue("meta::pure::profiles::query", "dataSpace", "value1");
        Assert.assertEquals(Long.valueOf(0), this.store.getQueryStoreStats().getQueryCount());
        Query query = store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue1)).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(Long.valueOf(1), this.store.getQueryStoreStats().getQueryCreatedFromDataSpaceCount());
    }

    @Test
    public void testGetQueriesWithLimit() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withLimit(1).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withLimit(0).build(), currentUser).size());
    }

    @Test
    public void testGetQueriesWithProjectCoordinates() throws Exception
    {
        String currentUser = "testUser";
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withGroupId("test").withArtifactId("test").withLegacyExecutionContext().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withGroupId("test").withArtifactId("test").withLegacyExecutionContext().build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withGroupId("something").withArtifactId("something").withLegacyExecutionContext().build();
        Query testQuery4 = TestQueryBuilder.create("4", "query4", currentUser).withGroupId("something.another").withArtifactId("something-another").withVersionId("1.0.0").withLegacyExecutionContext().build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);
        store.createQuery(testQuery3, currentUser);
        store.createQuery(testQuery4, currentUser);

        // When no projects specified, return all queries
        Assert.assertEquals(4, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());

        QueryProjectCoordinates coordinate1 = createTestQueryProjectCoordinate("notfound", "notfound", null);
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withProjectCoordinates(Lists.fixedSize.of(coordinate1)).build(), currentUser).size());

        QueryProjectCoordinates coordinate2 = createTestQueryProjectCoordinate("test", "test", null);
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withProjectCoordinates(Lists.fixedSize.of(coordinate2)).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withProjectCoordinates(Lists.fixedSize.of(coordinate1, coordinate2)).build(), currentUser).size());

        QueryProjectCoordinates coordinate3 = createTestQueryProjectCoordinate("something", "something", null);
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withProjectCoordinates(Lists.fixedSize.of(coordinate3)).build(), currentUser).size());
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().withProjectCoordinates(Lists.fixedSize.of(coordinate1, coordinate2, coordinate3)).build(), currentUser).size());

        QueryProjectCoordinates coordinate4 = createTestQueryProjectCoordinate("something.another", "something-another", "1.0.0");
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withProjectCoordinates(Lists.fixedSize.of(coordinate4)).build(), currentUser).size());
        Assert.assertEquals(4, store.searchQueries(new TestQuerySearchSpecificationBuilder().withProjectCoordinates(Lists.fixedSize.of(coordinate1, coordinate2, coordinate3, coordinate4)).build(), currentUser).size());
    }

    @Test
    public void testGetQueriesWithSortBy() throws Exception
    {
        String currentUser = "testUser";
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withGroupId("test").withArtifactId("test").withLegacyExecutionContext().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withGroupId("test").withArtifactId("test").withLegacyExecutionContext().build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withGroupId("something").withArtifactId("something").withLegacyExecutionContext().build();
        Query testQuery4 = TestQueryBuilder.create("4", "query4", currentUser).withGroupId("something.another").withArtifactId("something-another").withVersionId("1.0.0").withLegacyExecutionContext().build();

        // create in order 1 -> 4 -> 2 -> 3
        store.createQuery(testQuery1, currentUser);
        Thread.sleep(100);
        store.createQuery(testQuery4, currentUser);
        Thread.sleep(100);
        store.createQuery(testQuery2, currentUser);
        Thread.sleep(100);
        store.createQuery(testQuery3, currentUser);

        Assert.assertEquals(4, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSortByOption(QuerySearchSortBy.SORT_BY_CREATE).build(), currentUser).size());
        Assert.assertEquals(Arrays.asList("3", "2", "4", "1"), store.searchQueries(new TestQuerySearchSpecificationBuilder().withSortByOption(QuerySearchSortBy.SORT_BY_CREATE).build(), currentUser).stream().map(q -> q.id).collect(Collectors.toList()));

        store.updateQuery("2", TestQueryBuilder.create("2", "query2NewlyUpdated", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(Arrays.asList("2", "3", "4", "1"), store.searchQueries(new TestQuerySearchSpecificationBuilder().withSortByOption(QuerySearchSortBy.SORT_BY_UPDATE).build(), currentUser).stream().map(q -> q.id).collect(Collectors.toList()));

        store.getQuery("1");
        Assert.assertEquals(Arrays.asList("1", "2", "3", "4"), store.searchQueries(new TestQuerySearchSpecificationBuilder().withSortByOption(QuerySearchSortBy.SORT_BY_VIEW).build(), currentUser).stream().map(q -> q.id).collect(Collectors.toList()));
    }

    @Test
    public void testGetQueriesWithStereotypes() throws Exception
    {
        String currentUser = "testUser";
        StereotypePtr stereotype1 = createTestStereotype("profile1", "stereotype1");
        StereotypePtr stereotype2 = createTestStereotype("profile2", "stereotype2");
        StereotypePtr stereotype3 = createTestStereotype("profile3", "stereotype3");
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withStereotypes(Lists.fixedSize.of(stereotype1)).withLegacyExecutionContext().build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withStereotypes(Lists.fixedSize.of(stereotype2)).withLegacyExecutionContext().build();
        Query testQuery4 = TestQueryBuilder.create("4", "query3", currentUser).withStereotypes(Lists.fixedSize.of(stereotype1, stereotype2)).withLegacyExecutionContext().build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);
        store.createQuery(testQuery3, currentUser);
        store.createQuery(testQuery4, currentUser);

        // When no stereotype provided, return all queries
        Assert.assertEquals(4, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withStereotypes(Lists.fixedSize.of(stereotype3)).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withStereotypes(Lists.fixedSize.of(stereotype1)).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withStereotypes(Lists.fixedSize.of(stereotype2)).build(), currentUser).size());
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().withStereotypes(Lists.fixedSize.of(stereotype1, stereotype2)).build(), currentUser).size());
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().withStereotypes(Lists.fixedSize.of(stereotype1, stereotype2, stereotype3)).build(), currentUser).size());
    }

    @Test
    public void testGetQueriesWithParameterValues() throws Exception
    {
        String currentUser = "testUser";
        QueryParameterValue param1 = createTestParameterValue("booleanParam1", "true");
        QueryParameterValue param2 = createTestParameterValue("stringParam2", "'myString'");
        QueryParameterValue param3 = createTestParameterValue("myListParam3", "['d','a']");
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withParameterValues(Lists.fixedSize.of(param1)).withLegacyExecutionContext().build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withParameterValues(Lists.fixedSize.of(param2)).withLegacyExecutionContext().build();
        Query testQuery4 = TestQueryBuilder.create("4", "query4", currentUser).withParameterValues(Lists.fixedSize.of(param1, param2, param3)).withLegacyExecutionContext().build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);
        store.createQuery(testQuery3, currentUser);
        store.createQuery(testQuery4, currentUser);

        Query query1 = store.getQuery("1");
        Assert.assertEquals(0, query1.defaultParameterValues.size());
        Assert.assertNotNull(query1.lastOpenAt);
        Assert.assertNotEquals(query1.lastOpenAt, query1.createdAt);

        Query query2 = store.getQuery("2");
        Assert.assertEquals(1, query2.defaultParameterValues.size());
        Assert.assertEquals("booleanParam1", query2.defaultParameterValues.get(0).name);
        Assert.assertEquals("true", query2.defaultParameterValues.get(0).content);
        Assert.assertNotNull(query2.lastOpenAt);
        Assert.assertNotEquals(query2.lastOpenAt, query2.createdAt);

        Query query4 = store.getQuery("4");
        Assert.assertEquals(3, query4.defaultParameterValues.size());
        Assert.assertEquals("booleanParam1", query4.defaultParameterValues.get(0).name);
        Assert.assertEquals("stringParam2", query4.defaultParameterValues.get(1).name);
        Assert.assertEquals("myListParam3", query4.defaultParameterValues.get(2).name);
        Assert.assertEquals("['d','a']", query4.defaultParameterValues.get(2).content);
        Assert.assertNotNull(query4.lastOpenAt);
        Assert.assertNotEquals(query4.lastOpenAt, query4.createdAt);
    }


    @Test
    public void testGetQueriesWithExecContext() throws Exception
    {
        String currentUser = "testUser";
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withExplicitExecution().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withDataSpaceExecution("my::dataSpace").build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withDataSpaceExecution("my::dataSpace", "myKey").build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);
        store.createQuery(testQuery3, currentUser);

        Query query1 = store.getQuery("1");
        Assert.assertTrue(query1.executionContext instanceof QueryExplicitExecutionContext);
        Assert.assertEquals(((QueryExplicitExecutionContext) query1.executionContext).runtime, "runtime");
        Assert.assertEquals(((QueryExplicitExecutionContext) query1.executionContext).mapping, "mapping");

        Query query2 = store.getQuery("2");
        Assert.assertTrue(query2.executionContext instanceof QueryDataSpaceExecutionContext);
        Assert.assertEquals(((QueryDataSpaceExecutionContext) query2.executionContext).dataSpacePath, "my::dataSpace");
        Assert.assertNull(((QueryDataSpaceExecutionContext) query2.executionContext).executionKey);

        Query query3 = store.getQuery("3");
        Assert.assertTrue(query3.executionContext instanceof QueryDataSpaceExecutionContext);
        Assert.assertEquals(((QueryDataSpaceExecutionContext) query3.executionContext).dataSpacePath, "my::dataSpace");
        Assert.assertEquals(((QueryDataSpaceExecutionContext) query3.executionContext).executionKey, "myKey");
    }


    @Test
    public void testGetQueriesWithTaggedValues() throws Exception
    {
        String currentUser = "testUser";
        TaggedValue taggedValue1 = createTestTaggedValue("profile1", "tag1", "value1");
        TaggedValue taggedValue2 = createTestTaggedValue("profile2", "tag2", "value2");
        TaggedValue taggedValue3 = createTestTaggedValue("profile3", "tag3", "value3");
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue1)).withLegacyExecutionContext().build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue2)).withLegacyExecutionContext().build();
        Query testQuery4 = TestQueryBuilder.create("4", "query3", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue1, taggedValue2)).withLegacyExecutionContext().build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);
        store.createQuery(testQuery3, currentUser);
        store.createQuery(testQuery4, currentUser);

        // When no tagged value provided, return all queries
        Assert.assertEquals(4, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue3)).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue1)).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue2)).build(), currentUser).size());
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue1, taggedValue2)).build(), currentUser).size());
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue1, taggedValue2, taggedValue3)).build(), currentUser).size());
    }

    @Test
    public void testGetDataSpaceQueriesWithExecutionContext() throws Exception
    {
        String currentUser = "testUser";
        String dataspacePath = "test::Dataspace";
        TaggedValue taggedValue = createTestTaggedValue("meta::pure::profiles::query", "dataSpace", dataspacePath);
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withDataSpaceExecution(dataspacePath).build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue)).withDataSpaceExecution(dataspacePath).build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);

        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue)).build(), currentUser).size());
    }

    @Test
    public void testGetQueriesWithGridConfigs() throws Exception
    {
        String currentUser = "testUser";

        Map<String, Object> gridConfig = new HashMap<>();
        gridConfig.put("dummyValue", "value");
        gridConfig.put("isPivotModeEnabled", true);
        gridConfig.put("myNullValue", null);
        gridConfig.put("columns", Collections.emptyList());
        Map<String, Object> inner = new HashMap<>();
        inner.put("myCol", "val");
        gridConfig.put("config", inner);
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withGridConfigs(gridConfig).withLegacyExecutionContext().build();
        store.createQuery(testQuery1, currentUser);
        Query query = store.getQuery("1");
        Map<String, ?> gridConfigs = query.gridConfig;
        Assert.assertNotNull(gridConfigs);
        Assert.assertEquals(gridConfigs.get("dummyValue"), "value");
        Assert.assertEquals(gridConfigs.get("isPivotModeEnabled"), true);
        Assert.assertNotNull(gridConfigs.get("config"));
        Map<String, ?> innerResolved = (Map<String, ?>) gridConfigs.get("config");
        Assert.assertEquals(innerResolved.get("myCol"), "val");
    }

    @Test
    public void testGetQueriesWithSearchText() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("3", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("query1").build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("query2").build(), currentUser).size());
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("query").build(), currentUser).size());
    }


    @Test
    public void testGetQueriesWithSearchTextSpec() throws Exception
    {
        String currentUser = "user1";
        String user2 = "user2";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("3", "query3", user2).withLegacyExecutionContext().build(), user2);
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("user2").withIncludeOwner(true).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("user1").withIncludeOwner(true).build(), currentUser).size());
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("user").withIncludeOwner(true).build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("user").withExactNameSearch(true).withIncludeOwner(true).build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("user").withIncludeOwner(false).build(), currentUser).size());
    }

    @Test
    public void testGetQueriesForCurrentUser() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), "testUser1");
        store.createQuery(TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withShowCurrentUserQueriesOnly(false).build(), currentUser).size());
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withShowCurrentUserQueriesOnly(true).build(), currentUser).size());
    }

    @Test
    public void testGetNotFoundQuery()
    {
        Assert.assertEquals("Can't find query with ID '1'", Assert.assertThrows(ApplicationQueryException.class, () -> store.getQuery("1")).getMessage());
    }

    @Test
    public void testCreateSimpleQuery() throws Exception
    {
        String currentUser = "testUser";
        Query newQuery = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query createdQuery = store.createQuery(newQuery, currentUser);
        Assert.assertEquals("test-artifact", createdQuery.artifactId);
        Assert.assertEquals("test.group", createdQuery.groupId);
        Assert.assertEquals("1", createdQuery.id);
        Assert.assertEquals("query1", createdQuery.name);
        Assert.assertEquals("0.0.0", createdQuery.versionId);
        Assert.assertEquals("0.0.0", createdQuery.originalVersionId);
        Assert.assertEquals("content", createdQuery.content);
        Assert.assertEquals("description", createdQuery.description);
        Assert.assertEquals("mapping", createdQuery.mapping);
        Assert.assertEquals("runtime", createdQuery.runtime);
        Assert.assertEquals(0, createdQuery.stereotypes.size());
        Assert.assertEquals(0, createdQuery.taggedValues.size());
        Assert.assertNotNull(createdQuery.createdAt);
        Assert.assertEquals(createdQuery.createdAt, createdQuery.lastUpdatedAt);
        Assert.assertEquals(createdQuery.lastOpenAt, createdQuery.lastUpdatedAt);
    }

    @Test
    public void testCreateInvalidQuery()
    {
        String currentUser = "testUser";
        Assert.assertEquals("Query name is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> store.createQuery(TestQueryBuilder.create("1", null, currentUser).withLegacyExecutionContext().build(), currentUser)).getMessage());
    }

    @Test
    public void testCreateQueryWithSameId() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals("Query with ID '1' already existed", Assert.assertThrows(ApplicationQueryException.class, () -> store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser)).getMessage());
    }

    @Test
    public void testForceCurrentUserToBeOwnerWhenCreatingQuery() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", null).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(currentUser, store.getQuery("1").owner);
        store.createQuery(TestQueryBuilder.create("2", "query2", "testUser2").withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(currentUser, store.getQuery("2").owner);
        store.createQuery(TestQueryBuilder.create("3", "query1", "testUser2").withLegacyExecutionContext().build(), null);
        Assert.assertNull(store.getQuery("3").owner);
    }

    @Test
    public void testUpdateQuery() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.updateQuery("1", TestQueryBuilder.create("1", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals("query2", store.getQuery("1").name);
    }

    @Test
    public void testUpdateQueryVersion() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.updateQuery("1", TestQueryBuilder.create("1", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals("query2", store.getQuery("1").name);
        Query queryWithSelectedFields = new Query();
        queryWithSelectedFields.id = "1";
        queryWithSelectedFields.versionId = "1.0.0";
        store.patchQuery("1", queryWithSelectedFields, currentUser);
        Assert.assertEquals("1.0.0", store.getQuery("1").versionId);
        Assert.assertEquals("0.0.0", store.getQuery("1").originalVersionId);
    }

    @Test
    public void testUpdateWithInvalidQuery()
    {
        String currentUser = "testUser";
        Assert.assertEquals("Query name is missing or empty", Assert.assertThrows(ApplicationQueryException.class, () -> store.updateQuery("1", TestQueryBuilder.create("1", null, currentUser).withLegacyExecutionContext().build(), currentUser)).getMessage());
    }

    @Test
    public void testPreventUpdateQueryId() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", null).withLegacyExecutionContext().build(), null);
        Assert.assertEquals("Updating query ID is not supported", Assert.assertThrows(ApplicationQueryException.class, () -> store.updateQuery("1", TestQueryBuilder.create("2", "query1", "testUser2").withLegacyExecutionContext().build(), currentUser)).getMessage());
    }

    @Test
    public void testUpdateNotFoundQuery()
    {
        String currentUser = "testUser";
        Assert.assertThrows(ApplicationQueryException.class, () -> store.updateQuery("1", TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser));
    }

    @Test
    public void testAllowUpdateQueryWithoutOwner() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", null).withLegacyExecutionContext().build(), null);
        store.updateQuery("1", TestQueryBuilder.create("1", "query2", null).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(currentUser, store.getQuery("1").owner);
    }

    @Test
    public void testForbidUpdateQueryOfAnotherUser() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals("Only owner can update the query", Assert.assertThrows(ApplicationQueryException.class, () -> store.updateQuery("1", TestQueryBuilder.create("1", "query1", "testUser2").withLegacyExecutionContext().build(), "testUser2")).getMessage());
    }

    @Test
    public void testDeleteQuery() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.deleteQuery("1", currentUser);
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
    }

    @Test
    public void testDeleteNotFoundQuery()
    {
        String currentUser = "testUser";
        Assert.assertEquals("Can't find query with ID '1'", Assert.assertThrows(ApplicationQueryException.class, () -> store.deleteQuery("1", currentUser)).getMessage());
    }

    @Test
    public void testAllowDeleteQueryWithoutOwner() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", null).withLegacyExecutionContext().build(), null);
        store.deleteQuery("1", currentUser);
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
    }

    @Test
    public void testForbidDeleteQueryOfAnotherUser() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals("Only owner can delete the query", Assert.assertThrows(ApplicationQueryException.class, () -> store.deleteQuery("1", "testUser2")).getMessage());
    }

    @Test
    public void testCreateSimpleQueryContainsTimestamps() throws Exception
    {
        String currentUser = "testUser";
        Query newQuery = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query createdQuery = store.createQuery(newQuery, currentUser);
        Assert.assertNotNull(createdQuery.lastUpdatedAt);
        Assert.assertNotNull(createdQuery.createdAt);
    }

    @Test
    public void testSearchQueriesContainTimestamps() throws Exception
    {
        String currentUser = "testUser";
        Query newQuery = TestQueryBuilder.create("1", "query1", currentUser).withGroupId("test.group").withArtifactId("test-artifact").withLegacyExecutionContext().build();
        store.createQuery(newQuery, currentUser);
        List<Query> queries = store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser);
        Assert.assertEquals(1, queries.size());
        Query lightQuery = queries.get(0);
        Assert.assertNotNull(lightQuery.lastUpdatedAt);
        Assert.assertNotNull(lightQuery.createdAt);
    }

    @Test
    public void testSearchQueriesWithSearchByQueryId() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("26929514-237c-11ed-861d-0242ac120002", "query_a", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("26929515-237c-11bd-851d-0243ac120002", "query_b", currentUser).withLegacyExecutionContext().build(), currentUser);
        store.createQuery(TestQueryBuilder.create("23929515-235c-11ad-851d-0143ac120002", "query_c", currentUser).withLegacyExecutionContext().build(), currentUser);
        Assert.assertEquals(3, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("23929515-235c-11ad-851d-0143ac120002").build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withSearchTerm("23929515-235c-11ad").build(), currentUser).size());
    }

    @Test
    public void testSearchQueriesSortedByCurrentUserFirst() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", "testUser1").withLegacyExecutionContext().build(), "testUser1");
        store.createQuery(TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build(), currentUser);
        List<Query> queries = store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser);
        Assert.assertEquals(2, queries.size());
        Assert.assertEquals(currentUser, queries.get(0).owner);
    }

    @Test
    public void testSearchQueriesWithCombineTaggedValuesCondition() throws Exception
    {
        String currentUser = "testUser";
        TaggedValue taggedValue1 = createTestTaggedValue("profile1", "tag1", "value1");
        TaggedValue taggedValue2 = createTestTaggedValue("profile2", "tag2", "value2");
        TaggedValue taggedValue3 = createTestTaggedValue("profile3", "tag3", "value3");
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue1)).withLegacyExecutionContext().build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue2)).withLegacyExecutionContext().build();
        Query testQuery4 = TestQueryBuilder.create("4", "query3", currentUser).withTaggedValues(Lists.fixedSize.of(taggedValue1, taggedValue2)).withLegacyExecutionContext().build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);
        store.createQuery(testQuery3, currentUser);
        store.createQuery(testQuery4, currentUser);

        // When no tagged value provided, return all queries
        Assert.assertEquals(4, store.searchQueries(new TestQuerySearchSpecificationBuilder().build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue3)).withCombineTaggedValuesCondition(true).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue1)).withCombineTaggedValuesCondition(true).build(), currentUser).size());
        Assert.assertEquals(2, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue2)).withCombineTaggedValuesCondition(true).build(), currentUser).size());
        Assert.assertEquals(1, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue1, taggedValue2)).withCombineTaggedValuesCondition(true).build(), currentUser).size());
        Assert.assertEquals(0, store.searchQueries(new TestQuerySearchSpecificationBuilder().withTaggedValues(Lists.fixedSize.of(taggedValue1, taggedValue2, taggedValue3)).withCombineTaggedValuesCondition(true).build(), currentUser).size());
    }

    @Test
    public void testGetQueries() throws Exception
    {
        String currentUser = "testUser";
        Query testQuery1 = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        Query testQuery2 = TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build();
        Query testQuery3 = TestQueryBuilder.create("3", "query3", currentUser).withLegacyExecutionContext().build();
        store.createQuery(testQuery1, currentUser);
        store.createQuery(testQuery2, currentUser);
        store.createQuery(testQuery3, currentUser);

        Assert.assertEquals(1, store.getQueries(Lists.fixedSize.of("2")).size());
        Assert.assertEquals(1, store.getQueries(Lists.fixedSize.of("3")).size());
        Assert.assertEquals(2, store.getQueries(Lists.fixedSize.of("2", "3")).size());

        Assert.assertEquals("Can't find queries for the following ID(s):\\n4", Assert.assertThrows(ApplicationQueryException.class, () -> store.getQueries(Lists.fixedSize.of("4"))).getMessage());
        Assert.assertEquals("Can't find queries for the following ID(s):\\n4\\n6", Assert.assertThrows(ApplicationQueryException.class, () -> store.getQueries(Lists.fixedSize.of("4", "3", "6"))).getMessage());

        Assert.assertEquals("Can't fetch more than 50 queries", Assert.assertThrows(ApplicationQueryException.class, () -> store.getQueries(Lists.fixedSize.ofAll(Collections.nCopies(51, "5")))).getMessage());
    }

    @Test
    public void testCreateQueryEvent() throws Exception
    {
        String currentUser = "testUser";
        store.createQuery(TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build(), currentUser);
        List<QueryEvent> events = store.getQueryEvents(null, null, null, null, null);
        Assert.assertEquals(1, events.size());
        QueryEvent event = events.get(0);
        Assert.assertEquals("1", event.queryId);
        Assert.assertEquals(QueryEvent.QueryEventType.CREATED, event.eventType);
    }

    @Test
    public void testUpdateQueryEvent() throws Exception
    {
        String currentUser = "testUser";
        Query query = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        store.createQuery(query, currentUser);
        store.updateQuery(query.id, query, currentUser);
        List<QueryEvent> events = store.getQueryEvents(null, null, null, null, null);
        Assert.assertEquals(2, events.size());
        QueryEvent event = events.get(1);
        Assert.assertEquals("1", event.queryId);
        Assert.assertEquals(QueryEvent.QueryEventType.UPDATED, event.eventType);
    }

    @Test
    public void testDeleteQueryEvent() throws Exception
    {
        String currentUser = "testUser";
        Query query = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();
        store.createQuery(query, currentUser);
        store.deleteQuery(query.id, currentUser);
        List<QueryEvent> events = store.getQueryEvents(null, null, null, null, null);
        Assert.assertEquals(2, events.size());
        QueryEvent event = events.get(1);
        Assert.assertEquals("1", event.queryId);
        Assert.assertEquals(QueryEvent.QueryEventType.DELETED, event.eventType);
    }

    @Test
    public void testGetQueryEvents() throws Exception
    {
        String currentUser = "testUser";
        Query query1 = TestQueryBuilder.create("1", "query1", currentUser).withLegacyExecutionContext().build();

        // NOTE: for this test to work well, we need leave a tiny window of time (10 ms) between each operation
        // so the test for filter using timestamp can be correct
        store.createQuery(query1, currentUser);
        Thread.sleep(10);
        store.updateQuery(query1.id, query1, currentUser);
        Thread.sleep(10);
        store.deleteQuery(query1.id, currentUser);
        Thread.sleep(10);
        Query query2 = TestQueryBuilder.create("2", "query2", currentUser).withLegacyExecutionContext().build();
        store.createQuery(query2, currentUser);
        Thread.sleep(10);
        store.updateQuery(query2.id, query2, currentUser);
        Thread.sleep(10);
        store.deleteQuery(query2.id, currentUser);
        Thread.sleep(10);

        Assert.assertEquals(6, store.getQueryEvents(null, null, null, null, null).size());

        // Query ID
        Assert.assertEquals(3, store.getQueryEvents("1", null, null, null, null).size());
        Assert.assertEquals(3, store.getQueryEvents("2", null, null, null, null).size());

        // Event Type
        Assert.assertEquals(2, store.getQueryEvents(null, QueryEvent.QueryEventType.CREATED, null, null, null).size());
        Assert.assertEquals(2, store.getQueryEvents(null, QueryEvent.QueryEventType.UPDATED, null, null, null).size());
        Assert.assertEquals(2, store.getQueryEvents(null, QueryEvent.QueryEventType.DELETED, null, null, null).size());

        // Limit
        Assert.assertEquals(1, store.getQueryEvents(null, null, null, null, 1).size());
        Assert.assertEquals(5, store.getQueryEvents(null, null, null, null, 5).size());

        Long now = Instant.now().toEpochMilli();
        Assert.assertEquals(0, store.getQueryEvents(null, null, now, null, null).size());
        Assert.assertEquals(6, store.getQueryEvents(null, null, null, now, null).size());

        QueryEvent event1 = store.getQueryEvents("1", QueryEvent.QueryEventType.DELETED, null, null, null).get(0);
        Assert.assertEquals(4, store.getQueryEvents(null, null, event1.timestamp, null, null).size());
        Assert.assertEquals(3, store.getQueryEvents(null, null, null, event1.timestamp, null).size());

        QueryEvent event2 = store.getQueryEvents("2", QueryEvent.QueryEventType.CREATED, null, null, null).get(0);
        Assert.assertEquals(3, store.getQueryEvents(null, null, event2.timestamp, null, null).size());
        Assert.assertEquals(4, store.getQueryEvents(null, null, null, event2.timestamp, null).size());
    }
}
