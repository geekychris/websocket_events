package com.example.websocket.controller;

import com.example.websocket.model.Event;
import com.example.websocket.service.SessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SessionManager sessionManager;

    @Test
    void shouldSendEventToValidSession() throws Exception {
        // Given
        String sessionId = "5a27d986-84d8-4716-9bdd-2207a6990466";
        Event event = Event.builder()
                .eventType("TEST_EVENT")
                .payload(Map.of("test", "data"))
                .build();

        when(sessionManager.hasSession(sessionId)).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/events/send/{sessionId}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());

        verify(sessionManager).sendEventToSession(eq(sessionId), any(Event.class));
    }

    @Test
    void shouldReturnNotFoundForInvalidSession() throws Exception {
        // Given
        String sessionId = "invalid-session";
        Event event = Event.builder()
                .eventType("TEST_EVENT")
                .payload(Map.of("test", "data"))
                .build();

        when(sessionManager.hasSession(sessionId)).thenReturn(false);

        // When & Then
        mockMvc.perform(post("/api/events/send/{sessionId}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isNotFound());

        verify(sessionManager, never()).sendEventToSession(any(), any());
    }

    @Test
    void shouldBroadcastEventToAllSessions() throws Exception {
        // Given
        Event event = Event.builder()
                .eventType("BROADCAST_EVENT")
                .payload(Map.of("broadcast", "message"))
                .build();

        // When & Then
        mockMvc.perform(post("/api/events/broadcast")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(event)))
                .andExpect(status().isOk());

        verify(sessionManager).broadcastEvent(any(Event.class));
    }

    @Test
    void shouldHandleInvalidEventPayload() throws Exception {
        // Given
        String sessionId = "test-session";
        String invalidJson = "{invalid-json}";

        // When & Then
        mockMvc.perform(post("/api/events/send/{sessionId}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson))
                .andExpect(status().isBadRequest());

        verify(sessionManager, never()).sendEventToSession(any(), any());
    }

    @Test
    void shouldValidateRequiredEventFields() throws Exception {
        // Given
        String sessionId = "test-session";
        Event invalidEvent = Event.builder().build(); // Missing required fields

        when(sessionManager.hasSession(sessionId)).thenReturn(true);

        // When & Then
        mockMvc.perform(post("/api/events/send/{sessionId}", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidEvent)))
                .andExpect(status().isBadRequest());

        verify(sessionManager, never()).sendEventToSession(any(), any());
    }
}

