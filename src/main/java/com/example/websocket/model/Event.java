package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

@Data
@Slf4j
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event {
    @NotNull(message = "Event type is required")
    @NotBlank(message = "Event type cannot be empty")
    private String eventType;
    
    @NotNull(message = "Session ID is required")
    @NotBlank(message = "Session ID cannot be empty")
    private String sessionId;
    
    @NotNull(message = "User ID is required")
    @NotBlank(message = "User ID cannot be empty")
    private String userId;
    
    @NotNull(message = "Payload is required")
    private Object payload;
    
    @Builder.Default
    private Instant timestamp = Instant.now();

    public static Event of(String eventType, String sessionId, String userId, Object payload) {
        log.debug("Creating event: type={}, sessionId={}, userId={}, payload={}", 
                 eventType, sessionId, userId, payload);
        return Event.builder()
                .eventType(eventType)
                .sessionId(sessionId)
                .userId(userId)
                .payload(payload)
                .timestamp(Instant.now())
                .build();
    }
}
