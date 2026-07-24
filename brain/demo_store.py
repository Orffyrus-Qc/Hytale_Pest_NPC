"""Store player demonstration sessions for imitation learning."""

from __future__ import annotations

import time
import uuid
from pathlib import Path
from typing import Any

import orjson

from brain.protocol import PlayerActionDemo


class DemoStore:
    def __init__(self, data_dir: Path, max_sessions: int = 200) -> None:
        self.dir = data_dir / "demos"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.max_sessions = max_sessions
        self.active_id: str | None = None
        self._buffer: list[dict[str, Any]] = []

    def start_session(self, meta: dict[str, Any] | None = None) -> str:
        self.flush()
        self.active_id = str(uuid.uuid4())
        self._buffer = []
        header = {
            "session_id": self.active_id,
            "started_ts": time.time(),
            "meta": meta or {},
        }
        path = self.dir / f"{self.active_id}.jsonl"
        with path.open("ab") as f:
            f.write(orjson.dumps({"type": "session_start", **header}) + b"\n")
        return self.active_id

    def record(self, demo: PlayerActionDemo, state_summary: str = "") -> None:
        if not self.active_id:
            self.start_session({"auto": True})
        row = {
            "type": "demo",
            "ts": time.time(),
            "session_id": self.active_id,
            "state_summary": state_summary[:300],
            "demo": demo.model_dump(),
        }
        self._buffer.append(row)
        path = self.dir / f"{self.active_id}.jsonl"
        with path.open("ab") as f:
            f.write(orjson.dumps(row) + b"\n")

    def flush(self) -> None:
        self._buffer.clear()
        # prune old sessions
        files = sorted(self.dir.glob("*.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in files[self.max_sessions :]:
            try:
                old.unlink()
            except OSError:
                pass

    def recent_actions(self, limit: int = 50) -> list[dict[str, Any]]:
        files = sorted(self.dir.glob("*.jsonl"), key=lambda p: p.stat().st_mtime, reverse=True)
        out: list[dict[str, Any]] = []
        for path in files:
            try:
                for line in path.read_bytes().splitlines():
                    row = orjson.loads(line)
                    if row.get("type") == "demo":
                        out.append(row)
            except Exception:
                continue
            if len(out) >= limit:
                break
        return out[-limit:]

    def action_frequencies(self) -> dict[str, int]:
        freq: dict[str, int] = {}
        for row in self.recent_actions(400):
            demo = row.get("demo") or {}
            act = str(demo.get("action", "idle"))
            freq[act] = freq.get(act, 0) + 1
        return freq

    def suggest_from_demos(self, situation: str) -> str | None:
        sit = situation.lower()
        best: tuple[float, str] | None = None
        for row in self.recent_actions(200):
            demo = row.get("demo") or {}
            act = str(demo.get("action", "idle"))
            blob = f"{row.get('state_summary','')} {demo}".lower()
            score = sum(1.0 for t in sit.split() if t and t in blob)
            if demo.get("success") is True:
                score += 0.5
            if best is None or score > best[0]:
                best = (score, act)
        if best and best[0] > 0:
            return best[1]
        # fallback: most common demo action
        freq = self.action_frequencies()
        if not freq:
            return None
        return max(freq.items(), key=lambda kv: kv[1])[0]

    def stats(self) -> dict[str, Any]:
        files = list(self.dir.glob("*.jsonl"))
        return {
            "sessions": len(files),
            "active_session": self.active_id,
            "action_freq": self.action_frequencies(),
        }
