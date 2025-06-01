package com.example.websocket.service;

import com.example.websocket.model.Event;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManager {
    private static final String BROADCAST_TOPIC = "/topic/events.broadcast";
    private final Map<String, String> sessionRegistry = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public void registerSession(String sessionId, String subscriptionId) {
        sessionRegistry.put(sessionId, subscriptionId);
        log.info("Registered session: {} with subscription: {}", sessionId, subscriptionId);
    }

    public void removeSession(String sessionId) {
        String subscription = sessionRegistry.remove(sessionId);
        if (subscription != null) {
            log.info("Removed session: {} with subscription: {}", sessionId, subscription);
        }
    }

    public boolean hasSession(String sessionId) {
        return sessionRegistry.containsKey(sessionId);
    }

    public void sendEventToSession(String sessionId, Event event) {
        if (!hasSession(sessionId)) {
            log.warn("Attempted to send event to unknown session: {}", sessionId);
            return;
        }

        try {
            String destination = "/topic/events." + sessionId;
            messagingTemplate.convertAndSend(destination, event);
            log.debug("Sent event to session {}: {}", sessionId, event);
        } catch (Exception e) {
            log.error("Failed to send event to session {}: {}", sessionId, e.getMessage());
        }
    }

    public void broadcastEvent(Event event) {
        log.debug("Broadcasting event to all sessions: {}", event);
        
        try {
            messagingTemplate.convertAndSend(BROADCAST_TOPIC, event);
            log.debug("Sent event to broadcast topic");
        } catch (Exception e) {
            log.error("Failed to send broadcast event: {}", e.getMessage());
        }
    }
}

