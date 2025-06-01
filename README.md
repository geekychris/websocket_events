# WebSocket Event Service

A Spring Boot application demonstrating real-time event broadcasting using WebSocket/STOMP protocol with support for both targeted and broadcast events.

## Architecture Overview

The system consists of these main components:

1. **WebSocket Server**
   - Handles WebSocket connections and STOMP messaging
   - Manages client sessions
   - Routes events to specific clients or broadcasts

2. **Event System**
   - Support for targeted and broadcast events
   - Session-based message routing
   - Event persistence and delivery guarantees

3. **Client Interface**
   - SockJS/STOMP client implementation
   - Automatic reconnection handling
   - Event subscription management

## Prerequisites

- Java 21 or later
- Maven 3.6+
- Modern web browser with WebSocket support

## Setup and Build

1. Clone the repository:
```bash
git clone <repository-url>
cd websock_ui_eventing
```

2. Configure Lombok:
```xml
<!-- Required in pom.xml -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.30</version>
    <scope>provided</scope>
    <optional>true</optional>
</dependency>
```

3. Enable annotation processing in your IDE:
   - IntelliJ IDEA: Enable annotation processing in Preferences
   - Eclipse: Install Lombok plugin and enable annotation processing

4. Build the project:
```bash
mvn clean install
```

5. Run the application:
```bash
mvn spring-boot:run
```

## Client Library Usage

### WebSocket Connection

```javascript
const socket = new SockJS('http://localhost:8080/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, frame => {
    const sessionId = // extract from frame or URL
    
    // Subscribe to session events
    stompClient.subscribe(`/topic/events.${sessionId}`, message => {
        const event = JSON.parse(message.body);
        // Handle event
    });
    
    // Subscribe to broadcasts
    stompClient.subscribe('/topic/events.broadcast', message => {
        const event = JSON.parse(message.body);
        // Handle broadcast
    });
});
```

### Sending Events

```javascript
// Send session-specific event
fetch(`/api/events/send/${sessionId}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        eventType: 'TEST_EVENT',
        payload: { message: 'Hello!' }
    })
});

// Send broadcast
fetch('/api/events/broadcast', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
        eventType: 'BROADCAST_EVENT',
        payload: { message: 'Hello everyone!' }
    })
});
```

## Test UI Documentation

The included test.html provides a complete testing interface:

1. **Connection Management**
   - Connect/Disconnect buttons
   - Session ID display
   - Connection status indicator

2. **Event Testing**
   - Send test events to specific sessions
   - Send broadcast messages
   - View event history

3. **Debug Features**
   - Event log with timestamps
   - Color-coded message types
   - Full event details

Access the test UI at: `http://localhost:8080/test.html`

## API Documentation

### REST Endpoints

1. Send to Session
```
POST /api/events/send/{sessionId}
Content-Type: application/json

{
    "eventType": "string",
    "payload": {
        // Any JSON object
    }
}
```

2. Broadcast
```
POST /api/events/broadcast
Content-Type: application/json

{
    "eventType": "string",
    "payload": {
        // Any JSON object
    }
}
```

### WebSocket Topics

1. Session Events: `/topic/events.{sessionId}`
2. Broadcasts: `/topic/events.broadcast`

## Development Notes

