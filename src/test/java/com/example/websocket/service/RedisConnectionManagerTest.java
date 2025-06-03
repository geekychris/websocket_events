package com.example.websocket.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class RedisConnectionManagerTest {

    @Container
    static GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:6-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", redisContainer::getFirstMappedPort);
    }

    @Autowired
    private RedisConnectionManager connectionManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanup() {
        Set<String> keys = redisTemplate.keys("user:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void shouldRegisterAndRetrieveConnection() {
        // Given
        String userId = "testUser";
        String sessionId = "session123";

        // When
        connectionManager.registerConnection(userId, sessionId);

        // Then
        Set<String> connections = connectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .anyMatch(conn -> conn.endsWith(":" + sessionId));
    }

    @Test
    void shouldDeregisterConnection() {
        // Given
        String userId = "testUser";
        String sessionId = "session123";
        connectionManager.registerConnection(userId, sessionId);

        // When
        connectionManager.deregisterConnection(userId, sessionId);

        // Then
        Set<String> connections = connectionManager.getConnections(userId);
        assertThat(connections).isEmpty();
    }

    @Test
    void shouldHandleMultipleConnectionsForSameUser() {
        // Given
        String userId = "testUser";
        String session1 = "session1";
        String session2 = "session2";

        // When
        connectionManager.registerConnection(userId, session1);
        connectionManager.registerConnection(userId, session2);

        // Then
        Set<String> connections = connectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(2)
            .anyMatch(conn -> conn.endsWith(":" + session1))
            .anyMatch(conn -> conn.endsWith(":" + session2));
    }

    @Test
    void shouldIgnoreInvalidUserIds() {
        // Given
        String[] invalidUserIds = {"unknown", "pending", null};

        for (String userId : invalidUserIds) {
            // When
            connectionManager.registerConnection(userId, "session123");

            // Then
            Set<String> connections = connectionManager.getConnections(userId);
            assertThat(connections).isEmpty();
        }
    }

    @Test
    void shouldIncludeServiceNameInConnectionId() {
        // Given
        String userId = "testUser";
        String sessionId = "session123";

        // When
        connectionManager.registerConnection(userId, sessionId);

        // Then
        Set<String> connections = connectionManager.getConnections(userId);
        assertThat(connections)
            .hasSize(1)
            .allMatch(conn -> conn.startsWith("websocket-service:"));
    }
}

