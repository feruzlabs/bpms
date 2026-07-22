package com.bpms.engine;

import com.bpms.core.definition.ParseResult;
import com.bpms.core.definition.ProcessDefinition;
import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import com.bpms.spi.parse.ProcessDefinitionParser;
import com.bpms.spi.parse.SourceFormat;
import com.bpms.spi.port.DefinitionRepositoryPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingDefinitionRegistryTest {

    @Test
    void parseOnceOnRepeatedGet() {
        CountingParser parser = new CountingParser(stub("p1"));
        InMemoryDefs defs = new InMemoryDefs();
        defs.put(record("def-1", "<xml/>"));
        CachingDefinitionRegistry registry = new CachingDefinitionRegistry(defs, parser);

        ProcessDefinition first = registry.get("def-1");
        ProcessDefinition second = registry.get("def-1");
        ProcessDefinition third = registry.get("def-1");

        assertSame(first, second);
        assertSame(first, third);
        assertEquals(1, parser.calls.get());
    }

    @Test
    void warmAvoidsParseOnGet() {
        CountingParser parser = new CountingParser(stub("warmed"));
        InMemoryDefs defs = new InMemoryDefs();
        defs.put(record("def-w", "<xml/>"));
        CachingDefinitionRegistry registry = new CachingDefinitionRegistry(defs, parser);
        ProcessDefinition model = stub("warmed");
        registry.warm("def-w", model);

        assertSame(model, registry.get("def-w"));
        assertEquals(0, parser.calls.get());
    }

    @Test
    void cacheMissAfterClearParsesAgain() {
        CountingParser parser = new CountingParser(stub("p1"));
        InMemoryDefs defs = new InMemoryDefs();
        defs.put(record("def-1", "<xml/>"));
        CachingDefinitionRegistry registry = new CachingDefinitionRegistry(defs, parser);

        registry.get("def-1");
        registry.clear(); // restart simulation
        registry.get("def-1");
        registry.get("def-1");

        assertEquals(2, parser.calls.get());
        assertEquals(1L, registry.size());
    }

    @Test
    void concurrentGetIsThreadSafe() throws Exception {
        CountingParser parser = new CountingParser(stub("shared"));
        parser.delayMs = 30;
        InMemoryDefs defs = new InMemoryDefs();
        defs.put(record("def-c", "<xml/>"));
        CachingDefinitionRegistry registry = new CachingDefinitionRegistry(defs, parser);

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return registry.get("def-c");
                    }))
                    .toList();
            start.countDown();
            ProcessDefinition first = futures.getFirst().get(5, TimeUnit.SECONDS);
            for (var f : futures) {
                assertSame(first, f.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        // Races may parse more than once before putIfAbsent wins — never zero, bounded.
        assertTrue(parser.calls.get() >= 1 && parser.calls.get() <= threads);
        assertEquals(1L, registry.size());
    }

    @Test
    void evictionCausesReparse() {
        CountingParser parser = new CountingParser(stub("shared"));
        InMemoryDefs defs = new InMemoryDefs();
        defs.put(record("a", "<a/>"));
        defs.put(record("b", "<b/>"));
        CachingDefinitionRegistry registry = new CachingDefinitionRegistry(
                defs, parser, 1, java.time.Duration.ofHours(1));

        registry.get("a");
        registry.get("b");
        assertEquals(1L, registry.size());
        registry.get("a"); // miss after LRU eviction of "a"

        assertEquals(3, parser.calls.get());
    }

    private static ProcessDefinition stub(String processId) {
        return new ProcessDefinition(
                processId, processId, processId, List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    private static DefinitionRecord record(String id, String xml) {
        return new DefinitionRecord(id, "key", "n", 1, "CAMUNDA", xml, Instant.parse("2024-01-01T00:00:00Z"));
    }

    static final class CountingParser implements ProcessDefinitionParser {
        final AtomicInteger calls = new AtomicInteger();
        final ProcessDefinition model;
        volatile long delayMs;

        CountingParser(ProcessDefinition model) {
            this.model = model;
        }

        @Override
        public boolean supports(SourceFormat format) {
            return true;
        }

        @Override
        public ParseResult parse(byte[] source) {
            calls.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ParseResult(model, List.of());
        }
    }

    static final class InMemoryDefs implements DefinitionRepositoryPort {
        final Map<String, DefinitionRecord> byId = new ConcurrentHashMap<>();

        void put(DefinitionRecord d) {
            byId.put(d.id(), d);
        }

        @Override
        public DefinitionRecord save(DefinitionRecord definition) {
            byId.put(definition.id(), definition);
            return definition;
        }

        @Override
        public Optional<DefinitionRecord> findDefinitionById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<DefinitionRecord> findLatestByKey(String key) {
            return byId.values().stream().filter(d -> d.key().equals(key) && d.isLatest()).findFirst()
                    .or(() -> byId.values().stream().filter(d -> d.key().equals(key)).findFirst());
        }

        @Override
        public Optional<DefinitionRecord> findByKeyAndVersion(String key, int version) {
            return byId.values().stream()
                    .filter(d -> d.key().equals(key) && d.version() == version)
                    .findFirst();
        }

        @Override
        public List<DefinitionRecord> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public List<com.bpms.spi.engine.RuntimeModels.DefinitionVersionView> findVersionsByKey(String key) {
            return byId.values().stream()
                    .filter(d -> d.key().equals(key))
                    .map(d -> new com.bpms.spi.engine.RuntimeModels.DefinitionVersionView(
                            d.id(), d.key(), d.name(), d.version(), d.isLatest(), d.checksum(),
                            d.createdAt(), "ACTIVE", 0L))
                    .toList();
        }

        @Override
        public int nextVersion(String key) {
            return 1;
        }
    }
}