### Required Dependencies

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-websocket</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
</dependencies>
```

### Common Issues

1. **Lombok Issues**
   - Ensure Lombok is properly configured in pom.xml
   - IDE annotation processing is enabled
   - Lombok plugin is installed in IDE
   - Run `mvn clean install -U` to refresh dependencies

2. **WebSocket Connection Issues**
   - Check CORS configuration
   - Verify WebSocket endpoint is enabled
   - Check client URL matches server configuration
   - Ensure SockJS fallback is properly configured

3. **Session ID Mismatch Issues**
   - STOMP headers may not contain expected session ID
   - SockJS transport URL contains the actual session ID
   - Example log pattern for debugging:
     ```
     # Log entry showing SockJS session ID
     DEBUG WebSocketEventHandler - SockJS session created: 12345
     # Log entry showing STOMP session ID mismatch
     WARN  SessionManager - STOMP session ID null does not match SockJS session 12345
     ```
   - Solution: Extract session ID from SockJS URL:
     ```javascript
     const socketUrl = socket._transport.url;
     const match = socketUrl.match(/\/([^/]+)\/websocket/);
     const sessionId = match ? match[1] : null;
     ```

4. **Event Routing Problems**
   - Verify subscription topic format: `/topic/events.{sessionId}`
   - Check server logs for subscription confirmation:
     ```
     DEBUG SessionManager - New subscription: session=12345, topic=/topic/events.12345
     DEBUG SessionManager - Event routing: target=12345, subscribed=true
     ```
   - Common routing issues:
     * Incorrect session ID in API calls
     * Missing or malformed subscription topics
     * Events sent before subscription established

### Logging Configuration

Add to application.properties:
```properties
# Enable detailed WebSocket logging
logging.level.com.example.websocket=DEBUG
logging.level.org.springframework.web.socket=DEBUG
logging.level.org.springframework.messaging=DEBUG

# Configure log pattern for debugging
logging.pattern.console=%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n

# Enable WebSocket trace logging (very verbose)
#logging.level.org.springframework.web.socket.messaging=TRACE
```

Key log patterns to watch:
```
# Session creation
DEBUG WebSocketEventHandler - New WebSocket session: [session-id]

# Subscription events
DEBUG SessionManager - Subscription registered: session=[id], topic=[topic]

# Event routing
DEBUG SessionManager - Routing event: type=[type], session=[id]

# Connection issues
WARN  WebSocketEventHandler - Session [id] disconnected unexpectedly
```

### Testing

1. Unit Tests:
```bash
mvn test
```

2. Integration Tests:
```bash
mvn verify
```

3. Manual Testing:
   - Use the test UI
   - Monitor browser console
   - Check server logs

## Contributing

1. Fork the repository
2. Create a feature branch
3. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.
## Troubleshooting Guide

### Session ID Issues

1. **Finding the Correct Session ID**
   ```javascript
   // Browser console
   console.log('Socket URL:', socket._transport.url);
   console.log('STOMP Headers:', frame.headers);
   ```

2. **Server-side Verification**
   ```java
   // Enable debug logging
   log.debug("Session headers: {}", headers);
   log.debug("Session attributes: {}", attributes);
   ```

### Event Routing Debug

1. **Topic Subscription Check**
   ```bash
   # Grep logs for subscription events
   grep "Subscription registered" application.log
   ```

2. **Event Delivery Tracking**
   ```bash
   # Monitor event routing in real-time
   tail -f application.log | grep "Routing event"
   ```

The system consists of two main components:

1. **Spring Boot Backend Service**
   - WebSocket server using STOMP protocol with SockJS fallback
   - Session management for multiple client connections
   - REST endpoints for external services to push events
   - Asynchronous event handling for scalability

2. **React Frontend Library**
   - TypeScript-based WebSocket client
   - React hook for easy integration
   - Automatic reconnection handling
   - Type-safe event subscription system

### Event Flow
```
External Service -> REST API -> WebSocket Server -> Client Library -> React Components
```

## Setup Instructions

### Backend Setup

1. Build the project:
   ```bash
   mvn clean install
   ```

2. Run the server:
   ```bash
   mvn spring-boot:run
   ```

The server will start on port 8080 by default.

### Frontend Setup

1. Install dependencies:
   ```bash
   cd frontend
   npm install @stomp/stompjs sockjs-client
   ```

2. Import the library:
   ```typescript
   import { useEventClient } from './lib/useEventClient';
   ```

## Usage Examples

### Quick Start: Send Test Event
To quickly test the system, you can send an event to a session using curl:

```bash
curl -X POST \
  http://localhost:8080/api/events/send/YOUR_SESSION_ID \
  -H "Content-Type: application/json" \
  -d '{"eventType": "TEST_EVENT", "payload": {"message": "Hello!"}}'
