import { useEffect, useRef, useState, useCallback } from 'react';
import EventClient from './EventClient';
import type { Event, EventHandler, ConnectionStatus, EventClientConfig } from './types';

// Helper to check if config values that require reconnection have changed
const hasReconnectConfigChanged = (
    prevConfig: EventClientConfig | undefined, 
    newConfig: EventClientConfig | undefined
): boolean => {
    if (!prevConfig && !newConfig) return false;
    if (!prevConfig || !newConfig) return true;
    return prevConfig.url !== newConfig.url || prevConfig.userId !== newConfig.userId;
};

export function useEventClient(config?: EventClientConfig) {
    const clientRef = useRef<EventClient | null>(null);
    const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>({
        connected: false,
        connecting: false
    });

    // Store the previous config for comparison
    const prevConfigRef = useRef<EventClientConfig | undefined>();

    // Initialize and manage the client
    useEffect(() => {
        // Check if we need to reconnect
        if (clientRef.current && !hasReconnectConfigChanged(prevConfigRef.current, config)) {
            return;
        }

        // Cleanup existing client if it exists
        if (clientRef.current) {
            clientRef.current.disconnect();
            clientRef.current = null;
        }

        // Create and initialize new client
        clientRef.current = new EventClient(config);
        
        // Connect and setup status listener
        clientRef.current.connect().catch(error => {
            console.error('Failed to connect:', error);
        });
        
        const unsubscribe = clientRef.current.onConnectionStatusChange(setConnectionStatus);
        
        // Update the previous config reference
        prevConfigRef.current = config;

        return () => {
            unsubscribe();
            if (clientRef.current) {
                clientRef.current.disconnect();
                clientRef.current = null;
            }
        };
    }, [config]); // Track the entire config object

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

