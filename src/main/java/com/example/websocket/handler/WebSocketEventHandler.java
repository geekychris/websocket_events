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

import java.util.Collections;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventHandler {

    private final SessionManager sessionManager;
    private static final String BROADCAST_TOPIC = "/topic/events.broadcast";

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        log.info("Received a new web socket connection, session ID: {}", sessionId);
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
                    Event confirmEvent = Event.of("CONNECTED", sessionId, 
                        Collections.singletonMap("message", "Successfully connected to event stream"));
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
            log.info("User disconnected: {}", sessionId);
            sessionManager.removeSession(sessionId);
            sessionManager.removeSession(sessionId + ".broadcast"); // Clean up broadcast subscription
        }
    }
}

