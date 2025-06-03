import SockJS from 'sockjs-client';
import { Client, Frame } from '@stomp/stompjs';
import { Event, EventHandler, EventSubscription, EventClientConfig, ConnectionStatus } from './types';

const DEFAULT_CONFIG: EventClientConfig = {
    url: '/ws',
    reconnectDelay: 5000,
    debug: false
};

class EventClient {
    private client: Client;
    private config: EventClientConfig;
    private subscriptions: Map<string, Set<EventHandler>>;
    private connectionStatus: ConnectionStatus;
    private statusListeners: Set<(status: ConnectionStatus) => void>;
    private sessionId: string | null;

    constructor(config: EventClientConfig = {}) {
        this.config = { ...DEFAULT_CONFIG, ...config };
        this.subscriptions = new Map();
        this.statusListeners = new Set();
        this.connectionStatus = { connected: false, connecting: false };
        this.sessionId = null;

        this.setupStompClient();
    }

    private setupStompClient() {
        this.client = new Client({
            webSocketFactory: () => new SockJS(this.config.url!),
            reconnectDelay: this.config.reconnectDelay,
            connectHeaders: this.config.userId ? {
                'user-id': this.config.userId,
                'userId': this.config.userId  // for backward compatibility
            } : {},
            debug: (str) => {
                if (this.config.debug) {
                    console.log('STOMP:', str);
                    if (this.config.userId) {
                        console.log('Using userId:', this.config.userId);
                    }
                }
            },
            onConnect: this.handleConnect.bind(this),
            onDisconnect: this.handleDisconnect.bind(this),
            onStompError: this.handleError.bind(this)
        });
    }

    public connect(): Promise<void> {
        if (this.connectionStatus.connected) {
            return Promise.resolve();
        }

        if (this.config.debug && !this.config.userId) {
            console.warn('No userId provided in EventClient configuration');
        }

        return new Promise((resolve, reject) => {
            this.updateConnectionStatus({ connecting: true });
            
            const connectTimeout = setTimeout(() => {
                reject(new Error('Connection timeout'));
                this.updateConnectionStatus({ 
                    connecting: false, 
                    error: 'Connection timeout' 
                });
            }, 10000);

            this.client.onConnect = (frame: Frame) => {
                clearTimeout(connectTimeout);
                this.handleConnect(frame);
                resolve();
            };

            try {
                this.client.activate();
            } catch (error) {
                clearTimeout(connectTimeout);
                reject(error);
                this.updateConnectionStatus({ 
                    connecting: false, 
                    error: error.message 
                });
            }
        });
    }

    public disconnect(): void {
        this.client.deactivate();
    }

    public subscribe(eventType: string, handler: EventHandler): EventSubscription {
        if (!this.subscriptions.has(eventType)) {
            this.subscriptions.set(eventType, new Set());
        }
        
        this.subscriptions.get(eventType)!.add(handler);

        return {
            eventType,
            handler,
            unsubscribe: () => {
                const handlers = this.subscriptions.get(eventType);
                if (handlers) {
                    handlers.delete(handler);
                    if (handlers.size === 0) {
                        this.subscriptions.delete(eventType);
                    }
                }
            }
        };
    }

    public onConnectionStatusChange(listener: (status: ConnectionStatus) => void): () => void {
        this.statusListeners.add(listener);
        listener(this.connectionStatus);
        
        return () => {
            this.statusListeners.delete(listener);
        };
    }

    public getSessionId(): string | null {
        return this.sessionId;
    }

    private handleConnect(frame: Frame): void {
        this.sessionId = frame.headers['session-id'];
        
        if (this.config.debug) {
            console.log('Connected with session ID:', this.sessionId);
            console.log('Using userId:', this.config.userId || 'not set');
        }
        
        // Subscribe to session-specific topic
        this.client.subscribe(`/topic/events.${this.sessionId}`, (message) => {
            try {
                const event: Event = JSON.parse(message.body);
                this.handleEvent(event);
            } catch (error) {
                console.error('Failed to parse event:', error);
            }
        });

        this.updateConnectionStatus({ connected: true, connecting: false });
    }

    private handleDisconnect(): void {
        this.updateConnectionStatus({ connected: false, connecting: false });
    }

    private handleError(frame: Frame): void {
        this.updateConnectionStatus({
            connected: false,
            connecting: false,
            error: frame.body
        });
    }

    private handleEvent(event: Event): void {
        const handlers = this.subscriptions.get(event.eventType);
        if (handlers) {
            handlers.forEach(handler => {
                try {
                    handler(event);
                } catch (error) {
                    console.error('Error in event handler:', error);
                }
            });
        }
    }

    private updateConnectionStatus(status: Partial<ConnectionStatus>): void {
        this.connectionStatus = { ...this.connectionStatus, ...status };
        this.statusListeners.forEach(listener => {
            try {
                listener(this.connectionStatus);
            } catch (error) {
                console.error('Error in status listener:', error);
            }
        });
    }
}

export default EventClient;

