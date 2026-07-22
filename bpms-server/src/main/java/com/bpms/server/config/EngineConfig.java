package com.bpms.server.config;

import com.bpms.core.definition.ProcessDefinition;
import com.bpms.engine.CachingDefinitionRegistry;
import com.bpms.engine.ConnectorRegistry;
import com.bpms.engine.ExecutionEngine;
import com.bpms.engine.NoOpExecutionLogPort;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.parser.camunda.CamundaCompatParser;
import com.bpms.server.service.RunawayGuard;
import com.bpms.spi.connector.ConnectorProvider;
import com.bpms.spi.engine.RuntimeModels.DefinitionRecord;
import com.bpms.spi.expression.ExpressionEvaluator;
import com.bpms.spi.parse.ProcessDefinitionParser;
import com.bpms.spi.port.ClockPort;
import com.bpms.spi.port.DefinitionLookupPort;
import com.bpms.spi.port.DefinitionRegistry;
import com.bpms.spi.port.DefinitionRepositoryPort;
import com.bpms.spi.port.EventSubscriptionPort;
import com.bpms.spi.port.ExecutionLogPort;
import com.bpms.spi.port.IncidentPort;
import com.bpms.spi.port.InstanceControlPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.ListenerLogPort;
import com.bpms.spi.port.SpawnGuardPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TerminationSignal;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.TokenStatePort;
import com.bpms.spi.port.VariableStorePort;
import com.bpms.spi.script.ScriptNamespaceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Configuration
public class EngineConfig {

    @Bean
    ProcessDefinitionParser camundaCompatParser() {
        return new CamundaCompatParser();
    }

    @Bean
    ExpressionEvaluator expressionEvaluator(List<ScriptNamespaceProvider> namespaceProviders) {
        return new SpelExpressionEvaluator(namespaceProviders);
    }

    @Bean
    ClockPort clockPort() {
        return Instant::now;
    }

    @Bean
    ConnectorRegistry connectorRegistry(List<ConnectorProvider> providers) {
        return new ConnectorRegistry(providers);
    }

    @Bean
    DefinitionRegistry definitionRegistry(
            DefinitionRepositoryPort definitions,
            ProcessDefinitionParser parser,
            @Value("${bpms.definition-cache.maximum-size:500}") long maximumSize,
            @Value("${bpms.definition-cache.expire-after-access:6h}") Duration expireAfterAccess
    ) {
        return new CachingDefinitionRegistry(definitions, parser, maximumSize, expireAfterAccess);
    }

    @Bean
    @ConditionalOnProperty(name = "bpms.execution-log.enabled", havingValue = "false")
    ExecutionLogPort noOpExecutionLogPort() {
        return NoOpExecutionLogPort.INSTANCE;
    }

    /** Cooperative stop signal for the engine, backed by a live status read (plan 27 §3b). */
    @Bean
    TerminationSignal terminationSignal(InstanceControlPort control) {
        return control::isHalted;
    }

    /**
     * Process-key → {@link ProcessDefinition}/definition-id lookup for callActivity (plan 34 Phase 1).
     * Reuses the same {@link DefinitionRegistry} cache the rest of the engine uses — no extra parsing.
     */
    @Bean
    DefinitionLookupPort definitionLookupPort(DefinitionRepositoryPort definitions, DefinitionRegistry registry) {
        return new DefinitionLookupPort() {
            @Override
            public Optional<ProcessDefinition> findDefinitionByKey(String processKey) {
                return definitions.findLatestByKey(processKey).map(d -> registry.get(d.id()));
            }

            @Override
            public Optional<String> findDefinitionIdByKey(String processKey) {
                return definitions.findLatestByKey(processKey).map(DefinitionRecord::id);
            }

            @Override
            public Optional<ProcessDefinition> findDefinitionById(String definitionId) {
                return definitions.findDefinitionById(definitionId).map(d -> registry.get(d.id()));
            }
        };
    }

    /** Wraps the previously-dormant {@code RunawayGuard.checkSpawnDepthBeforeStart} (plan 27 §4) for callActivity spawn. */
    @Bean
    SpawnGuardPort spawnGuardPort(RunawayGuard runawayGuard) {
        return runawayGuard::checkSpawnDepthBeforeStart;
    }

    @Bean
    ExecutionEngine executionEngine(
            ConnectorRegistry registry,
            ExpressionEvaluator expressionEvaluator,
            InstanceRepositoryPort instances,
            TokenRepositoryPort tokens,
            VariableStorePort variables,
            TaskRepositoryPort tasks,
            JobRepositoryPort jobs,
            JobQueuePort jobQueue,
            ClockPort clock,
            ObjectMapper objectMapper,
            ExecutionLogPort execLog,
            TokenStatePort tokenState,
            ListenerLogPort listenerLog,
            TerminationSignal terminationSignal,
            IncidentPort incidents,
            DefinitionLookupPort definitionLookupPort,
            SpawnGuardPort spawnGuardPort,
            EventSubscriptionPort eventSubscriptionPort,
            @Value("${bpms.job-queue:in-process}") String jobQueueMode,
            @Value("${bpms.runaway.max-steps-per-run:10000}") int maxStepsPerRun,
            @Value("${bpms.runaway.max-node-revisits-per-run:1000}") int maxNodeRevisitsPerRun
    ) {
        boolean async = "rabbit".equalsIgnoreCase(jobQueueMode);
        return new ExecutionEngine(
                registry, expressionEvaluator, instances, tokens, variables, tasks, jobs, jobQueue,
                clock, async, objectMapper, execLog, tokenState, listenerLog,
                terminationSignal, incidents, maxStepsPerRun, maxNodeRevisitsPerRun,
                definitionLookupPort, spawnGuardPort, eventSubscriptionPort);
    }
}
