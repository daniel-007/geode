/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.cache.lucene.test;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.geode.cache.AttributesFactory;
import org.apache.geode.cache.Cache;
import org.apache.geode.cache.EntryOperation;
import org.apache.geode.cache.FixedPartitionAttributes;
import org.apache.geode.cache.FixedPartitionResolver;
import org.apache.geode.cache.PartitionAttributesFactory;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.asyncqueue.AsyncEventQueue;
import org.apache.geode.cache.asyncqueue.internal.AsyncEventQueueImpl;
import org.apache.geode.cache.lucene.LuceneIndex;
import org.apache.geode.cache.lucene.LuceneIndexFactory;
import org.apache.geode.cache.lucene.LuceneQuery;
import org.apache.geode.cache.lucene.LuceneQueryException;
import org.apache.geode.cache.lucene.PageableLuceneQueryResults;
import org.apache.geode.cache.lucene.LuceneService;
import org.apache.geode.cache.lucene.LuceneServiceProvider;
import org.apache.geode.cache.lucene.internal.LuceneIndexForPartitionedRegion;
import org.apache.geode.cache.lucene.internal.LuceneServiceImpl;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.wan.AbstractGatewaySender;
import org.apache.geode.test.dunit.VM;

public class LuceneTestUtilities {
  public static final String INDEX_NAME = "index";
  public static final String REGION_NAME = "region";
  public static final String DEFAULT_FIELD = "text";

  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_FIELDS =
      "Cannot create Lucene index index on region /region with fields [field1, field2] because another member defines the same index with fields [field1].";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_FIELDS_2 =
      "Cannot create Lucene index index on region /region with fields [field1] because another member defines the same index with fields [field1, field2].";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_ANALYZERS =
      "Cannot create Lucene index index on region /region with analyzer StandardAnalyzer on field field2 because another member defines the same index with analyzer KeywordAnalyzer on that field.";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_ANALYZERS_1 =
      "Cannot create Lucene index index on region /region with analyzer StandardAnalyzer on field field1 because another member defines the same index with analyzer KeywordAnalyzer on that field.";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_ANALYZERS_2 =
      "Cannot create Lucene index index on region /region with analyzer KeywordAnalyzer on field field1 because another member defines the same index with analyzer StandardAnalyzer on that field.";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_ANALYZERS_3 =
      "Cannot create Lucene index index on region /region with analyzer KeywordAnalyzer on field field2 because another member defines the same index with analyzer StandardAnalyzer on that field.";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_NAMES =
      "Cannot create Region /region with [index2#_region] async event ids because another cache has the same region defined with [index1#_region] async event ids";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_INDEXES_1 =
      "Cannot create Region /region with [] async event ids because another cache has the same region defined with [index#_region] async event ids";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_INDEXES_2 =
      "Cannot create Region /region with [index#_region, index2#_region] async event ids because another cache has the same region defined with [index#_region] async event ids";
  public static final String CANNOT_CREATE_LUCENE_INDEX_DIFFERENT_INDEXES_3 =
      "Cannot create Region /region with [index#_region] async event ids because another cache has the same region defined with [] async event ids";

  public static String Quarter1 = "Q1";
  public static String Quarter2 = "Q2";
  public static String Quarter3 = "Q3";
  public static String Quarter4 = "Q4";

  public static Region initDataStoreForFixedPR(final Cache cache) throws Exception {
    List<FixedPartitionAttributes> fpaList = new ArrayList<FixedPartitionAttributes>();
    int vmNum = VM.getCurrentVMNum();
    if (vmNum % 2 == 0) {
      FixedPartitionAttributes fpa1 = FixedPartitionAttributes.createFixedPartition(Quarter1, true);
      FixedPartitionAttributes fpa2 =
          FixedPartitionAttributes.createFixedPartition(Quarter2, false);
      fpaList.clear();
      fpaList.add(fpa1);
      fpaList.add(fpa2);
    } else {
      FixedPartitionAttributes fpa1 =
          FixedPartitionAttributes.createFixedPartition(Quarter1, false);
      FixedPartitionAttributes fpa2 = FixedPartitionAttributes.createFixedPartition(Quarter2, true);
      fpaList.clear();
      fpaList.add(fpa1);
      fpaList.add(fpa2);
    }

    return createFixedPartitionedRegion(cache, REGION_NAME, fpaList, 40);
  }

