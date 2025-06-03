package com.example.websocket.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;

@Slf4j
@Service
public class RedisConnectionManager {
    
    private final StringRedisTemplate redisTemplate;
    private final SetOperations<String, String> setOps;
    private String hostname;
    private final Counter redisOperationsTotal;
    private final Counter redisOperationsFailedTotal;
    private final Counter redisRegistrationTotal;
    private final Counter redisDeregistrationTotal;
    private final Counter redisBatchOperationsTotal;
    private final Gauge activeConnectionsGauge;
    private final Timer redisOperationTimer;
    
    private final String applicationName;
    
    public RedisConnectionManager(
            StringRedisTemplate redisTemplate, 
            MeterRegistry meterRegistry,
            @Value("${spring.application.name:websocket-service}") String applicationName) {
        this.redisTemplate = redisTemplate;
        this.setOps = redisTemplate.opsForSet();
        this.applicationName = applicationName;
        
        // Initialize metrics
        this.redisOperationsTotal = Counter.builder("redis.operations.total")
            .description("Total number of Redis operations")
            .tag("type", "connection_manager")
            .register(meterRegistry);
            
        this.redisOperationsFailedTotal = Counter.builder("redis.operations.failed.total")
            .description("Total number of failed Redis operations")
            .tag("type", "connection_manager")
            .register(meterRegistry);

        this.redisRegistrationTotal = Counter.builder("redis.operations.registration.total")
            .description("Total number of connection registrations")
            .tag("type", "connection_manager")
            .register(meterRegistry);

        this.redisDeregistrationTotal = Counter.builder("redis.operations.deregistration.total")
            .description("Total number of connection deregistrations")
            .tag("type", "connection_manager")
            .register(meterRegistry);

        this.redisBatchOperationsTotal = Counter.builder("redis.operations.batch.total")
            .description("Total number of batch operations")
            .tag("type", "connection_manager")
            .register(meterRegistry);
            
        this.activeConnectionsGauge = Gauge.builder("redis.connections.active", 
            this, manager -> getActiveConnectionsCount())
            .description("Number of active Redis connections")
            .tag("type", "connection_manager")
            .register(meterRegistry);

        this.redisOperationTimer = Timer.builder("redis.operation.duration")
            .description("Redis operation duration")
            .tag("type", "connection_manager")
            .register(meterRegistry);
    }

    private long getActiveConnectionsCount() {
        try {
            Set<String> keys = redisTemplate.keys("user:*:connections");
            if (keys == null || keys.isEmpty()) {
                return 0;
            }
            
            long total = 0;
            for (String key : keys) {
                Long size = setOps.size(key);
                if (size != null) {
                    total += size;
                }
            }
            return total;
        } catch (Exception e) {
            log.error("Failed to get active connections count: {}", e.getMessage());
            return 0;
        }
    }
    
    @PostConstruct
    public void init() {
        this.hostname = getHostname();
        log.info("Initialized RedisConnectionManager with hostname: {}", hostname);
    }
    
    public void registerConnection(String userId, String connectionId) {
        if (userId == null || userId.equals("unknown") || userId.equals("pending")) {
            log.debug("Skipping registration for invalid userId: {}", userId);
            return;
        }
        
        String key = getUserKey(userId);
        String value = formatConnectionId(connectionId);
        try {
            redisOperationTimer.record(() -> {
                setOps.add(key, value);
                redisTemplate.expire(key, Duration.ofHours(24)); // Add 24-hour TTL
            });
            redisOperationsTotal.increment();
            redisRegistrationTotal.increment();
            log.info("Registered connection for user {}: {}", userId, value);
        } catch (Exception e) {
            redisOperationsFailedTotal.increment();
            log.error("Failed to register connection: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to register connection in Redis", e);
        }
    }

