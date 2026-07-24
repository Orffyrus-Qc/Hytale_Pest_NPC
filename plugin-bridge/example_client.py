#!/usr/bin/env python3
"""Minimal host-side WebSocket client to smoke-test the Docker brain without a Java plugin."""

from __future__ import annotations

import asyncio
import json
import time

import websockets


URI = "ws://127.0.0.1:8766"


async def main() -> None:
    async with websockets.connect(URI) as ws:
        await ws.send(json.dumps({"type": "hello", "ts": time.time(), "payload": {"client": "example"}}))
        print("<<", await ws.recv())

        state = {
            "type": "state",
            "ts": time.time(),
            "payload": {
                "state": {
                    "world": "test",
                    "tick": 1,
                    "player": {
                        "id": "p1",
                        "name": "You",
                        "pos": {"x": 0, "y": 64, "z": 0},
                        "hp": 0.8,
                        "held_item": "pickaxe",
                    },
                    "npc": {"id": "n1", "name": "Pest", "pos": {"x": 1, "y": 64, "z": 0}, "hp": 1.0},
                    "nearby": [],
                    "threats": [
                        {
                            "id": "m1",
                            "kind": "hostile",
                            "name": "Trork",
                            "pos": {"x": 3, "y": 64, "z": 1},
                            "hostile": True,
                            "hp": 0.5,
                            "dist": 3.0,
                        }
                    ],
                    "blocks_of_interest": [],
                    "inventory_hints": ["wood"],
                }
            },
        }
        await ws.send(json.dumps(state))
        print("<<", await ws.recv())

        demo = {
            "type": "player_action",
            "ts": time.time(),
            "payload": {
                "demo": {
                    "action": "attack",
                    "target": "m1",
                    "success": True,
                    "keys": ["LMB"],
                }
            },
        }
        await ws.send(json.dumps(demo))
        print("<<", await ws.recv())


if __name__ == "__main__":
    asyncio.run(main())
