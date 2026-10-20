import logging
from typing import Dict, Set, Any
from fastapi import WebSocket

logger = logging.getLogger("uvicorn.error")


MAX_CONNECTIONS_PER_DEVICE = 10


class LocationWebSocketManager:
    """
    Manages active WebSocket connections subscribed to real-time device location telemetry.
    Thread-safe registry mapping device_id -> Set[WebSocket].
    """

    def __init__(self):
        self._active_connections: Dict[str, Set[WebSocket]] = {}

    async def connect(self, device_id: str, websocket: WebSocket, subprotocol: str = None) -> bool:
        """Register and accept an authenticated WebSocket connection if under limits."""
        if device_id in self._active_connections and len(self._active_connections[device_id]) >= MAX_CONNECTIONS_PER_DEVICE:
            logger.warning(f"Rejecting WS connection for device {device_id}: Max connection limit ({MAX_CONNECTIONS_PER_DEVICE}) reached.")
            return False

        if subprotocol:
            await websocket.accept(subprotocol=subprotocol)
        else:
            await websocket.accept()
        if device_id not in self._active_connections:
            self._active_connections[device_id] = set()
        self._active_connections[device_id].add(websocket)
        logger.info(f"WebSocket client connected for device {device_id}. Total listeners: {len(self._active_connections[device_id])}")
        return True

    def disconnect(self, device_id: str, websocket: WebSocket) -> None:
        """Unregister a disconnected WebSocket connection."""
        if device_id in self._active_connections:
            self._active_connections[device_id].discard(websocket)
            if not self._active_connections[device_id]:
                del self._active_connections[device_id]
        logger.info(f"WebSocket client disconnected for device {device_id}.")

    async def broadcast_location(self, device_id: str, payload: Dict[str, Any]) -> None:
        """
        Broadcast verified location update to all active subscribers for the given device.
        Silently cleans up any dead/stale connections.
        """
        if device_id not in self._active_connections:
            return

        dead_connections = set()
        for connection in list(self._active_connections[device_id]):
            try:
                await connection.send_json(payload)
            except Exception as e:
                logger.warning(f"Error broadcasting to WebSocket for device {device_id}: {e}")
                dead_connections.add(connection)

        for dead in dead_connections:
            self.disconnect(device_id, dead)

    def get_listener_count(self, device_id: str) -> int:
        """Return number of active WebSocket connections for a device."""
        return len(self._active_connections.get(device_id, set()))


# Global singleton instance
ws_manager = LocationWebSocketManager()