```

Replace `YOUR_SESSION_ID` with the session ID shown in the WebSocket test client UI or server logs.

### Backend API Usage

1. **Send Test Event to Specific Session**
   ```bash
   curl -X POST http://localhost:8080/api/events/send/{SESSION_ID} \
     -H "Content-Type: application/json" \
     -d '{
       "eventType": "TEST_EVENT",
       "payload": {
         "message": "Test message",
         "timestamp": "'$(date -u +"%Y-%m-%dT%H:%M:%SZ")'"
       }
     }'
   ```
   Replace `{SESSION_ID}` with the actual session ID shown in the WebSocket test client UI.

2. **Send Event to Specific Session**
   ```bash
   curl -X POST http://localhost:8080/api/events/send/{sessionId} \
     -H "Content-Type: application/json" \
     -d '{
       "eventType": "USER_UPDATE",
       "payload": {"userId": "123", "status": "online"}
     }'
   ```

2. **Broadcast Event to All Sessions**
   ```bash
   curl -X POST http://localhost:8080/api/events/broadcast \
     -H "Content-Type: application/json" \
     -d '{
       "eventType": "SYSTEM_NOTIFICATION",
       "payload": {"message": "System maintenance in 5 minutes"}
     }'
   ```

### Frontend Library Usage

1. **Basic Usage in React Component**
   ```typescript
   function MyComponent() {
     const { connectionStatus, subscribe } = useEventClient({
       url: 'http://localhost:8080/ws',
       debug: true
     });

     useEffect(() => {
       const subscription = subscribe('USER_UPDATE', (event) => {
         console.log('Received update:', event);
       });

       return () => subscription.unsubscribe();
     }, [subscribe]);

     return (
       <div>Status: {connectionStatus.connected ? 'Connected' : 'Disconnected'}</div>
     );
   }
   ```

2. **Multiple Event Types**
   ```typescript
   function MultiEventComponent() {
     const { subscribe } = useEventClient();

     useEffect(() => {
       const subscriptions = [
         subscribe('USER_UPDATE', handleUserUpdate),
         subscribe('SYSTEM_NOTIFICATION', handleNotification)
       ];

       return () => subscriptions.forEach(sub => sub.unsubscribe());
     }, [subscribe]);
   }
   ```

## API Documentation

### Backend REST Endpoints

#### POST /api/events/send/{sessionId}
Send event to specific session
- **Path Parameters:**
  - sessionId: WebSocket session identifier
- **Request Body:**
  - eventType: String
  - payload: Object
  - timestamp: ISO DateTime (optional)

#### POST /api/events/broadcast
Broadcast event to all connected sessions
- **Request Body:**
  - eventType: String
  - payload: Object
  - timestamp: ISO DateTime (optional)

### WebSocket Endpoints

#### /ws
Main WebSocket endpoint with SockJS fallback
- Supports STOMP protocol
- Session-specific subscriptions: `/topic/events.{sessionId}`

### Frontend Client API

#### useEventClient Hook
```typescript
const {
  connectionStatus,
  subscribe,
  getSessionId,
  client
} = useEventClient({
  url?: string,
  reconnectDelay?: number,
  debug?: boolean
});
```

#### EventClient Methods
- **connect()**: Promise<void>
- **disconnect()**: void
- **subscribe(eventType: string, handler: EventHandler)**: EventSubscription
- **getSessionId()**: string | null
- **onConnectionStatusChange(listener: (status: ConnectionStatus) => void)**: () => void

## Testing

### Backend Tests

Run backend tests with:
```bash
mvn test
```

### Frontend Tests

Run frontend tests with:
```bash
cd frontend
npm test
```

## Configuration

### Backend Configuration (application.properties)
```properties
# WebSocket Configuration
spring.websocket.max-text-message-size=8192
spring.websocket.max-binary-message-size=8192

# Server Configuration
server.port=8080
```

### Frontend Configuration
```typescript
const config: EventClientConfig = {
  url: 'http://localhost:8080/ws',
  reconnectDelay: 5000,
  debug: false
};
```

## Error Handling

The system includes comprehensive error handling:

1. **Connection Issues**
   - Automatic reconnection
   - Connection status updates
   - Error event propagation

2. **Message Processing**
   - Invalid message format handling
   - Failed delivery notification
   - Session timeout management

## Performance Considerations

- Uses non-blocking I/O for scalability
- Supports thousands of concurrent connections
- Efficient event routing using session IDs
- Minimal memory footprint per connection

