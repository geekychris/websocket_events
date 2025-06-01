package com.example.websocket.controller;

import com.example.websocket.model.Event;
import com.example.websocket.service.SessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
@Slf4j
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final SessionManager sessionManager;

    @PostMapping("/send/{sessionId}")
    public ResponseEntity<Void> sendEventToSession(
            @PathVariable String sessionId,
            @RequestBody Event event) {
        
        if (!sessionManager.hasSession(sessionId)) {
            log.warn("Attempted to send event to non-existent session: {}", sessionId);
            return ResponseEntity.notFound().build();
        }

        Event newEvent = Event.of(event.getEventType(), sessionId, event.getPayload());
        sessionManager.sendEventToSession(sessionId, newEvent);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/broadcast")
    public ResponseEntity<Void> broadcastEvent(@RequestBody Event event) {
        log.debug("Broadcasting event to all sessions: {}", event);
        sessionManager.broadcastEvent(event);
        return ResponseEntity.ok().build();
    }
}

