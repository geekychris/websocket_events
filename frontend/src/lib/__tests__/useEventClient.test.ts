import { renderHook, act } from '@testing-library/react-hooks';
import { useEventClient } from '../useEventClient';
import EventClient from '../EventClient';

// Mock EventClient
jest.mock('../EventClient');

describe('useEventClient', () => {
    let mockEventClient: jest.Mocked<EventClient>;
    const mockConfig = { url: 'ws://localhost:8080/ws' };

    beforeEach(() => {
        jest.clearAllMocks();
        
        // Setup mock implementation
        mockEventClient = {
            connect: jest.fn().mockResolvedValue(undefined),
            disconnect: jest.fn(),
            subscribe: jest.fn(),
            getSessionId: jest.fn(),
            onConnectionStatusChange: jest.fn().mockReturnValue(() => {}),
        } as any;

        (EventClient as jest.Mock).mockImplementation(() => mockEventClient);
    });

    it('should initialize EventClient with config', () => {
        renderHook(() => useEventClient(mockConfig));
        
        expect(EventClient).toHaveBeenCalledWith(mockConfig);
        expect(mockEventClient.connect).toHaveBeenCalled();
    });

    it('should setup connection status listener', () => {
        renderHook(() => useEventClient(mockConfig));
        
        expect(mockEventClient.onConnectionStatusChange).toHaveBeenCalled();
    });

    it('should cleanup on unmount', () => {
        const { unmount } = renderHook(() => useEventClient(mockConfig));
        
        unmount();
        
        expect(mockEventClient.disconnect).toHaveBeenCalled();
    });

    it('should handle event subscriptions', () => {
        const mockHandler = jest.fn();
        const mockSubscription = {
            eventType: 'TEST_EVENT',
            handler: mockHandler,
            unsubscribe: jest.fn()
        };
        
        mockEventClient.subscribe.mockReturnValue(mockSubscription);

        const { result } = renderHook(() => useEventClient(mockConfig));

        act(() => {
            result.current.subscribe('TEST_EVENT', mockHandler);
        });

        expect(mockEventClient.subscribe).toHaveBeenCalledWith('TEST_EVENT', mockHandler);
    });

    it('should maintain connection status', () => {
        const { result } = renderHook(() => useEventClient(mockConfig));
        
        expect(result.current.connectionStatus).toEqual({
            connected: false,
            connecting: false
        });

        // Simulate connection status change
        const statusListener = mockEventClient.onConnectionStatusChange.mock.calls[0][0];
        act(() => {
            statusListener({
                connected: true,
                connecting: false
            });
        });

        expect(result.current.connectionStatus).toEqual({
            connected: true,
            connecting: false
        });
    });

    it('should handle session ID retrieval', () => {
        const mockSessionId = 'test-session';
        mockEventClient.getSessionId.mockReturnValue(mockSessionId);

        const { result } = renderHook(() => useEventClient(mockConfig));
        
        expect(result.current.getSessionId()).toBe(mockSessionId);
    });

    it('should throw error when subscribing without initialized client', () => {
        (EventClient as jest.Mock).mockImplementation(() => null);
        
        const { result } = renderHook(() => useEventClient(mockConfig));
        
        expect(() => {
            result.current.subscribe('TEST_EVENT', jest.fn());
        }).toThrow('EventClient not initialized');
    });
});

