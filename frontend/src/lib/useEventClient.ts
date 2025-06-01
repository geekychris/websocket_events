import { useEffect, useRef, useState, useCallback } from 'react';
import EventClient from './EventClient';
import type { Event, EventHandler, ConnectionStatus, EventClientConfig } from './types';

export function useEventClient(config?: EventClientConfig) {
    const clientRef = useRef<EventClient | null>(null);
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>({
        connected: false,
        connecting: false
    });

    // Initialize the client
    useEffect(() => {
        clientRef.current = new EventClient(config);
        
        // Connect and setup status listener
        clientRef.current.connect();
        const unsubscribe = clientRef.current.onConnectionStatusChange(setConnectionStatus);
        
        return () => {
            unsubscribe();
            clientRef.current?.disconnect();
            clientRef.current = null;
        };
    }, []);

    // Subscribe to events
    const subscribe = useCallback((eventType: string, handler: EventHandler) => {
        if (!clientRef.current) {
            throw new Error('EventClient not initialized');
        }
        return clientRef.current.subscribe(eventType, handler);
    }, []);

    // Get session ID
    const getSessionId = useCallback(() => {
        return clientRef.current?.getSessionId() ?? null;
    }, []);

    return {
        connectionStatus,
        subscribe,
        getSessionId,
        client: clientRef.current
    };
}

// Example usage of the hook with TypeScript:
/*
function MyComponent() {
    const { connectionStatus, subscribe } = useEventClient({
        url: 'http://localhost:8080/ws',
        debug: true
    });

    useEffect(() => {
        // Subscribe to specific event type
        const subscription = subscribe('USER_UPDATE', (event) => {
            console.log('Received user update:', event);
        });

        // Cleanup subscription
        return () => subscription.unsubscribe();
    }, [subscribe]);

    return (
        <div>
            Connection status: {connectionStatus.connected ? 'Connected' : 'Disconnected'}
        </div>
    );
}
*/

