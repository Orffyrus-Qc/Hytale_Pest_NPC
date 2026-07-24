"""HTTP API for status, learning control, file search, and wiki search."""

from __future__ import annotations

from typing import Any

from fastapi import Body, FastAPI, Query
from pydantic import BaseModel, Field

from brain.service import BrainService


class ChatIn(BaseModel):
    text: str = Field(..., min_length=1)


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

    @app.get("/search-wiki")
    async def search_wiki(q: str = Query(""), limit: int = 5) -> dict[str, Any]:
        if not brain.wiki.enabled:
            return {
                "query": q,
                "enabled": False,
                "hits": [],
                "error": "wiki_search is disabled in harness tools",
            }
        hits = await brain.wiki.search(q, limit=limit)
        return {
            "query": q,
            "enabled": True,
            "hits": hits,
            "error": brain.wiki.last_error or None,
        }

    @app.post("/mode/{mode}")
    def set_mode(mode: str) -> dict[str, str]:
        brain.policy.set_mode(mode)
        return {"mode": brain.policy.mode}

    @app.post("/chat")
    async def chat(payload: ChatIn = Body(...)) -> dict[str, Any]:
        reply, action = await brain.answer_chat(payload.text)
        return {
            "reply": reply,
            "action": action.model_dump(),
            "wiki": brain.wiki.status() if brain.wiki.enabled else {"enabled": False},
        }

    @app.post("/distill")
    def distill() -> dict[str, Any]:
        patterns = brain.bank.distill()
        return {"patterns": patterns[:20]}

    return app
