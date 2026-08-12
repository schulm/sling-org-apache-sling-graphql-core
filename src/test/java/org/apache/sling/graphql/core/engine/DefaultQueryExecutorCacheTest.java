/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.graphql.core.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import graphql.schema.GraphQLSchema;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.graphql.core.hash.SHA256Hasher;
import org.apache.sling.graphql.core.scalars.SlingScalarsProvider;
import org.apache.sling.graphql.core.schema.RankedSchemaProviders;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class DefaultQueryExecutorCacheTest {

    @Mock
    private RankedSchemaProviders schemaProvider;

    @Mock
    private SlingDataFetcherSelector dataFetcherSelector;

    @Mock
    private SlingTypeResolverSelector typeResolverSelector;

    @Mock
    private SlingScalarsProvider scalarsProvider;

    @Mock
    private Resource resource;

    @InjectMocks
    private DefaultQueryExecutor executor;

    @Before
    public void setUp() {
        activate(10, false);
        when(resource.getPath()).thenReturn("/content/test");
        when(scalarsProvider.getCustomScalars(any())).thenReturn(Collections.emptyList());
    }

    private void activate(int schemaCacheSize, boolean executableSchemaCacheEnabled) {
        DefaultQueryExecutor.Config config = mock(DefaultQueryExecutor.Config.class);
        when(config.schemaCacheSize()).thenReturn(schemaCacheSize);
        when(config.executableSchemaCacheEnabled()).thenReturn(executableSchemaCacheEnabled);
        when(config.maxQueryTokens()).thenReturn(15000);
        when(config.maxWhitespaceTokens()).thenReturn(200000);
        when(config.maxFieldCount()).thenReturn(100000);
        executor.activate(config);
    }

    @Test
    public void testGetTypeDefinitionRegistry_ValidSDL() {
        String validSDL = "type Query { hello: String }";
        String[] selectors = {"test"};

        TypeDefinitionRegistry result = executor.getTypeDefinitionRegistry(validSDL, resource, selectors);

        assertNotNull(result);
        assertTrue(result.getType("Query").isPresent());
    }

    @Test
    public void testGetTypeDefinitionRegistry_InvalidSDL() {
        String invalidSDL = "invalid graphql syntax {";
        String[] selectors = {"test"};

        TypeDefinitionRegistry result = executor.getTypeDefinitionRegistry(invalidSDL, resource, selectors);

        assertNull(result);
    }

    @Test
    public void testGetTypeDefinitionRegistry_CacheDisabled() {
        activate(0, false);

        String sdl = "type Query { hello: String }";
        String[] selectors = {"test"};

        TypeDefinitionRegistry result = executor.getTypeDefinitionRegistry(sdl, resource, selectors);

        assertNotNull(result);
    }

    @Test
    public void testGetTypeDefinitionRegistry_Extended() {
        Resource resource2 = mock(Resource.class);
        when(resource2.getPath()).thenReturn("/content/test2");

        activate(2, false);

        String sdl = "type Query { hello: String }";
        String[] selectors = {"test"};

        executor.getTypeDefinitionRegistry(sdl, resource2, selectors);
        executor.getTypeDefinitionRegistry(sdl + " ", resource, selectors);
        executor.getTypeDefinitionRegistry(sdl + "  ", resource, selectors);
        TypeDefinitionRegistry result = executor.getTypeDefinitionRegistry(sdl, resource2, selectors);
        assertNotNull(result);
    }

    @Test
    public void testExecutableSchemaCache_ReusesSameInstance() {
        activate(10, true);
        String sdl = "type Query { hello: String }";
        TypeDefinitionRegistry registry = executor.getTypeDefinitionRegistry(sdl, resource, new String[] {"test"});
        String hash = SHA256Hasher.getHash(sdl);

        GraphQLSchema first = executor.getExecutableSchema(hash, registry);
        GraphQLSchema second = executor.getExecutableSchema(hash, registry);

        assertNotNull(first);
        assertSame(first, second);
    }

    @Test
    public void testExecutableSchemaCache_DisabledBuildsIndependently() {
        activate(10, false);
        String sdl = "type Query { hello: String }";
        TypeDefinitionRegistry registry = executor.getTypeDefinitionRegistry(sdl, resource, new String[] {"test"});
        String hash = SHA256Hasher.getHash(sdl);

        GraphQLSchema first = executor.getExecutableSchema(hash, registry);
        GraphQLSchema second = executor.getExecutableSchema(hash, registry);

        assertNotNull(first);
        assertNotNull(second);
        // Without the cache each call creates a new GraphQLSchema instance
        assertTrue(first != second);
    }

    @Test
    public void testExecutableSchemaCache_SingleFlightUnderContention() throws Exception {
        activate(10, true);
        String sdl = "type Query { hello: String }";
        TypeDefinitionRegistry registry = executor.getTypeDefinitionRegistry(sdl, resource, new String[] {"test"});
        String hash = SHA256Hasher.getHash(sdl);

        final AtomicInteger buildCalls = new AtomicInteger();
        final CountDownLatch started = new CountDownLatch(1);
        final CyclicBarrier barrier = new CyclicBarrier(8, started::countDown);

        // Wrap scalars lookup to observe how often buildSchema runs
        when(scalarsProvider.getCustomScalars(any())).thenAnswer((Answer<Iterable>) invocation -> {
            buildCalls.incrementAndGet();
            // Hold the winner briefly so waiters join the in-flight Future
            Thread.sleep(50);
            return Collections.emptyList();
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<GraphQLSchema>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    return executor.getExecutableSchema(hash, registry);
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));

            GraphQLSchema first = null;
            for (Future<GraphQLSchema> future : futures) {
                GraphQLSchema schema = future.get(10, TimeUnit.SECONDS);
                assertNotNull(schema);
                if (first == null) {
                    first = schema;
                } else {
                    assertSame(first, schema);
                }
            }
            assertTrue(
                    "Expected a single schema build under contention, got " + buildCalls.get(), buildCalls.get() == 1);
        } finally {
            pool.shutdownNow();
        }
    }
}
