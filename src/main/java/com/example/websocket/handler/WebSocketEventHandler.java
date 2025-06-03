package com.example.websocket.handler;

import com.example.websocket.model.Event;
import com.example.websocket.service.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventHandler {

    private final SessionManager sessionManager;
    private static final String BROADCAST_TOPIC = "/topic/events.broadcast";

    private String extractUserId(StompHeaderAccessor headerAccessor) {
        String userId = null;
        log.debug("=== Extracting UserId START ===");
        
        // Log all available headers
        log.debug("All message headers: {}", headerAccessor.getMessageHeaders());

        Map<String, Object> attributes = headerAccessor.getSessionAttributes();
        log.debug("Session attributes: {}", attributes);
        
        // Try session attributes first
        if (attributes != null) {
            userId = (String) attributes.get("userId");
            log.debug("UserId from session attributes: {}", userId);
        }
        
        // If not found or pending, try STOMP headers
        if (userId == null || "pending".equals(userId)) {
            userId = headerAccessor.getFirstNativeHeader("user-id");
            log.debug("UserId from user-id header: {}", userId);
            
            if (userId == null) {
                userId = headerAccessor.getFirstNativeHeader("client-id");
                log.debug("UserId from client-id header: {}", userId);
            }
            
            if (userId == null) {
                userId = headerAccessor.getFirstNativeHeader("userId");
                log.debug("UserId from userId header: {}", userId);
            }
        }
        
        String finalUserId = (userId != null && !"pending".equals(userId)) ? userId : "unknown";
        log.debug("=== Extracting UserId END === (Result: {})", finalUserId);
        return finalUserId;
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String userId = extractUserId(headerAccessor);
        
        // Register the session with the user ID
        if (!"unknown".equals(userId)) {
            sessionManager.registerSession(sessionId, "/topic/events." + sessionId, userId);
            log.info("Registered new connection - Session ID: {}, User ID: {}", sessionId, userId);
        } else {
            sessionManager.registerSession(sessionId, "/topic/events." + sessionId);
            log.info("Registered new connection without user ID - Session ID: {}", sessionId);
        }
    }

    @EventListener
    public void handleWebSocketSubscribeListener(SessionSubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        String destination = headerAccessor.getDestination();

        if (destination != null) {
            log.debug("Subscription request: sessionId={}, destination={}", sessionId, destination);

            if (BROADCAST_TOPIC.equals(destination)) {
                // Handle broadcast topic subscription
                log.info("Client {} subscribed to broadcast events", sessionId);
                sessionManager.registerSession(sessionId + ".broadcast", destination);
            } else if (destination.startsWith("/topic/events.")) {
                // Handle personal topic subscription
                String expectedTopic = "/topic/events." + sessionId;
                if (destination.equals(expectedTopic)) {
                    log.info("Client {} subscribed to personal events", sessionId);
                    sessionManager.registerSession(sessionId, destination);
                    
                    // Send connection confirmation event
                    Event confirmEvent = Event.builder()
                        .eventType("CONNECTED")
                        .sessionId(sessionId)
                        .payload(Collections.singletonMap("message", "Successfully connected to event stream"))
                        .build();
                    sessionManager.sendEventToSession(sessionId, confirmEvent);
                } else {
                    log.warn("Client {} attempted to subscribe to invalid topic: {}", sessionId, destination);
                }
            }
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        
        if (sessionId != null) {
            String userId = sessionManager.getUserIdForSession(sessionId);
            sessionManager.removeSession(sessionId);
            sessionManager.removeSession(sessionId + ".broadcast"); // Clean up broadcast subscription
            
            if (userId != null) {
                log.info("User disconnected - Session ID: {}, User ID: {}", sessionId, userId);
            } else {
                log.info("Session disconnected - Session ID: {}", sessionId);
            }
        }
    }
}
