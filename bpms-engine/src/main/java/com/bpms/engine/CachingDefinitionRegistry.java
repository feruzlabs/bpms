package com.bpms.engine;

import com.bpms.core.definition.ProcessDefinition;
import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import com.bpms.spi.parse.ProcessDefinitionParser;
import com.bpms.spi.port.DefinitionRegistry;
import com.bpms.spi.port.DefinitionRepositoryPort;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Caffeine LRU cache: XML is canonical; parse on miss without holding a cache lock during parse
 * (getIfPresent → parse → asMap().putIfAbsent).
 */
public final class CachingDefinitionRegistry implements DefinitionRegistry {

    public static final int DEFAULT_MAXIMUM_SIZE = 500;
    public static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofHours(6);

    private final Cache<String, ProcessDefinition> cache;
    private final DefinitionRepositoryPort definitions;
    private final ProcessDefinitionParser parser;

    public CachingDefinitionRegistry(DefinitionRepositoryPort definitions, ProcessDefinitionParser parser) {
        this(definitions, parser, DEFAULT_MAXIMUM_SIZE, DEFAULT_EXPIRE_AFTER_ACCESS);
    }

    public CachingDefinitionRegistry(
            DefinitionRepositoryPort definitions,
            ProcessDefinitionParser parser,
            long maximumSize,
            Duration expireAfterAccess
    ) {
        this.definitions = Objects.requireNonNull(definitions);
        this.parser = Objects.requireNonNull(parser);
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterAccess(Objects.requireNonNull(expireAfterAccess))
                .build();
    }

    @Override
    public ProcessDefinition get(String definitionId) {
        ProcessDefinition cached = cache.getIfPresent(definitionId);
        if (cached != null) {
            return cached;
        }
        DefinitionRecord record = definitions.findDefinitionById(definitionId)
                .orElseThrow(() -> new NoSuchElementException("Definition not found: " + definitionId));
        ProcessDefinition parsed = parser.parse(record.bpmnXml().getBytes(StandardCharsets.UTF_8)).definition();
        ProcessDefinition winner = cache.asMap().putIfAbsent(definitionId, parsed);
        return winner != null ? winner : parsed;
    }

    @Override
    public void warm(String definitionId, ProcessDefinition definition) {
        cache.asMap().putIfAbsent(definitionId, Objects.requireNonNull(definition));
    }

    /** Test helper: empty cache (simulates JVM restart / eviction). */
    void clear() {
        cache.invalidateAll();
    }

    long size() {
        cache.cleanUp();
        return cache.estimatedSize();
    }
}
