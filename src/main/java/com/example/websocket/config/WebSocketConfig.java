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
                    // Debug log all headers
                    log.debug("STOMP CONNECT headers: {}", accessor.getMessageHeaders());
                    log.debug("STOMP CONNECT native headers: {}", accessor.getNativeHeader("userId"));
                    
                    // Try different ways to get userId
                    String userId = accessor.getFirstNativeHeader("user-id");
                    if (userId == null) {
                        userId = accessor.getFirstNativeHeader("client-id");
                        log.debug("Trying client-id header for userId: {}", userId);
                    }
                    if (userId == null) {
                        userId = accessor.getFirstNativeHeader("userId"); // fallback to old format
                        log.debug("Trying legacy userId header: {}", userId);
                    }
                    
                    if (userId != null) {
                        log.info("Found userId in STOMP headers: {}", userId);
                        Map<String, Object> attributes = accessor.getSessionAttributes();
                        if (attributes == null) {
                            attributes = new HashMap<>();
                        }
                        attributes.put("userId", userId);
                        accessor.setSessionAttributes(attributes);
                    } else {
                        log.warn("No userId found in STOMP headers");
                    }
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
                // Store session creation time and initialize attributes
                attributes.put("connectionTime", System.currentTimeMillis());
                
                // Check for userId in request parameters
                if (request instanceof org.springframework.http.server.ServletServerHttpRequest) {
                    org.springframework.http.server.ServletServerHttpRequest servletRequest = 
                        (org.springframework.http.server.ServletServerHttpRequest) request;
                    String userId = servletRequest.getServletRequest().getParameter("userId");
                    if (userId != null) {
                        log.debug("Found userId in handshake request: {}", userId);
                        attributes.put("userId", userId);
                    } else {
                        log.debug("No userId in handshake request, setting pending");
                        attributes.put("userId", "pending");
                    }
                }
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

