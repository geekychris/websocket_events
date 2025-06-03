package com.example.websocket;

import com.example.websocket.model.Event;
import com.example.websocket.model.UserEvent;
import com.example.websocket.service.RedisConnectionManager;
import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Value;
import org.testcontainers.utility.DockerImageName;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaAdmin;
import org.junit.jupiter.api.AfterEach;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(partitions = 1, topics = {"user-events"})
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "spring.kafka.consumer.auto-offset-reset=earliest",
    "spring.kafka.consumer.group-id=test-group"
})
class KafkaEventIntegrationTest {

    private static DynamicPropertyRegistry registry;

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:6-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void registerRedisProperties(DynamicPropertyRegistry registry) {
        KafkaEventIntegrationTest.registry = registry;
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private KafkaTemplate<String, UserEvent> kafkaTemplate;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private RedisConnectionManager redisConnectionManager;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private BlockingQueue<Event> receivedEvents;
    private StompSession stompSession;
    private WebSocketStompClient stompClient;
    private static final String TOPIC = "user-events";
    private List<StompSession> activeSessions;

    @BeforeEach
    void setup() throws Exception {
        receivedEvents = new LinkedBlockingQueue<>();
        activeSessions = new ArrayList<>();
        
        List<Transport> transports = Collections.singletonList(
            new WebSocketTransport(new StandardWebSocketClient())
        );
        
        stompClient = new WebSocketStompClient(new SockJsClient(transports));
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("user-id", "testUser");

        stompSession = stompClient.connect(
            String.format("ws://localhost:%d/ws", port),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                    String sessionId = session.getSessionId();
                    session.subscribe("/topic/events." + sessionId, new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Event.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            receivedEvents.add((Event) payload);
                        }
                    });
                    
                    session.subscribe("/topic/events.broadcast", new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Event.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            receivedEvents.add((Event) payload);
                        }
                    });
                }

                @Override
                public void handleException(StompSession session, StompCommand command, 
                                         StompHeaders headers, byte[] payload, Throwable exception) {
                    throw new RuntimeException("Error in STOMP session", exception);
                }

                @Override
                public void handleTransportError(StompSession session, Throwable exception) {
                    throw new RuntimeException("Transport error in STOMP session", exception);
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    throw new UnsupportedOperationException("Unexpected STOMP frame received");
                }
            }
        ).get(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldReceiveKafkaEventViaWebSocket() throws Exception {
        // Given
        String userId = "testUser";
        UserEvent userEvent = UserEvent.builder()
            .eventType("TEST_EVENT")
            .userId(userId)
            .payload(Map.of("message", "test message"))
            .build();

        // When - send event to Kafka
        kafkaTemplate.send(TOPIC, userId, userEvent).get(5, TimeUnit.SECONDS);

        // Then - verify event is received via WebSocket
        Event receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(receivedEvent).isNotNull();
        assertThat(receivedEvent.getEventType()).isEqualTo("TEST_EVENT");
        assertThat(receivedEvent.getPayload()).containsEntry("message", "test message");

        // Verify connection is in Redis
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()));
    }

    @Test
    void shouldReceiveBroadcastEvents() throws Exception {
        // Given
        UserEvent broadcastEvent = UserEvent.builder()
            .eventType("BROADCAST_EVENT")
            .userId("broadcast")
            .payload(Map.of(
                "message", "broadcast message",
                "timestamp", System.currentTimeMillis()
            ))
            .build();

        // When - send broadcast event to Kafka
        kafkaTemplate.send(TOPIC, "broadcast", broadcastEvent).get(5, TimeUnit.SECONDS);

        // Then - verify broadcast event is received
        Event receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(receivedEvent).isNotNull();
        assertThat(receivedEvent.getEventType()).isEqualTo("BROADCAST_EVENT");
        assertThat(receivedEvent.getPayload())
            .containsEntry("message", "broadcast message")
            .containsKey("timestamp");
    }

    @Test
    void shouldHandleMultipleConnectionsForSameUser() throws Exception {
        // Given
        String userId = "testUser";
        
        // Create second connection for same user
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("user-id", userId);
        
        StompSession secondSession = stompClient.connect(
            String.format("ws://localhost:%d/ws", port),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                    String sessionId = session.getSessionId();
                    session.subscribe("/topic/events." + sessionId, new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Event.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            receivedEvents.add((Event) payload);
                        }
                    });
                }
            }
        ).get(5, TimeUnit.SECONDS);
        activeSessions.add(secondSession);

        // Wait for connections to be registered
        Thread.sleep(1000);

        // Verify both connections in Redis
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(2)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()))
            .anyMatch(conn -> conn.contains(secondSession.getSessionId()));

        // When - send event to Kafka
        UserEvent userEvent = UserEvent.builder()
            .eventType("MULTI_CONN_TEST")
            .userId(userId)
            .payload(Map.of("message", "test message"))
            .build();

        kafkaTemplate.send(TOPIC, userId, userEvent).get(5, TimeUnit.SECONDS);

        // Then - verify event is received on both connections
        List<Event> events = new ArrayList<>();
        Event event1 = receivedEvents.poll(5, TimeUnit.SECONDS);
        Event event2 = receivedEvents.poll(5, TimeUnit.SECONDS);
        events.add(event1);
        events.add(event2);
        
        assertThat(events)
            .hasSize(2)
            .allMatch(e -> e.getEventType().equals("MULTI_CONN_TEST"));
    }

    @Test
    void shouldHandleUserDisconnect() throws Exception {
        // Given
        String userId = "testUser";
        
        // Verify initial connection
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()));

        // When - disconnect
        stompSession.disconnect();
        Thread.sleep(1000);  // Allow time for cleanup

        // Then - verify connection removed from Redis
        connections = redisConnectionManager.getConnections(userId);
        assertThat(connections).isEmpty();
        
        // Reconnect for other tests
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("user-id", userId);
        stompSession = stompClient.connect(
            String.format("ws://localhost:%d/ws", port),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                    String sessionId = session.getSessionId();
                    session.subscribe("/topic/events." + sessionId, new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Event.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            receivedEvents.add((Event) payload);
                        }
                    });
                }
            }
        ).get(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldHandleInvalidUserId() throws Exception {
        // Given
        String invalidUserId = "unknown";
        UserEvent userEvent = UserEvent.builder()
            .eventType("TEST_EVENT")
            .userId(invalidUserId)
            .payload(Map.of("message", "test message"))
            .build();

        // When - send event to Kafka
        kafkaTemplate.send(TOPIC, invalidUserId, userEvent).get(5, TimeUnit.SECONDS);

        // Then - verify no event is received (wait a bit to ensure)
        Event receivedEvent = receivedEvents.poll(2, TimeUnit.SECONDS);
        assertThat(receivedEvent).isNull();
    }

    @Test
    void shouldHandleConcurrentEvents() throws Exception {
        // Given
        String userId = "testUser";
        int eventCount = 10;
        List<UserEvent> events = new ArrayList<>();
        
        for (int i = 0; i < eventCount; i++) {
            events.add(UserEvent.builder()
                .eventType("CONCURRENT_TEST")
                .userId(userId)
                .payload(Map.of(
                    "message", "concurrent message " + i,
                    "sequence", i
                ))
                .build());
        }

        // When - send events concurrently
        List<CompletableFuture<SendResult<String, UserEvent>>> futures = events.stream()
            .map(event -> kafkaTemplate.send(TOPIC, userId, event))
            .map(CompletableFuture::toCompletableFuture)
            .collect(Collectors.toList());

        // Wait for all sends to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);

        // Then - verify all events are received (possibly in different order)
        List<Event> receivedEvents = new ArrayList<>();
        for (int i = 0; i < eventCount; i++) {
            Event event = this.receivedEvents.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            receivedEvents.add(event);
        }

        assertThat(receivedEvents)
            .hasSize(eventCount)
            .allMatch(e -> e.getEventType().equals("CONCURRENT_TEST"))
            .allMatch(e -> e.getPayload().containsKey("sequence"));
    }

    @Test
    void shouldOnlyDeliverEventsToCorrectServiceInstance() throws Exception {
        // Given
        String userId = "testUser";
        String differentServiceInstance = "different-service";
        
        // Simulate a connection from a different service instance by manually adding to Redis
        String differentInstanceConnection = String.format("%s:localhost:%s", 
            differentServiceInstance, UUID.randomUUID().toString());
        redisTemplate.opsForSet().add("user:" + userId + ":connections", differentInstanceConnection);

        // Wait for Redis to propagate the change
        Thread.sleep(100);

        // Verify both connections exist
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(2)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()))
            .anyMatch(conn -> conn.startsWith(differentServiceInstance));

        // When - send event to Kafka
        UserEvent userEvent = UserEvent.builder()
            .eventType("SERVICE_SPECIFIC_TEST")
            .userId(userId)
            .payload(Map.of(
                "message", "test message",
                "timestamp", System.currentTimeMillis()
            ))
            .build();

        kafkaTemplate.send(TOPIC, userId, userEvent).get(5, TimeUnit.SECONDS);

        // Then - verify only one event is received (by our service instance)
        Event receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(receivedEvent)
            .isNotNull()
            .extracting(Event::getEventType)
            .isEqualTo("SERVICE_SPECIFIC_TEST");

        // Verify no more events were received
        assertThat(receivedEvents.poll(2, TimeUnit.SECONDS))
            .as("Should not receive events for different service instance")
            .isNull();

        // Clean up the manual Redis entry
        redisTemplate.opsForSet().remove("user:" + userId + ":connections", differentInstanceConnection);
    }

    @Test
    void shouldHandleMalformedEvents() throws Exception {
        // Given
        String userId = "testUser";
        
        // Create a String-based KafkaTemplate for sending raw messages
        Map<String, Object> producerProps = KafkaTestUtils.producerProps(bootstrapServers);
        DefaultKafkaProducerFactory<String, String> pf = new DefaultKafkaProducerFactory<>(producerProps);
        KafkaTemplate<String, String> stringTemplate = new KafkaTemplate<>(pf);

        // Send malformed JSON
        String malformedJson = "{\"eventType\":\"TEST_EVENT\",\"userId\":\"" + userId + "\",\"payload\":{\"message\": malformed}}";
        stringTemplate.send(TOPIC, userId, malformedJson).get(5, TimeUnit.SECONDS);

        // Then - verify no events are received due to deserialization error
        Event receivedEvent = receivedEvents.poll(2, TimeUnit.SECONDS);
        assertThat(receivedEvent).isNull();
        
        // Verify the connection is still active
        assertThat(stompSession.isConnected()).isTrue();
        
        // Verify we can still receive valid events
        UserEvent validEvent = UserEvent.builder()
            .eventType("VALID_EVENT")
            .userId(userId)
            .payload(Map.of("message", "valid message"))
            .build();
            
        kafkaTemplate.send(TOPIC, userId, validEvent).get(5, TimeUnit.SECONDS);
        
        receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(receivedEvent)
            .isNotNull()
            .extracting(Event::getEventType)
            .isEqualTo("VALID_EVENT");

        pf.destroy();
    }

    @Test
    void shouldHandleConnectionFailures() throws Exception {
        // Given
        String userId = "testUser";
        
        // Store original connection ID before stopping Redis
        Set<String> originalConnections = redisConnectionManager.getConnections(userId);
        assertThat(originalConnections).hasSize(1);
        String originalConnection = originalConnections.iterator().next();
        
        // Stop Redis container to simulate connection failure
        redis.stop();
        
        // When - try to send event during Redis outage
        UserEvent userEvent = UserEvent.builder()
            .eventType("REDIS_OUTAGE_TEST")
            .userId(userId)
            .payload(Map.of("message", "test message"))
            .build();

        kafkaTemplate.send(TOPIC, userId, userEvent).get(5, TimeUnit.SECONDS);
        
        // Then - verify no events are received during outage
        Event receivedEvent = receivedEvents.poll(2, TimeUnit.SECONDS);
        assertThat(receivedEvent).isNull();
        
        // Restart Redis and wait for it to be ready
        redis.start();
        
        // Update Redis connection properties and wait for connection to be reestablished
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        Thread.sleep(2000); // Allow more time for complete recovery
        
        // Re-register the connection
        redisTemplate.opsForSet().add("user:" + userId + ":connections", originalConnection);
        
        // Verify connection is restored
        Set<String> recoveredConnections = redisConnectionManager.getConnections(userId);
        assertThat(recoveredConnections)
            .hasSize(1)
            .contains(originalConnection);
        
        // Verify we can receive events after recovery
        UserEvent recoveryEvent = UserEvent.builder()
            .eventType("RECOVERY_TEST")
            .userId(userId)
            .payload(Map.of("message", "recovery message"))
            .build();
            
        kafkaTemplate.send(TOPIC, userId, recoveryEvent).get(5, TimeUnit.SECONDS);
        
        receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(receivedEvent)
            .isNotNull()
            .extracting(Event::getEventType)
            .isEqualTo("RECOVERY_TEST");
            
        // Verify connection remains stable after recovery
        Set<String> finalConnections = redisConnectionManager.getConnections(userId);
        assertThat(finalConnections)
            .hasSize(1)
            .contains(originalConnection);
    }

    @Test
    void shouldHandleRedisLatency() throws Exception {
        // Given
        String userId = "testUser";
        
        // Simulate Redis latency by stopping and starting the container
        // This creates a delay but doesn't fully disconnect
        redis.pause();
        Thread.sleep(100);  // Brief pause
        redis.unpause();
        
        // When - send multiple events during potential latency
        List<UserEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(UserEvent.builder()
                .eventType("LATENCY_TEST")
                .userId(userId)
                .payload(Map.of(
                    "sequence", i,
                    "message", "latency test " + i
                ))
                .build());
        }

        // Send events with minimal delay
        for (UserEvent event : events) {
            kafkaTemplate.send(TOPIC, userId, event).get(1, TimeUnit.SECONDS);
        }
        
        // Then - verify all events are eventually received
        List<Event> receivedEvents = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Event event = this.receivedEvents.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            receivedEvents.add(event);
        }

        assertThat(receivedEvents)
            .hasSize(events.size())
            .allMatch(e -> e.getEventType().equals("LATENCY_TEST"))
            .allMatch(e -> e.getPayload().containsKey("sequence"));

        // Verify connection is still in Redis after latency
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()));
    }

    @Test
    void shouldCleanupStaleConnections() throws Exception {
        // Given
        String userId = "testUser";
        
        // Add a stale connection directly to Redis
        String staleConnection = String.format("%s:localhost:%s", 
            "websocket-service", UUID.randomUUID().toString());
        redisTemplate.opsForSet().add("user:" + userId + ":connections", staleConnection);

        // Wait for Redis to propagate
        Thread.sleep(100);

        // Verify both connections exist
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(2)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()))
            .anyMatch(conn -> conn.equals(staleConnection));

        // When - send event to Kafka
        UserEvent userEvent = UserEvent.builder()
            .eventType("STALE_CONNECTION_TEST")
            .userId(userId)
            .payload(Map.of("message", "test message"))
            .build();

        kafkaTemplate.send(TOPIC, userId, userEvent).get(5, TimeUnit.SECONDS);

        // Then
        // 1. Verify event is received on active connection
        Event receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(receivedEvent)
            .isNotNull()
            .extracting(Event::getEventType)
            .isEqualTo("STALE_CONNECTION_TEST");

        // 2. Verify stale connection was cleaned up
        Thread.sleep(1000); // Allow time for cleanup
        connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .allMatch(conn -> conn.contains(stompSession.getSessionId()));
    }

    @Test
    void shouldCleanupRedisOnContextShutdown() throws Exception {
        // Given
        String userId = "testUser";
        
        // Verify initial connection exists
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()));

        // Add some additional test connections
        for (int i = 0; i < 3; i++) {
            String testConnection = String.format("websocket-service:test-host-%d:test-session-%d", i, i);
            redisTemplate.opsForSet().add("user:" + userId + ":connections", testConnection);
        }
        
        // Verify all connections are present
        connections = redisConnectionManager.getConnections(userId);
        assertThat(connections).hasSize(4);

        // When - simulate context shutdown by calling destroy method
        redisConnectionManager.destroy();

        // Then - verify only our service's connections are cleaned up
        connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(3)
            .allMatch(conn -> conn.startsWith("websocket-service:test-host-"))
            .noneMatch(conn -> conn.contains(stompSession.getSessionId()));

        // Cleanup test connections
        for (int i = 0; i < 3; i++) {
            String testConnection = String.format("websocket-service:test-host-%d:test-session-%d", i, i);
            redisTemplate.opsForSet().remove("user:" + userId + ":connections", testConnection);
        }

        // Restore the main test connection for other tests
        String activeConnection = String.format("%s:%s:%s", 
            "websocket-service",
            "localhost",  // Use localhost as we're in test
            stompSession.getSessionId());
        redisTemplate.opsForSet().add("user:" + userId + ":connections", activeConnection);

        // Verify restoration
        connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .allMatch(conn -> conn.contains(stompSession.getSessionId()));
    }

    @Test
    void shouldHandleKafkaOutage() throws Exception {
        // Given
        String userId = "testUser";
        
        // Verify initial connection state
        Set<String> connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()));

        // When - simulate Kafka outage by temporarily removing admin configs
        kafkaAdmin.setFatalIfBrokerNotAvailable(true);
        Map<String, Object> configs = kafkaAdmin.getConfigurationProperties();
        String originalBrokers = (String) configs.get("bootstrap.servers");
        configs.put("bootstrap.servers", "localhost:9999");  // Invalid broker
        
        // Try to send event during simulated outage
        UserEvent userEvent = UserEvent.builder()
            .eventType("KAFKA_OUTAGE_TEST")
            .userId(userId)
            .payload(Map.of("message", "test message"))
            .build();

        // Then - verify send fails but doesn't affect WebSocket connection
        CompletableFuture<SendResult<String, UserEvent>> future = 
            kafkaTemplate.send(TOPIC, userId, userEvent)
                .toCompletableFuture();
            
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasRootCauseInstanceOf(TimeoutException.class);

        // Verify WebSocket connection is still active
        assertThat(stompSession.isConnected()).isTrue();
        
        // Verify Redis connection is maintained
        connections = redisConnectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .anyMatch(conn -> conn.contains(stompSession.getSessionId()));

        // Restore Kafka configuration
        configs.put("bootstrap.servers", originalBrokers);
        
        // Verify system recovers after Kafka is restored
        UserEvent recoveryEvent = UserEvent.builder()
            .eventType("KAFKA_RECOVERY_TEST")
            .userId(userId)
            .payload(Map.of("message", "recovery message"))
            .build();
            
        kafkaTemplate.send(TOPIC, userId, recoveryEvent).get(5, TimeUnit.SECONDS);
        
        Event receivedEvent = receivedEvents.poll(5, TimeUnit.SECONDS);
        assertThat(receivedEvent)
            .isNotNull()
            .extracting(Event::getEventType)
            .isEqualTo("KAFKA_RECOVERY_TEST");
    }

    @AfterEach
    void cleanup() {
        // Clear any pending events
        receivedEvents.clear();

        // First, disconnect all STOMP sessions which will trigger Redis cleanup
        if (stompSession != null && stompSession.isConnected()) {
            try {
                stompSession.disconnect();
                Thread.sleep(100); // Brief wait for disconnect to propagate
            } catch (Exception e) {
                System.err.println("Error disconnecting main session: " + e.getMessage());
            }
        }
        
        if (activeSessions != null) {
            activeSessions.forEach(session -> {
                try {
                    if (session.isConnected()) {
                        session.disconnect();
                        Thread.sleep(100); // Brief wait for disconnect to propagate
                    }
                } catch (Exception e) {
                    System.err.println("Error disconnecting additional session: " + e.getMessage());
                }
            });
            activeSessions.clear();
        }

        // Then, force cleanup of any remaining Redis keys
        try {
            Thread.sleep(500); // Wait for any async operations to complete
            
            // Get and clean all test-related keys with multiple patterns
            Set<String> allKeys = new HashSet<>();
            allKeys.addAll(redisTemplate.keys("user:testUser:*"));
            allKeys.addAll(redisTemplate.keys("user:broadcast:*"));
            allKeys.addAll(redisTemplate.keys("user:unknown:*"));
            allKeys.addAll(redisTemplate.keys("user:*:test-session-*"));
            allKeys.addAll(redisTemplate.keys("connections:*"));
            
            if (!allKeys.isEmpty()) {
                redisTemplate.delete(allKeys);
            }
            
            // Verify cleanup and handle any remaining keys
            Thread.sleep(100); // Wait for deletions to complete
            Set<String> remainingKeys = redisTemplate.keys("user:*:*");
            if (remainingKeys != null && !remainingKeys.isEmpty()) {
                System.err.println("Warning: Some Redis keys remained after cleanup: " + remainingKeys);
                redisTemplate.delete(remainingKeys);
            }

            // Clear any events that might have been generated during cleanup
            receivedEvents.clear();
        } catch (Exception e) {
            System.err.println("Error during Redis cleanup: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Ensure event queue is cleared even if cleanup fails
            receivedEvents.clear();
        }
    }

    private StompSession createSession(String userId) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("user-id", userId);
        
        StompSession session = stompClient.connect(
            String.format("ws://localhost:%d/ws", port),
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {
                @Override
                public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                    subscribeToSessionEvents(session);
                }
            }
        ).get(5, TimeUnit.SECONDS);
        
        activeSessions.add(session);
        return session;
    }

    private void subscribeToSessionEvents(StompSession session) {
        session.subscribe("/topic/events." + session.getSessionId(), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Event.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                receivedEvents.add((Event) payload);
            }
        });
    }
}
