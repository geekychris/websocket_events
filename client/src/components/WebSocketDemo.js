import React, { useState, useEffect, useCallback } from 'react';
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const WebSocketDemo = () => {
  const [status, setStatus] = useState('Disconnected');
  const [sessionId, setSessionId] = useState(null);
  const [messages, setMessages] = useState([]);
  const [stompClient, setStompClient] = useState(null);

  const addMessage = useCallback((message, type = 'debug') => {
    setMessages(prev => [...prev, { text: message, type, timestamp: new Date().toISOString() }]);
  }, []);

  const connect = useCallback(() => {
    const socket = new SockJS('http://localhost:8080/ws');
    const client = new Client({
      webSocketFactory: () => socket,
      onConnect: () => {
        // Extract session ID from SockJS URL
        const socketUrl = socket._transport.url;
        const newSessionId = socketUrl.match(/\/([^/]+)\/websocket/)[1];
        setSessionId(newSessionId);
        setStatus('Connected');
        addMessage(`Connected with session ID: ${newSessionId}`, 'event');

        // Subscribe to personal events
        client.subscribe(`/topic/events.${newSessionId}`, message => {
          try {
            const event = JSON.parse(message.body);
            addMessage(`Received personal event: ${JSON.stringify(event, null, 2)}`, 'event');
          } catch (error) {
            addMessage(`Error parsing message: ${error.message}`, 'error');
          }
        });

        // Subscribe to broadcast events
        client.subscribe('/topic/events.broadcast', message => {
          try {
            const event = JSON.parse(message.body);
            addMessage(`Received broadcast: ${JSON.stringify(event, null, 2)}`, 'event');
          } catch (error) {
            addMessage(`Error parsing broadcast: ${error.message}`, 'error');
          }
        });
      },
      onDisconnect: () => {
        setStatus('Disconnected');
        setSessionId(null);
        addMessage('Disconnected from server', 'debug');
      },
      onStompError: error => {
        setStatus(`Error: ${error.headers?.message || 'Unknown error'}`);
        addMessage(`Connection error: ${error.headers?.message || 'Unknown error'}`, 'error');
      }
    });

    setStompClient(client);
    client.activate();
  }, [addMessage]);

  const disconnect = useCallback(() => {
    if (stompClient) {
      stompClient.deactivate();
      setStompClient(null);
    }
  }, [stompClient]);

  const sendTestEvent = useCallback(() => {
    if (!sessionId) {
      alert('Not connected!');
      return;
    }

    fetch(`http://localhost:8080/api/events/send/${sessionId}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        eventType: 'TEST_EVENT',
        payload: {
          message: 'Hello!',
          timestamp: new Date().toISOString()
        }
      })
    })
      .then(response => {
        if (!response.ok) throw new Error('Network response was not ok');
        addMessage('Test event sent successfully', 'event');
      })
      .catch(error => {
        addMessage(`Failed to send test event: ${error.message}`, 'error');
      });
  }, [sessionId, addMessage]);

  const sendBroadcastEvent = useCallback(() => {
    fetch('http://localhost:8080/api/events/broadcast', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        eventType: 'BROADCAST_EVENT',
        payload: {
          message: 'Hello everyone!',
          timestamp: new Date().toISOString()
        }
      })
    })
      .then(response => {
        if (!response.ok) throw new Error('Network response was not ok');
        addMessage('Broadcast event sent successfully', 'event');
      })
      .catch(error => {
        addMessage(`Failed to send broadcast event: ${error.message}`, 'error');
      });
  }, [addMessage]);

  useEffect(() => {
    return () => {
      if (stompClient) {
        stompClient.deactivate();
      }
    };
  }, [stompClient]);

  return (
    <div className="websocket-demo">
      <h2>WebSocket Demo</h2>
      <div className="controls">
        <button onClick={connect} disabled={!!stompClient}>Connect</button>
        <button onClick={disconnect} disabled={!stompClient}>Disconnect</button>
        <button onClick={sendTestEvent} disabled={!sessionId}>Send Test Event</button>
        <button onClick={sendBroadcastEvent} disabled={!sessionId}>Send Broadcast</button>
      </div>
      <div className="status">
        <p>Status: {status}</p>
        {sessionId && <p>Session ID: {sessionId}</p>}
      </div>
      <div className="messages">
        {messages.map((msg, index) => (
          <p key={index} className={msg.type}>
            [{msg.timestamp}] {msg.text}
          </p>
        ))}
      </div>
    </div>
  );
};

export default WebSocketDemo;

