package com.example.websocket.service;

import com.example.websocket.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(messagingTemplate);
    }

    @Test
    void shouldRegisterAndRemoveSession() {
        // Given
        String sessionId = "test-session";
        String subscriptionId = "/topic/events.test-session";

        // When
        sessionManager.registerSession(sessionId, subscriptionId);

        // Then
        assert sessionManager.hasSession(sessionId);

        // When
        sessionManager.removeSession(sessionId);

        // Then
        assert !sessionManager.hasSession(sessionId);
    }

    @Test
    void shouldSendEventToSpecificSession() {
        // Given
        String sessionId = "test-session";
        String subscriptionId = "/topic/events.test-session";
        sessionManager.registerSession(sessionId, subscriptionId);

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
    void shouldBroadcastEventToAllSessions() {
        // Given
        String session1 = "session-1";
        String session2 = "session-2";
        sessionManager.registerSession(session1, "/topic/events.session-1");
        sessionManager.registerSession(session2, "/topic/events.session-2");

        Event event = Event.of("BROADCAST_EVENT", null, Map.of("broadcast", "message"));

        // When
        sessionManager.broadcastEvent(event);

        // Then
        verify(messagingTemplate).convertAndSend("/topic/events.session-1", event);
        verify(messagingTemplate).convertAndSend("/topic/events.session-2", event);
    }

    @Test
    void shouldHandleMessageTemplateException() {
        // Given
        String sessionId = "test-session";
        String subscriptionId = "/topic/events.test-session";
        sessionManager.registerSession(sessionId, subscriptionId);

        Event event = Event.of("TEST_EVENT", sessionId, Map.of("test", "data"));

        doThrow(new RuntimeException("Send failed"))
            .when(messagingTemplate)
            .convertAndSend(any(String.class), any(Object.class));

        // When & Then - should not throw exception
        sessionManager.sendEventToSession(sessionId, event);
    }
}

