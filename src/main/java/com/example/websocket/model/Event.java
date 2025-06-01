package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Data
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event {
    private String eventType;
    private String sessionId;
    private Object payload;
    
    @Builder.Default
    private Instant timestamp = Instant.now();

    public static Event of(String eventType, String sessionId, Object payload) {
        log.debug("Creating event: type={}, sessionId={}, payload={}", eventType, sessionId, payload);
        return Event.builder()
                .eventType(eventType)
                .sessionId(sessionId)
                .payload(payload)
                .timestamp(Instant.now())
                .build();
    }
}
