package com.example.websocket.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserEvent {
    private String eventType;
    private String userId;
    private Map<String, Object> payload;
    
    @Builder.Default
    private long timestamp = System.currentTimeMillis();
    
    public static UserEvent of(String eventType, String userId, Map<String, Object> payload) {
        return UserEvent.builder()
            .eventType(eventType)
            .userId(userId)
            .payload(payload)
            .build();
    }
}
