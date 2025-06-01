export interface Event {
    eventType: string;
    sessionId: string;
    payload: any;
    timestamp: string;
}

export interface EventHandler {
    (event: Event): void;
}

export interface EventSubscription {
    eventType: string;
    handler: EventHandler;
    unsubscribe: () => void;
}

export interface ConnectionStatus {
    connected: boolean;
    connecting: boolean;
    error?: string;
}

export interface EventClientConfig {
    url?: string;
    reconnectDelay?: number;
    debug?: boolean;
}

