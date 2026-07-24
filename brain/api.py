"""HTTP API for status, learning control, and file search."""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Query
from pydantic import BaseModel, Field

from brain.service import BrainService


def create_app(brain: BrainService) -> FastAPI:
    app = FastAPI(title="Hytale AI NPC Brain", version="0.1.0")

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok", "npc": brain.settings.npc_name}

    @app.get("/status")
    def status() -> dict[str, Any]:
        return brain.status()

    @app.post("/scan-files")
    def scan_files(max_files: int = 400) -> dict[str, Any]:
        hits = brain.files.scan(max_files=max_files)
        return {"count": len(hits), "sample": hits[:10]}

    @app.get("/search-files")
    def search_files(q: str = Query(""), limit: int = 12) -> dict[str, Any]:
        return {"query": q, "hits": brain.files.search(q, limit=limit)}

    @app.post("/mode/{mode}")
    def set_mode(mode: str) -> dict[str, str]:
        brain.policy.set_mode(mode)
        return {"mode": brain.policy.mode}

    class ChatIn(BaseModel):
        text: str = Field(..., min_length=1)

    @app.post("/chat")
    async def chat(body: ChatIn) -> dict[str, Any]:
        from brain.protocol import GameState

        state = brain.last_state or GameState()
        action = await brain.decide_and_learn(state)
        reply = await brain.policy.maybe_narrate(state, action)
        if not reply:
            reply = f"{brain.settings.npc_name}: action={action.name} — {action.reason}"
        return {"reply": reply, "action": action.model_dump()}

    @app.post("/distill")
    def distill() -> dict[str, Any]:
        patterns = brain.bank.distill()
        return {"patterns": patterns[:20]}

    return app
