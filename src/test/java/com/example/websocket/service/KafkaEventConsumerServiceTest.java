package com.example.websocket.service;

import com.example.websocket.model.Event;
import com.example.websocket.model.UserEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"user-events"})
@DirtiesContext
class KafkaEventConsumerServiceTest {

    @Autowired
    private KafkaTemplate<String, UserEvent> kafkaTemplate;

    @Autowired
    private KafkaEventConsumerService consumerService;

    @MockBean
    private RedisConnectionManager redisConnectionManager;

    @MockBean
    private SessionManager sessionManager;

    @Test
    void shouldConsumeAndForwardUserEvent() throws Exception {
        // Given
        String userId = "testUser";
        String sessionId = "session123";
        String connectionId = "websocket-service:localhost:" + sessionId;
        Set<String> connections = Collections.singleton(connectionId);

        UserEvent userEvent = UserEvent.builder()
            .eventType("TEST_EVENT")
            .userId(userId)
            .payload(new HashMap<>(Map.of("key", "value")))
            .build();

        when(redisConnectionManager.getConnections(userId)).thenReturn(connections);

        // When
        kafkaTemplate.send("user-events", userEvent.getUserId(), userEvent);

        // Then
        verify(sessionManager, timeout(5000)).sendEventToSession(eq(sessionId), any(Event.class));
    }

    @Test
    void shouldSkipEventsForDifferentServiceInstances() throws Exception {
        // Given
        String userId = "testUser";
        String sessionId = "session123";
        String connectionId = "different-service:localhost:" + sessionId;
        Set<String> connections = Collections.singleton(connectionId);

        UserEvent userEvent = UserEvent.builder()
            .eventType("TEST_EVENT")
            .userId(userId)
            .payload(new HashMap<>(Map.of("key", "value")))
            .build();

        when(redisConnectionManager.getConnections(userId)).thenReturn(connections);

        // When
        kafkaTemplate.send("user-events", userEvent.getUserId(), userEvent);

        // Then - verify that no event was sent
        verify(sessionManager, timeout(5000).times(0)).sendEventToSession(any(), any());
    }
}

