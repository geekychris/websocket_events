package com.example.websocket.service;

import com.example.websocket.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;
    
    @Mock
    private RedisConnectionManager redisConnectionManager;

    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(messagingTemplate, redisConnectionManager);
    }

    @Test
    void shouldRegisterAndRemoveSession() {
        // Given
        String sessionId = "test-session";
        String subscriptionId = "/topic/events.test-session";
        String userId = "test-user";

        // When
        sessionManager.registerSession(sessionId, subscriptionId, userId);

        // Then
        assert sessionManager.hasSession(sessionId);
        verify(redisConnectionManager).registerConnection(eq(userId), eq(sessionId));

        // When
        sessionManager.removeSession(sessionId);

        // Then
        assert !sessionManager.hasSession(sessionId);
        verify(redisConnectionManager).deregisterConnection(eq(userId), eq(sessionId));
    }

    @Test
    void shouldSendEventToSpecificSession() {
        // Given
        String sessionId = "test-session";
        String subscriptionId = "/topic/events.test-session";
        String userId = "test-user";
        sessionManager.registerSession(sessionId, subscriptionId, userId);

        Event event = Event.of("TEST_EVENT", sessionId, Map.of("test", "data"));

        // When
        sessionManager.sendEventToSession(sessionId, event);

        // Then
        verify(messagingTemplate).convertAndSend(
            "/topic/events." + sessionId,
            event
        );
    }

    @Test
    void shouldNotSendEventToNonExistentSession() {
        // Given
        String sessionId = "non-existent-session";
        Event event = Event.of("TEST_EVENT", sessionId, Map.of("test", "data"));

        // When
        sessionManager.sendEventToSession(sessionId, event);

        // Then
        verify(messagingTemplate, never()).convertAndSend(
            any(String.class),
            any(Object.class)
        );
    }

    @Test
    void shouldSkipRedisRegistrationForUnknownUser() {
        // Given
        String sessionId = "test-session";
        String subscriptionId = "/topic/events.test-session";

        // When
        sessionManager.registerSession(sessionId, subscriptionId);

        // Then
        verify(redisConnectionManager, never()).registerConnection(any(), any());
    }

    @Test
    void shouldBroadcastEvent() {
        // Given
        Event event = Event.of("BROADCAST_EVENT", null, Map.of("broadcast", "message"));

        // When
        sessionManager.broadcastEvent(event);

        // Then
        verify(messagingTemplate).convertAndSend("/topic/events.broadcast", event);
    }

    @Test
    void shouldHandleMessageTemplateException() {
        // Given
        String sessionId = "test-session";
        String subscriptionId = "/topic/events.test-session";
        String userId = "test-user";
        sessionManager.registerSession(sessionId, subscriptionId, userId);

        Event event = Event.of("TEST_EVENT", sessionId, Map.of("test", "data"));

        doThrow(new RuntimeException("Send failed"))
            .when(messagingTemplate)
            .convertAndSend(any(String.class), any(Object.class));

        // When & Then - should not throw exception
        sessionManager.sendEventToSession(sessionId, event);
    }
}

