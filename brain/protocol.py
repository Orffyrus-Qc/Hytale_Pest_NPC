"""WebSocket message protocol between Hytale plugin (host) and Docker brain."""

from __future__ import annotations

from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, Field


class MsgType(str, Enum):
    HELLO = "hello"
    HELLO_ACK = "hello_ack"
    STATE = "state"  # structured game/NPC/player snapshot (preferred over raw RAM)
    PLAYER_ACTION = "player_action"  # demonstration tick from player
    MEMORY_SNAPSHOT = "memory_snapshot"  # optional host probe (sanitized)
    ACTION = "action"  # brain → game: NPC action proposal
    ACTION_RESULT = "action_result"
    CHAT = "chat"
    CHAT_REPLY = "chat_reply"
    MODE = "mode"  # observe | imitate | hybrid | autonomous
    STATUS = "status"
    ERROR = "error"
    PING = "ping"
    PONG = "pong"


class Envelope(BaseModel):
    type: MsgType
    ts: float | None = None
    session_id: str | None = None
    payload: dict[str, Any] = Field(default_factory=dict)


class Vec3(BaseModel):
    x: float = 0.0
    y: float = 0.0
    z: float = 0.0


class EntitySnap(BaseModel):
    id: str
    kind: str = "unknown"
    name: str | None = None
    pos: Vec3 = Field(default_factory=Vec3)
    hostile: bool = False
    hp: float | None = None
    dist: float | None = None


class PlayerSnap(BaseModel):
    id: str = "player"
    name: str | None = None
    pos: Vec3 = Field(default_factory=Vec3)
    hp: float | None = None
    held_item: str | None = None
    game_mode: str | None = None


class NpcSnap(BaseModel):
    id: str = "npc"
    name: str = "Pest"
    pos: Vec3 = Field(default_factory=Vec3)
    hp: float | None = None
    goal: str | None = None


class GameState(BaseModel):
    """Server-authoritative structured state (plugin bridge)."""

    world: str | None = None
    tick: int | None = None
    player: PlayerSnap | None = None
    npc: NpcSnap | None = None
    nearby: list[EntitySnap] = Field(default_factory=list)
    blocks_of_interest: list[dict[str, Any]] = Field(default_factory=list)
    threats: list[EntitySnap] = Field(default_factory=list)
    inventory_hints: list[str] = Field(default_factory=list)
    raw: dict[str, Any] = Field(default_factory=dict)


ActionName = Literal[
    "idle",
    "move_toward",
    "follow_player",
    "flee",
    "attack",
    "mine_block",
    "place_block",
    "build_base",
    "use_item",
    "pickup",
    "chat",
    "explore",
    "craft_hint",
]


class NpcAction(BaseModel):
    name: ActionName = "idle"
    target_id: str | None = None
    target_pos: Vec3 | None = None
    item: str | None = None
    text: str | None = None
    urgency: float = 0.0
    reason: str = ""
    source: str = "policy"  # policy | imitate | llm | fallback


class PlayerActionDemo(BaseModel):
    """One player demonstration frame for imitation learning."""

    action: str
    target: str | None = None
    pos: Vec3 | None = None
    item: str | None = None
    keys: list[str] = Field(default_factory=list)
    success: bool | None = None
    context: dict[str, Any] = Field(default_factory=dict)