    /**
     * Checks Redis connection health
     * @return true if Redis is accessible
     */
    public boolean isHealthy() {
        try {
            return Boolean.TRUE.equals(redisTemplate.execute((RedisCallback<Boolean>) connection -> {
                try {
                    return connection != null && "PONG".equals(connection.ping());
                } catch (Exception e) {
                    log.warn("Redis ping failed: {}", e.getMessage());
                    return false;
                }
            }));
        } catch (Exception e) {
            log.error("Redis health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Registers multiple connections in a batch operation
     */
    public void registerConnections(String userId, Set<String> connectionIds) {
        if (userId == null || userId.equals("unknown") || userId.equals("pending")) {
            log.debug("Skipping batch registration for invalid userId: {}", userId);
            return;
        }
        
        if (connectionIds == null || connectionIds.isEmpty()) {
            return;
        }

        String key = getUserKey(userId);
        try {
            redisOperationTimer.record(() -> {
                redisTemplate.execute((RedisCallback<Void>) connection -> {
                    byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
                    if (rawKey != null) {
                        // Convert all values in one go
                        byte[][] rawValues = connectionIds.stream()
                            .map(this::formatConnectionId)
                            .map(redisTemplate.getStringSerializer()::serialize)
                            .filter(bytes -> bytes != null)
                            .toArray(byte[][]::new);
                        
                        // Add all values in a single operation
                        if (rawValues.length > 0) {
                            connection.setCommands().sAdd(rawKey, rawValues);
                            // Set TTL for safety (24 hours)
                            connection.keyCommands().expire(rawKey, Duration.ofHours(24).toSeconds());
                        }
                    }
                    return null;
                });
                redisOperationsTotal.increment();
                redisBatchOperationsTotal.increment();
                log.info("Registered {} connections for user {}", connectionIds.size(), userId);
            });
        } catch (Exception e) {
            redisOperationsFailedTotal.increment();
            log.error("Failed to register connections in batch: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to register connections in Redis", e);
        }
    }

    /**
     * Updates the TTL for a user's connections
     */
    private void updateTTL(String userId) {
        if (userId == null || userId.equals("unknown") || userId.equals("pending")) {
            return;
        }

        String key = getUserKey(userId);
        try {
            redisTemplate.expire(key, Duration.ofHours(24));
        } catch (Exception e) {
            log.error("Failed to update TTL for user {}: {}", userId, e.getMessage());
        }
    }
    
    public void deregisterConnection(String userId, String connectionId) {
        if (userId == null || userId.equals("unknown") || userId.equals("pending")) {
            log.debug("Skipping deregistration for invalid userId: {}", userId);
            return;
        }
        
        String key = getUserKey(userId);
        String value = formatConnectionId(connectionId);
        try {
            redisOperationTimer.record(() -> {
                setOps.remove(key, value);
                // Clean up if this was the last connection
                Long size = setOps.size(key);
                if (size != null && size == 0) {
                    redisTemplate.delete(key);
                    log.debug("Removed empty connection set for user {}", userId);
                }
            });
            redisOperationsTotal.increment();
            redisDeregistrationTotal.increment();
            log.info("Deregistered connection for user {}: {}", userId, value);
        } catch (Exception e) {
            redisOperationsFailedTotal.increment();
            log.error("Failed to deregister connection: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to deregister connection in Redis", e);
        }
    }
    
    public Set<String> getConnections(String userId) {
        if (userId == null || userId.equals("unknown") || userId.equals("pending")) {
            log.debug("Cannot get connections for invalid userId: {}", userId);
            return Collections.emptySet();
        }
        
        String key = getUserKey(userId);
        try {
            if (!isHealthy()) {
                throw new RuntimeException("Redis is not healthy");
            }
            Set<String> connections = setOps.members(key);
            log.debug("Found {} connections for user {}", 
                connections != null ? connections.size() : 0, userId);
            return connections != null ? connections : Collections.emptySet();
        } catch (Exception e) {
            redisOperationsFailedTotal.increment();
            log.error("Failed to get connections: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get connections from Redis", e);
        }
    }
    
    private String getUserKey(String userId) {
        return String.format("user:%s:connections", userId);
    }
    
    private String formatConnectionId(String connectionId) {
        return String.format("%s:%s:%s", applicationName, hostname, connectionId);
    }
    
    private String getHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            log.warn("Could not determine hostname, using 'unknown'", e);
            return "unknown";
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("Cleaning up Redis connections for service instance: {}:{}", applicationName, hostname);
        try {
            // Get all connection keys
            Set<String> keys = redisTemplate.keys("user:*:connections");
            if (keys == null || keys.isEmpty()) {
                return;
            }

            String servicePrefix = applicationName + ":" + hostname + ":";
            int cleanedCount = 0;

            // Clean up connections for this service instance only
            for (String key : keys) {
                try {
                    Set<String> connections = setOps.members(key);
                    if (connections == null) continue;

                    for (String conn : connections) {
                        if (conn.startsWith(servicePrefix)) {
                            setOps.remove(key, conn);
                            cleanedCount++;
                        }
                    }

                    // Remove the key if no connections remain
                    if (setOps.size(key) == 0) {
                        redisTemplate.delete(key);
                    }
                } catch (Exception e) {
                    log.error("Error cleaning up connections for key {}: {}", key, e.getMessage());
                }
            }
            log.info("Cleaned up {} connections for service instance {}:{}", 
                cleanedCount, applicationName, hostname);
        } catch (Exception e) {
            log.error("Error during Redis connection cleanup: {}", e.getMessage(), e);
        }
    }

    /**
     * Validates and cleans up stale connections for a user
     */
    public void validateConnections(String userId) {
        if (userId == null || userId.equals("unknown") || userId.equals("pending")) {
            return;
        }

        String key = getUserKey(userId);
        try {
            redisOperationTimer.record(() -> {
                Set<String> connections = setOps.members(key);
                if (connections == null || connections.isEmpty()) {
                    return;
                }

                boolean hasStaleConnections = false;
                for (String conn : connections) {
                    String[] parts = conn.split(":");
                    if (parts.length != 3) {
                        setOps.remove(key, conn);
                        hasStaleConnections = true;
                        log.warn("Removed invalid connection format: {} for user: {}", conn, userId);
                    }
                }

                if (hasStaleConnections) {
                    // Check if any connections remain
                    Long size = setOps.size(key);
                    if (size != null && size == 0) {
                        redisTemplate.delete(key);
                        log.info("Removed empty connection set for user {} after cleanup", userId);
                    }
                }
                redisOperationsTotal.increment();
            });
        } catch (Exception e) {
            redisOperationsFailedTotal.increment();
            log.error("Error validating connections for user {}: {}", userId, e.getMessage(), e);
        }
    }
}
