package com.example.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable a simple memory-based message broker for sending messages to clients
        // The "/topic" prefix will be used for messages that are broadcasted to multiple clients
        config.enableSimpleBroker("/topic");
        
        // Set prefix for messages bound for @MessageMapping methods
        config.setApplicationDestinationPrefixes("/app");
        
        // Configure broker settings for better performance
        config.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // Allow connections from any origin during development
                .setAllowedOriginPatterns("*")
                // Add custom handshake handler
                .setHandshakeHandler(customHandshakeHandler())
                // Add custom interceptor
                .addInterceptors(customHandshakeInterceptor())
                // Enable SockJS fallback options
                .withSockJS()
                .setClientLibraryUrl("https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js")
                .setWebSocketEnabled(true)
                .setSessionCookieNeeded(false);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(64 * 1024) // 64KB
                .setSendBufferSizeLimit(512 * 1024) // 512KB
                .setSendTimeLimit(20 * 1000) // 20 seconds
                .setTimeToFirstMessage(30 * 1000); // 30 seconds
    }

    @Bean
    public DefaultHandshakeHandler customHandshakeHandler() {
        return new DefaultHandshakeHandler() {
            @Override
            protected Principal determineUser(
                    org.springframework.http.server.ServerHttpRequest request,
                    WebSocketHandler wsHandler,
                    Map<String, Object> attributes) {
                // Generate unique session ID for each connection
                final String sessionId = UUID.randomUUID().toString();
                return new Principal() {
                    @Override
                    public String getName() {
                        return sessionId;
                    }
                };
            }
        };
    }

    @Bean
    public HandshakeInterceptor customHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(
                    org.springframework.http.server.ServerHttpRequest request,
                    org.springframework.http.server.ServerHttpResponse response,
                    WebSocketHandler wsHandler,
                    Map<String, Object> attributes) {
                // Store session creation time
                attributes.put("connectionTime", System.currentTimeMillis());
                return true;
            }

            @Override
            public void afterHandshake(
                    org.springframework.http.server.ServerHttpRequest request,
                    org.springframework.http.server.ServerHttpResponse response,
                    WebSocketHandler wsHandler,
                    Exception exception) {
                // No action needed after handshake
            }
        };
    }
}

