package com.example.websocket.service;

import com.example.websocket.model.Event;
import com.example.websocket.model.UserEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaEventConsumerService {
    private final RedisConnectionManager redisConnectionManager;
    private final SessionManager sessionManager;
    
    @Value("${spring.application.name:websocket-service}")
    private String applicationName;

    @KafkaListener(topics = "${kafka.topic.user-events:user-events}", groupId = "${spring.kafka.consumer.group-id:websocket-service}")
    public void consumeUserEvent(UserEvent userEvent) {
        String userId = userEvent.getUserId();
        log.debug("Received event for user {}: {}", userId, userEvent.getEventType());
        
        Set<String> connections = redisConnectionManager.getConnections(userId);
        if (connections.isEmpty()) {
            log.debug("No active connections found for user {}", userId);
            return;
        }
        
        for (String connection : connections) {
            String[] parts = connection.split(":");
            if (parts.length != 3) {
                log.warn("Invalid connection format: {}", connection);
                continue;
            }
            
            String serviceName = parts[0];
            String sessionId = parts[2];
            
            if (!serviceName.equals(applicationName)) {
                log.debug("Skipping event for different service instance: {}", serviceName);
                continue;
            }
            
            if (!sessionManager.hasSession(sessionId)) {
                log.debug("Session {} no longer active, cleaning up", sessionId);
                redisConnectionManager.deregisterConnection(userId, sessionId);
                continue;
            }
            
            Event event = Event.builder()
                .eventType(userEvent.getEventType())
                .sessionId(sessionId)
                .payload(userEvent.getPayload())
                .build();
            
            try {
                sessionManager.sendEventToSession(sessionId, event);
                log.debug("Forwarded event to session {}", sessionId);
            } catch (Exception e) {
                log.error("Failed to forward event to session {}: {}", sessionId, e.getMessage());
            }
        }
    }
}

