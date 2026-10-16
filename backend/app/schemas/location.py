from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel, Field, ConfigDict


class LocationIngestItem(BaseModel):
    """A single location trace point signed by Guardian ECDSA key."""
    sequence_number: int = Field(..., ge=1, description="Strictly monotonic sequence number")
    latitude: float = Field(..., ge=-90.0, le=90.0)
    longitude: float = Field(..., ge=-180.0, le=180.0)
    accuracy: float = Field(..., ge=0.0, description="Horizontal accuracy in meters")
    altitude: Optional[float] = None
    velocity: Optional[float] = None
    capture_time: datetime = Field(..., description="UTC timestamp when GNSS fix was acquired")
    signature: str = Field(..., description="Base64 ASN.1 DER ECDSA signature over canonical payload")


class LocationBatchIngestRequest(BaseModel):
    """Batch of location traces dispatched by Guardian sync worker."""
    device_id: str
    events: List[LocationIngestItem] = Field(..., min_length=1)


class LocationBatchIngestResponse(BaseModel):
    device_id: str
    acknowledged_sequences: List[int]
    rejected_sequences: List[int]
    status: str


class LocationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    protected_device_id: str
    latitude: float
    longitude: float
    accuracy: float
    altitude: Optional[float]
    velocity: Optional[float]
    capture_time: datetime
    server_receipt_time: datetime
    sequence_number: int
