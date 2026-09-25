package com.workoutdone.rpgym.game.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "Bearer Authentication";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String USER_ROLE_HEADER = "X-User-Role";

    private static final Set<String> PUBLIC_PATHS = Set.of();

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .servers(List.of(
                        new Server()
                                .url("/")
                                .description("API Gateway")
                ))
                .components(new Components()
                        .addSecuritySchemes(
                                SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                        )
                );
    }

    @Bean
    public OpenApiCustomizer openApiCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, pathItem) ->
                pathItem.readOperations().forEach(operation -> {

                    // Gateway가 JWT 검증 후 생성하는 내부 Header는 Swagger에서 숨김
                    if (operation.getParameters() != null) {
                        operation.getParameters().removeIf(parameter ->
                                "header".equals(parameter.getIn())
                                        && (USER_ID_HEADER.equalsIgnoreCase(parameter.getName())
                                        || USER_ROLE_HEADER.equalsIgnoreCase(parameter.getName()))
                        );
                    }

                    // Gateway에서 인증이 필요한 API에만 Bearer 인증 표시
                    if (!PUBLIC_PATHS.contains(path)) {
                        operation.addSecurityItem(
                                new SecurityRequirement()
                                        .addList(SECURITY_SCHEME_NAME)
                        );
                    }
                })
        );
    }
}