  public static Region createFixedPartitionedRegion(final Cache cache, String regionName,
      List<FixedPartitionAttributes> fpaList, int localMaxMemory) {
    List<String> allPartitions = new ArrayList();
    if (fpaList != null) {
      for (FixedPartitionAttributes fpa : fpaList) {
        allPartitions.add(fpa.getPartitionName());
      }
    } else {
      allPartitions.add("Q1");
      allPartitions.add("Q2");
    }

    AttributesFactory fact = new AttributesFactory();

    PartitionAttributesFactory pfact = new PartitionAttributesFactory();
    pfact.setTotalNumBuckets(16);
    pfact.setRedundantCopies(1);
    pfact.setLocalMaxMemory(localMaxMemory);
    if (fpaList != null) {
      for (FixedPartitionAttributes fpa : fpaList) {
        pfact.addFixedPartitionAttributes(fpa);
      }
    }
    pfact.setPartitionResolver(new MyFixedPartitionResolver(allPartitions));
    fact.setPartitionAttributes(pfact.create());
    Region r = cache.createRegionFactory(fact.create()).create(regionName);
    assertNotNull(r);
    return r;
  }

  static class MyFixedPartitionResolver implements FixedPartitionResolver {

    private final List<String> allPartitions;

    public MyFixedPartitionResolver(final List<String> allPartitions) {
      this.allPartitions = allPartitions;
    }

    @Override
    public String getPartitionName(final EntryOperation opDetails,
        @Deprecated final Set targetPartitions) {
      int hash = Math.abs(opDetails.getKey().hashCode() % allPartitions.size());
      return allPartitions.get(hash);
    }

    @Override
    public Object getRoutingObject(final EntryOperation opDetails) {
      return opDetails.getKey();
    }

    @Override
    public String getName() {
      return getClass().getName();
    }

    @Override
    public void close() {

    }

  }

  public static void verifyInternalRegions(LuceneService luceneService, Cache cache,
      Consumer<LocalRegion> verify) {
    // Get index
    LuceneIndexForPartitionedRegion index =
        (LuceneIndexForPartitionedRegion) luceneService.getIndex(INDEX_NAME, REGION_NAME);

    LocalRegion fileRegion = (LocalRegion) cache.getRegion(index.createFileRegionName());
    verify.accept(fileRegion);
  }

  public static AsyncEventQueue getIndexQueue(Cache cache) {
    String aeqId = LuceneServiceImpl.getUniqueIndexName(INDEX_NAME, REGION_NAME);
    return cache.getAsyncEventQueue(aeqId);
  }

  public static void createIndex(Cache cache, String... fieldNames) {
    final LuceneIndexFactory indexFactory = LuceneServiceProvider.get(cache).createIndexFactory();
    indexFactory.setFields(fieldNames).create(INDEX_NAME, REGION_NAME);
  }

  public static void verifyIndexFinishFlushing(Cache cache, String indexName, String regionName)
      throws InterruptedException {
    LuceneService luceneService = LuceneServiceProvider.get(cache);
    LuceneIndex index = luceneService.getIndex(indexName, regionName);
    boolean flushed =
        luceneService.waitUntilFlushed(indexName, regionName, 60000, TimeUnit.MILLISECONDS);
    assertTrue(flushed);
  }

  /**
   * Verify that a query returns the expected list of keys. Ordering is ignored.
   */
  public static <K> void verifyQueryKeys(LuceneQuery<K, Object> query, K... expectedKeys)
      throws LuceneQueryException {
    Set<K> expectedKeySet = new HashSet<>(Arrays.asList(expectedKeys));
    Set<K> actualKeySet = new HashSet<>(query.findKeys());
    assertEquals(expectedKeySet, actualKeySet);
  }

  /**
   * Verify that a query returns the expected map of key-value. Ordering is ignored.
   */
  public static <K> void verifyQueryKeyAndValues(LuceneQuery<K, Object> query,
      HashMap expectedResults) throws LuceneQueryException {
    HashMap actualResults = new HashMap<>();
    final PageableLuceneQueryResults<K, Object> results = query.findPages();
    while (results.hasNext()) {
      results.next().stream().forEach(struct -> {
        Object value = struct.getValue();
        actualResults.put(struct.getKey(), value);
      });
    }
    assertEquals(expectedResults, actualResults);
  }

  public static void pauseSender(final Cache cache) {
    final AsyncEventQueueImpl queue = (AsyncEventQueueImpl) getIndexQueue(cache);
    if (queue == null) {
      return;
    }
    queue.getSender().pause();

    AbstractGatewaySender sender = (AbstractGatewaySender) queue.getSender();
    sender.getEventProcessor().waitForDispatcherToPause();
  }

  public static void resumeSender(final Cache cache) {
    final AsyncEventQueueImpl queue = (AsyncEventQueueImpl) getIndexQueue(cache);
    if (queue == null) {
      return;
    }
    queue.getSender().resume();
  }
}
