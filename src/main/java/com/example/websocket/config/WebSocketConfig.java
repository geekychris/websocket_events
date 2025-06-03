package com.example.websocket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocketMessageBroker
@Slf4j
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
                // Add custom handshake handler that preserves headers
                .setHandshakeHandler(new DefaultHandshakeHandler() {
                    @Override
                    protected Principal determineUser(
                            org.springframework.http.server.ServerHttpRequest request,
                            WebSocketHandler wsHandler,
                            Map<String, Object> attributes) {
                        // We'll handle the principal creation in the STOMP connection
                        return () -> "anonymous";
                    }
                })
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

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    log.debug("=== STOMP CONNECT START ===");
                    
                    // Log all headers for debugging
                    Map<String, Object> messageHeaders = accessor.getMessageHeaders();
                    log.debug("Message headers: {}", messageHeaders);
                    
                    // Get and log native headers
                    Map<String, List<String>> nativeHeaders = accessor.getNativeHeaders();
                    if (nativeHeaders != null) {
                        nativeHeaders.forEach((key, value) -> {
                            log.debug("Native header - {}: {}", key, value);
                        });
                    } else {
                        log.warn("No native headers found in STOMP message");
                    }
                    
                    // Get session attributes
                    Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
                    log.debug("Initial session attributes: {}", sessionAttrs);
                    
                    // Try to get userId from STOMP headers with detailed logging
                    String userId = null;
                    if (nativeHeaders != null) {
                        // Try user-id first
                        List<String> userIdHeaders = nativeHeaders.get("user-id");
                        if (userIdHeaders != null && !userIdHeaders.isEmpty()) {
                            userId = userIdHeaders.get(0);
                            log.debug("Found userId in user-id header: {}", userId);
                        }
                        
                        // Try client-id if user-id not found
                        if (userId == null) {
                            userIdHeaders = nativeHeaders.get("client-id");
                            if (userIdHeaders != null && !userIdHeaders.isEmpty()) {
                                userId = userIdHeaders.get(0);
                                log.debug("Found userId in client-id header: {}", userId);
                            }
                        }
                        
                        // Try legacy userId if still not found
                        if (userId == null) {
                            userIdHeaders = nativeHeaders.get("userId");
                            if (userIdHeaders != null && !userIdHeaders.isEmpty()) {
                                userId = userIdHeaders.get(0);
                                log.debug("Found userId in legacy userId header: {}", userId);
                            }
                        }
                    }
                    
                    // Ensure session attributes exist
                    if (sessionAttrs == null) {
                        sessionAttrs = new HashMap<>();
                        accessor.setSessionAttributes(sessionAttrs);
                    }
                    
                    // Handle userId from headers or session
                    if (userId != null && !userId.trim().isEmpty()) {
                        log.info("Setting userId from STOMP headers: {}", userId);
                        sessionAttrs.put("userId", userId);
                        accessor.setSessionAttributes(sessionAttrs);
                    } else {
                        // Check if we already have a userId from handshake
                        userId = (String) sessionAttrs.get("userId");
                        if (userId != null) {
                            log.debug("Using existing userId from session: {}", userId);
                        } else {
                            log.warn("No userId found in STOMP headers or session attributes");
                        }
                    }
                    
                    // Log final state
                    log.debug("Final session attributes: {}", accessor.getSessionAttributes());
                    log.debug("=== STOMP CONNECT END ===");
                } else if (accessor != null) {
                    log.debug("Non-CONNECT STOMP command: {}", accessor.getCommand());
                }
                
                return message;
            }
        });
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
                
                log.debug("=== WebSocket Handshake START ===");
                
                // Initialize attributes
                attributes.put("connectionTime", System.currentTimeMillis());
                
                // Extract userId from URL parameters
                if (request instanceof org.springframework.http.server.ServletServerHttpRequest) {
                    org.springframework.http.server.ServletServerHttpRequest servletRequest = 
                        (org.springframework.http.server.ServletServerHttpRequest) request;
                    
                    // Log all request parameters
                    Map<String, String[]> params = servletRequest.getServletRequest().getParameterMap();
                    log.debug("All request parameters: {}", params);
                    
                    String userId = servletRequest.getServletRequest().getParameter("userId");
                    log.debug("Handshake URL userId parameter: {}", userId);
                    
                    if (userId != null && !userId.trim().isEmpty()) {
                        log.info("Setting userId from URL parameter: {}", userId);
                        attributes.put("userId", userId);
                        attributes.put("original_userId", userId);
                    } else {
                        log.debug("No userId in URL parameters, will check STOMP headers");
                    }
                }
                
                log.debug("Handshake attributes: {}", attributes);
                log.debug("=== WebSocket Handshake END ===");
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

