package com.workoutdone.rpgym.gateway.infrastructure.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.workoutdone.rpgym.common.exception.CommonErrorCode;
import com.workoutdone.rpgym.common.response.ErrorResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public Mono<Void> write(
            ServerWebExchange exchange,
            CommonErrorCode errorCode
    ) {
        ErrorResponse response = ErrorResponse.of(
                errorCode.getCode(),
                errorCode.getMessage(),
                MDC.get("traceId")
        );

        try {
            byte[] bytes = objectMapper.writeValueAsString(response)
                    .getBytes(StandardCharsets.UTF_8);

            exchange.getResponse().setStatusCode(errorCode.getStatus());
            exchange.getResponse()
                    .getHeaders()
                    .setContentType(MediaType.APPLICATION_JSON);

            return exchange.getResponse().writeWith(
                    Mono.just(
                            exchange.getResponse()
                                    .bufferFactory()
                                    .wrap(bytes)
                    )
            );
        } catch (JsonProcessingException e) {
            return exchange.getResponse().setComplete();
        }
    }
}