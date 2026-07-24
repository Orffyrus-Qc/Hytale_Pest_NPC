"""WebSocket server for Hytale plugin bridge."""

from __future__ import annotations

import json
import logging
import time
from typing import Any

from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

from brain.protocol import (
    Envelope,
    GameState,
    MsgType,
    NpcAction,
    PlayerActionDemo,
)
from brain.service import BrainService

log = logging.getLogger("hytale_ai.ws")


class WsBridge:
    def __init__(self, brain: BrainService, host: str, port: int) -> None:
        self.brain = brain
        self.host = host
        self.port = port

    async def run(self) -> None:
        log.info("WebSocket bridge listening on %s:%s", self.host, self.port)
        async with serve(self.handler, self.host, self.port, ping_interval=20, ping_timeout=20):
            await _forever()

    async def handler(self, ws: ServerConnection) -> None:
        self.brain.ws_clients += 1
        session_id = f"ws-{int(time.time() * 1000)}"
        log.info("Plugin connected session=%s", session_id)
        try:
            await self._send(
                ws,
                Envelope(
                    type=MsgType.HELLO_ACK,
                    session_id=session_id,
                    ts=time.time(),
                    payload={
                        "npc": self.brain.settings.npc_name,
                        "protocol": "hytale_ai_npc_v1",
                        "modes": ["observe", "imitate", "hybrid", "autonomous"],
                    },
                ),
            )
            async for raw in ws:
                await self._on_message(ws, raw, session_id)
        except ConnectionClosed:
            log.info("Plugin disconnected session=%s", session_id)
        finally:
            self.brain.ws_clients = max(0, self.brain.ws_clients - 1)

    async def _on_message(self, ws: ServerConnection, raw: str | bytes, session_id: str) -> None:
        try:
            data = json.loads(raw)
            env = Envelope.model_validate(data)
        except Exception as e:
            await self._send(
                ws,
                Envelope(type=MsgType.ERROR, session_id=session_id, ts=time.time(), payload={"error": str(e)}),
            )
            return

        t = env.type
        p = env.payload or {}

        if t == MsgType.PING:
            await self._send(ws, Envelope(type=MsgType.PONG, session_id=session_id, ts=time.time()))
            return

        if t == MsgType.HELLO:
            await self._send(
                ws,
                Envelope(
                    type=MsgType.HELLO_ACK,
                    session_id=session_id,
                    ts=time.time(),
                    payload={"npc": self.brain.settings.npc_name},
                ),
            )
            return

        if t == MsgType.MODE:
            mode = str(p.get("mode", "hybrid"))
            self.brain.policy.set_mode(mode)
            await self._send(
                ws,
                Envelope(
                    type=MsgType.STATUS,
                    session_id=session_id,
                    ts=time.time(),
                    payload={"mode": self.brain.policy.mode},
                ),
            )
            return

        if t == MsgType.STATE:
            state = GameState.model_validate(p.get("state") or p)
            await self.brain.ingest_state(state)
            action = await self.brain.decide_and_learn(state)
            await self._send(
                ws,
                Envelope(
                    type=MsgType.ACTION,
                    session_id=session_id,
                    ts=time.time(),
                    payload={"action": action.model_dump()},
                ),
            )
            return

        if t == MsgType.PLAYER_ACTION:
            demo = PlayerActionDemo.model_validate(p.get("demo") or p)
            state = None
            if p.get("state"):
                state = GameState.model_validate(p["state"])
                await self.brain.ingest_state(state)
            await self.brain.ingest_player_demo(demo, state)
            await self._send(
                ws,
                Envelope(
                    type=MsgType.STATUS,
                    session_id=session_id,
                    ts=time.time(),
                    payload={"recorded": True, "action": demo.action},
                ),
            )
            return

        if t == MsgType.ACTION_RESULT:
            ok = p.get("ok")
            if self.brain.last_state and self.brain.last_action:
                self.brain.policy.learn_from_result(
                    self.brain.last_state,
                    self.brain.last_action,
                    result_ok=bool(ok) if ok is not None else None,
                    diagnosis=str(p.get("diagnosis", "")),
                )
            return

        if t == MsgType.MEMORY_SNAPSHOT:
            # Optional sanitized host probe: store as observation only
            summary = str(p.get("summary", "memory_snapshot"))[:500]
            self.brain.bank.add(
                situation=summary,
                action="observe",
                reward=0.4,
                obs={"keys": list((p.get("fields") or {}).keys())[:30]},
                diagnosis="host_memory_probe",
                source="memory",
            )
            return

        if t == MsgType.CHAT:
            text = str(p.get("text", ""))
            state = self.brain.last_state or GameState()
            reply, action = await self.brain.answer_chat(text, state)
            await self._send(
                ws,
                Envelope(
                    type=MsgType.CHAT_REPLY,
                    session_id=session_id,
                    ts=time.time(),
                    payload={"text": reply, "action": action.model_dump()},
                ),
            )
            return

        if t == MsgType.STATUS:
            await self._send(
                ws,
                Envelope(
                    type=MsgType.STATUS,
                    session_id=session_id,
                    ts=time.time(),
                    payload=self.brain.status(),
                ),
            )
            return

        await self._send(
            ws,
            Envelope(
                type=MsgType.ERROR,
                session_id=session_id,
                ts=time.time(),
                payload={"error": f"unhandled type {t}"},
            ),
        )

    async def _send(self, ws: ServerConnection, env: Envelope) -> None:
        await ws.send(env.model_dump_json())


async def _forever() -> None:
    import asyncio

    while True:
        await asyncio.sleep(3600)
