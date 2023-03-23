/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene;

import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.Randomness;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.LongVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.CannedSourceOperator;
import org.elasticsearch.compute.operator.Driver;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.compute.operator.OperatorTestCase;
import org.elasticsearch.compute.operator.PageConsumerOperator;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.IndexFieldDataCache;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NumberFieldMapper;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.aggregations.support.FieldContext;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceType;
import org.junit.After;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class ValuesSourceReaderOperatorTests extends OperatorTestCase {
    private static final String[] PREFIX = new String[] { "a", "b", "c" };

    private Directory directory = newDirectory();
    private IndexReader reader;

    @After
    public void closeIndex() throws IOException {
        IOUtils.close(reader, directory);
    }

    @Override
    protected Operator.OperatorFactory simple(BigArrays bigArrays) {
        return factory(
            CoreValuesSourceType.NUMERIC,
            ElementType.LONG,
            new NumberFieldMapper.NumberFieldType("long", NumberFieldMapper.NumberType.LONG)
        );
    }

    private Operator.OperatorFactory factory(ValuesSourceType vsType, ElementType elementType, MappedFieldType ft) {
        IndexFieldData<?> fd = ft.fielddataBuilder(FieldDataContext.noRuntimeFields("test"))
            .build(new IndexFieldDataCache.None(), new NoneCircuitBreakerService());
        FieldContext fc = new FieldContext(ft.name(), fd, ft);
        ValuesSource vs = vsType.getField(fc, null);
        return new ValuesSourceReaderOperator.ValuesSourceReaderOperatorFactory(
            List.of(new ValueSourceInfo(vsType, vs, elementType, reader)),
            0,
            ft.name()
        );
    }

    @Override
    protected SourceOperator simpleInput(int size) {
        // The test wants more than one segment. We shoot for about 10.
        int commitEvery = Math.max(1, size / 10);
        try (
            RandomIndexWriter writer = new RandomIndexWriter(
                random(),
                directory,
                newIndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE)
            )
        ) {
            for (int d = 0; d < size; d++) {
                List<IndexableField> doc = new ArrayList<>();
                doc.add(new SortedNumericDocValuesField("key", d));
                doc.add(new SortedNumericDocValuesField("long", d));
                doc.add(
                    new KeywordFieldMapper.KeywordField("kwd", new BytesRef(Integer.toString(d)), KeywordFieldMapper.Defaults.FIELD_TYPE)
                );
                for (int v = 0; v <= d % 3; v++) {
                    doc.add(
                        new KeywordFieldMapper.KeywordField("mv_kwd", new BytesRef(PREFIX[v] + d), KeywordFieldMapper.Defaults.FIELD_TYPE)
                    );
                }
                writer.addDocument(doc);
                if (d % commitEvery == 0) {
                    writer.commit();
                }
            }
            reader = writer.getReader();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new LuceneSourceOperator(reader, 0, new MatchAllDocsQuery());
    }

    @Override
    protected String expectedDescriptionOfSimple() {
        return "ValuesSourceReaderOperator(field = long)";
    }

    @Override
    protected String expectedToStringOfSimple() {
        return "ValuesSourceReaderOperator";
    }

    @Override
    protected void assertSimpleOutput(List<Page> input, List<Page> results) {
        long expectedSum = 0;
        long current = 0;

        long sum = 0;
        for (Page r : results) {
            LongBlock b = r.getBlock(r.getBlockCount() - 1);
            for (int p = 0; p < b.getPositionCount(); p++) {
                expectedSum += current;
                current++;
                sum += b.getLong(p);
            }
        }

        assertThat(sum, equalTo(expectedSum));
    }

    @Override
    protected ByteSizeValue smallEnoughToCircuitBreak() {
        assumeTrue("doesn't use big arrays so can't break", false);
        return null;
    }

    public void testLoadAll() {
        loadSimpleAndAssert(CannedSourceOperator.collectPages(simpleInput(between(1_000, 10 * LuceneSourceOperator.PAGE_SIZE))));
    }

    public void testLoadAllInOnePage() {
        assumeFalse("filter blocks don't support multivalued fields yet", true);
        loadSimpleAndAssert(
            List.of(
                CannedSourceOperator.mergePages(
                    CannedSourceOperator.collectPages(simpleInput(between(1_000, 10 * LuceneSourceOperator.PAGE_SIZE)))
                )
            )
        );
    }

    public void testLoadAllInOnePageShuffled() {
        assumeFalse("filter blocks don't support multivalued fields yet", true);
        Page source = CannedSourceOperator.mergePages(
            CannedSourceOperator.collectPages(simpleInput(between(1_000, 10 * LuceneSourceOperator.PAGE_SIZE)))
        );
        List<Integer> shuffleList = new ArrayList<>();
        IntStream.range(0, source.getPositionCount()).forEach(i -> shuffleList.add(i));
        Randomness.shuffle(shuffleList);
        int[] shuffleArray = shuffleList.stream().mapToInt(Integer::intValue).toArray();
        Block[] shuffledBlocks = new Block[source.getBlockCount()];
        for (int b = 0; b < shuffledBlocks.length; b++) {
            shuffledBlocks[b] = source.getBlock(b).filter(shuffleArray);
        }
        source = new Page(shuffledBlocks);
        loadSimpleAndAssert(List.of(source));
    }

    private void loadSimpleAndAssert(List<Page> input) {
        List<Page> results = new ArrayList<>();
        try (
            Driver d = new Driver(
                new CannedSourceOperator(input.iterator()),
                List.of(
                    factory(
                        CoreValuesSourceType.NUMERIC,
                        ElementType.INT,
                        new NumberFieldMapper.NumberFieldType("key", NumberFieldMapper.NumberType.LONG)
                    ).get(),
                    factory(
                        CoreValuesSourceType.NUMERIC,
                        ElementType.LONG,
                        new NumberFieldMapper.NumberFieldType("long", NumberFieldMapper.NumberType.LONG)
                    ).get(),
                    factory(CoreValuesSourceType.KEYWORD, ElementType.BYTES_REF, new KeywordFieldMapper.KeywordFieldType("kwd")).get(),
                    factory(CoreValuesSourceType.KEYWORD, ElementType.BYTES_REF, new KeywordFieldMapper.KeywordFieldType("mv_kwd")).get()
                ),
                new PageConsumerOperator(page -> results.add(page)),
                () -> {}
            )
        ) {
            d.run();
        }
        assertThat(results, hasSize(input.size()));
        for (Page p : results) {
            assertThat(p.getBlockCount(), equalTo(5));
            IntVector keys = p.<IntBlock>getBlock(1).asVector();
            LongVector longs = p.<LongBlock>getBlock(2).asVector();
            BytesRefVector keywords = p.<BytesRefBlock>getBlock(3).asVector();
            BytesRefBlock mvKeywords = p.getBlock(4);
            for (int i = 0; i < p.getPositionCount(); i++) {
                int key = keys.getInt(i);
                assertThat(longs.getLong(i), equalTo((long) key));
                assertThat(keywords.getBytesRef(i, new BytesRef()).utf8ToString(), equalTo(Integer.toString(key)));

                assertThat(mvKeywords.getValueCount(i), equalTo(key % 3 + 1));
                int offset = mvKeywords.getFirstValueIndex(i);
                for (int v = 0; v <= key % 3; v++) {
                    assertThat(mvKeywords.getBytesRef(offset + v, new BytesRef()).utf8ToString(), equalTo(PREFIX[v] + key));
                }
            }
        }
    }
}
