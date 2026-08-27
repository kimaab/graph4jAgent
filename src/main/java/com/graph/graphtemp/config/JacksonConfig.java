package com.graph.graphtemp.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.PropertyNamingStrategies;

/**
 * The agent spec is snake_case on the wire ("system_prompt", "graph_type",
 * "max_iterations") while the Java records stay camelCase.
 * <p>
 * Spring Boot 4 ships Jackson 3, so this hooks {@code JsonMapper.Builder} —
 * {@code Jackson2ObjectMapperBuilderCustomizer} no longer exists.
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer snakeCaseCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
