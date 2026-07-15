package com.bpms.server.config;

import com.bpms.expression.SpelExpressionEvaluator;
import com.bpms.parser.camunda.CamundaCompatParser;
import com.bpms.spi.expression.ExpressionEvaluator;
import com.bpms.spi.parse.ProcessDefinitionParser;
import com.bpms.spi.script.ScriptNamespaceProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}