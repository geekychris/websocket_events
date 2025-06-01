import { Client } from '@stomp/stompjs';
import EventClient from '../EventClient';
import { Event } from '../types';

// Mock SockJS
jest.mock('sockjs-client', () => {
    return jest.fn().mockImplementation(() => ({
        close: jest.fn()
    }));
});

// Mock STOMP Client
jest.mock('@stomp/stompjs', () => {
    return {
        Client: jest.fn().mockImplementation(() => ({
            activate: jest.fn(),
            deactivate: jest.fn(),
            subscribe: jest.fn(),
            onConnect: null,
            onDisconnect: null,
            onStompError: null
        }))
    };
});

describe('EventClient', () => {
    let client: EventClient;
    let mockStompClient: any;

    beforeEach(() => {
        jest.clearAllMocks();
        client = new EventClient({ url: 'ws://localhost:8080/ws' });
        mockStompClient = (Client as jest.Mock).mock.results[0].value;
    });

    describe('Connection Management', () => {
        it('should connect successfully', async () => {
            const connectPromise = client.connect();
            
            // Simulate successful connection
            mockStompClient.onConnect({ headers: { 'session-id': 'test-session' } });
            
            await connectPromise;
            expect(mockStompClient.activate).toHaveBeenCalled();
        });

        it('should handle connection timeout', async () => {
            jest.useFakeTimers();
            const connectPromise = client.connect();
            
            // Advance time past timeout
            jest.advanceTimersByTime(11000);
            
            await expect(connectPromise).rejects.toThrow('Connection timeout');
            jest.useRealTimers();
        });

        it('should handle connection errors', async () => {
            mockStompClient.activate.mockImplementation(() => {
                throw new Error('Connection failed');
            });

            await expect(client.connect()).rejects.toThrow('Connection failed');
        });

        it('should disconnect properly', () => {
            client.disconnect();
            expect(mockStompClient.deactivate).toHaveBeenCalled();
        });
    });

    describe('Event Subscription', () => {
        const testEvent: Event = {
            eventType: 'TEST_EVENT',
            sessionId: 'test-session',
            payload: { test: 'data' },
            timestamp: new Date().toISOString()
        };

        beforeEach(async () => {
            const connectPromise = client.connect();
            mockStompClient.onConnect({ headers: { 'session-id': 'test-session' } });
            await connectPromise;
        });

        it('should handle event subscriptions', () => {
            const handler = jest.fn();
            const subscription = client.subscribe('TEST_EVENT', handler);

            // Simulate receiving an event
            mockStompClient.subscribe.mock.calls[0][1]({ body: JSON.stringify(testEvent) });

            expect(handler).toHaveBeenCalledWith(testEvent);
            expect(subscription.eventType).toBe('TEST_EVENT');
            expect(typeof subscription.unsubscribe).toBe('function');
        });

        it('should remove subscription when unsubscribed', () => {
            const handler = jest.fn();
            const subscription = client.subscribe('TEST_EVENT', handler);
            
            subscription.unsubscribe();
            
            // Simulate receiving an event after unsubscribe
            mockStompClient.subscribe.mock.calls[0][1]({ body: JSON.stringify(testEvent) });
            
            expect(handler).not.toHaveBeenCalled();
        });

        it('should handle multiple subscriptions to same event type', () => {
            const handler1 = jest.fn();
            const handler2 = jest.fn();
            
            client.subscribe('TEST_EVENT', handler1);
            client.subscribe('TEST_EVENT', handler2);
            
            // Simulate receiving an event
            mockStompClient.subscribe.mock.calls[0][1]({ body: JSON.stringify(testEvent) });
            
            expect(handler1).toHaveBeenCalledWith(testEvent);
            expect(handler2).toHaveBeenCalledWith(testEvent);
        });
    });

    describe('Error Handling', () => {
        it('should handle malformed event data', () => {
            const handler = jest.fn();
            client.subscribe('TEST_EVENT', handler);
            
            // Simulate receiving malformed JSON
            mockStompClient.subscribe.mock.calls[0][1]({ body: 'invalid-json' });
            
            expect(handler).not.toHaveBeenCalled();
        });

        it('should handle errors in event handlers', () => {
            const consoleError = jest.spyOn(console, 'error').mockImplementation();
            const handler = jest.fn().mockImplementation(() => {
                throw new Error('Handler error');
            });
            
            client.subscribe('TEST_EVENT', handler);
            
            // Simulate receiving an event
            mockStompClient.subscribe.mock.calls[0][1]({
                body: JSON.stringify({
                    eventType: 'TEST_EVENT',
                    sessionId: 'test-session',
                    payload: {}
                })
            });
            
            expect(consoleError).toHaveBeenCalled();
            consoleError.mockRestore();
        });
    });
});

