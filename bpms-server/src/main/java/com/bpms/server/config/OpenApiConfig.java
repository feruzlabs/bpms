package com.bpms.server.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI bpmsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("BPMS New Backend API")
                        .description("New BPMN engine (Camunda schema compatibility). Side-by-side with the legacy engine.")
                        .version("0.1.0"));
    }
}