package com.bpms.server.config;

import com.bpms.engine.ConnectorRegistry;
import com.bpms.engine.ExecutionEngine;
import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.parser.camunda.CamundaCompatParser;
import com.bpms.spi.connector.ConnectorProvider;
import com.bpms.spi.expression.ExpressionEvaluator;
import com.bpms.spi.parse.ProcessDefinitionParser;
import com.bpms.spi.port.ClockPort;
import com.bpms.spi.port.InstanceRepositoryPort;
import com.bpms.spi.port.JobQueuePort;
import com.bpms.spi.port.JobRepositoryPort;
import com.bpms.spi.port.TaskRepositoryPort;
import com.bpms.spi.port.TokenRepositoryPort;
import com.bpms.spi.port.VariableStorePort;
import com.bpms.spi.script.ScriptNamespaceProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

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
            @Value("${bpms.job-queue:in-process}") String jobQueueMode
    ) {
        boolean async = "rabbit".equalsIgnoreCase(jobQueueMode);
        return new ExecutionEngine(
                registry, expressionEvaluator, instances, tokens, variables, tasks, jobs, jobQueue,
                clock, async, objectMapper);
    }
}
