from datetime import datetime
import os
from typing import Any, Optional

from fastapi import FastAPI, Header, HTTPException, Query
from pydantic import BaseModel, Field
from sqlalchemy import create_engine, String, Float, DateTime, JSON, Integer, select
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, Session

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./bridge.db")
MASTER_KEY = os.getenv("BRIDGE_MASTER_KEY", "dev-only-change-me")

engine = create_engine(DATABASE_URL, connect_args={"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {})

class Base(DeclarativeBase):
    pass

class MetricRow(Base):
    __tablename__ = "metric_rows"
    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[str] = mapped_column(String(128), index=True)
    metric: Mapped[str] = mapped_column(String(64), index=True)
    start_time: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    end_time: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True), nullable=True)
    value: Mapped[Optional[float]] = mapped_column(Float, nullable=True)
    unit: Mapped[Optional[str]] = mapped_column(String(32), nullable=True)
    source_package: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    payload: Mapped[dict] = mapped_column(JSON, default=dict)

Base.metadata.create_all(engine)

class MetricIn(BaseModel):
    metric: str
    startTime: datetime
    endTime: Optional[datetime] = None
    value: Optional[float] = None
    unit: Optional[str] = None
    sourcePackage: Optional[str] = None
    metadata: dict[str, Any] = Field(default_factory=dict)

class IngestRequest(BaseModel):
    userId: str
    metrics: list[MetricIn]

app = FastAPI(title="Health Connect ChatGPT Bridge", version="0.1.0")

def require_token(auth: Optional[str]):
    if not auth or not auth.startswith("Bearer "):
        raise HTTPException(401, "Missing bearer token")
    # MVP only. Replace with per-user OAuth/OIDC JWT verification.
    if auth.removeprefix("Bearer ").strip() == "":
        raise HTTPException(401, "Invalid bearer token")

@app.post("/v1/ingest")
def ingest(body: IngestRequest, authorization: Optional[str] = Header(None)):
    require_token(authorization)
    with Session(engine) as db:
        for m in body.metrics:
            db.add(MetricRow(
                user_id=body.userId,
                metric=m.metric,
                start_time=m.startTime,
                end_time=m.endTime,
                value=m.value,
                unit=m.unit,
                source_package=m.sourcePackage,
                payload=m.metadata,
            ))
        db.commit()
    return {"ok": True, "accepted": len(body.metrics)}

@app.get("/v1/users/{user_id}/metrics")
def metrics(
    user_id: str,
    metric: str = Query(...),
    start: datetime = Query(...),
    end: datetime = Query(...),
    authorization: Optional[str] = Header(None),
):
    require_token(authorization)
    with Session(engine) as db:
        q = select(MetricRow).where(
            MetricRow.user_id == user_id,
            MetricRow.metric == metric,
            MetricRow.start_time >= start,
            MetricRow.start_time < end,
        ).order_by(MetricRow.start_time.asc())
        rows = db.scalars(q).all()
        return [{
            "metric": r.metric,
            "start_time": r.start_time,
            "end_time": r.end_time,
            "value": r.value,
            "unit": r.unit,
            "source_package": r.source_package,
            "metadata": r.payload,
        } for r in rows]

@app.get("/v1/users/{user_id}/summary")
def summary(user_id: str, authorization: Optional[str] = Header(None)):
    require_token(authorization)
    with Session(engine) as db:
        rows = db.scalars(
            select(MetricRow).where(MetricRow.user_id == user_id)
            .order_by(MetricRow.start_time.desc()).limit(500)
        ).all()
    latest = {}
    for r in rows:
        latest.setdefault(r.metric, {
            "time": r.start_time,
            "value": r.value,
            "unit": r.unit,
            "source_package": r.source_package
        })
    return {"user_id": user_id, "latest": latest}
