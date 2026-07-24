"""Synthetic Hytale session for testing without a live plugin."""

from __future__ import annotations

import asyncio
import logging
import math
import random
import time

from brain.protocol import (
    EntitySnap,
    GameState,
    NpcSnap,
    PlayerActionDemo,
    PlayerSnap,
    Vec3,
)

log = logging.getLogger("hytale_ai.sim")


class SimWorld:
    def __init__(self) -> None:
        self.t = 0
        self.player = PlayerSnap(id="player1", name="Traveler", pos=Vec3(x=0, y=64, z=0), hp=1.0, held_item="sword")
        self.npc = NpcSnap(id="npc1", name="Pest", pos=Vec3(x=2, y=64, z=1), hp=1.0)
        self.threat_alive = True

    def step(self) -> GameState:
        self.t += 1
        # Player wanders
        self.player.pos.x += math.sin(self.t / 8.0) * 0.4
        self.player.pos.z += math.cos(self.t / 11.0) * 0.3
        # NPC lags behind until brain issues follow (sim just drifts slightly)
        self.npc.pos.x += (self.player.pos.x - self.npc.pos.x) * 0.05
        self.npc.pos.z += (self.player.pos.z - self.npc.pos.z) * 0.05

        threats = []
        nearby = []
        if self.threat_alive and self.t % 40 > 25:
            threats.append(
                EntitySnap(
                    id="mob1",
                    kind="hostile",
                    name="Trork",
                    pos=Vec3(x=self.player.pos.x + 3, y=64, z=self.player.pos.z + 2),
                    hostile=True,
                    hp=0.6,
                    dist=3.5,
                )
            )
        nearby.append(
            EntitySnap(
                id="sheep1",
                kind="passive",
                name="Sheep",
                pos=Vec3(x=self.player.pos.x - 5, y=64, z=self.player.pos.z),
                hostile=False,
                dist=5.0,
            )
        )
        return GameState(
            world="sim_valley",
            tick=self.t,
            player=self.player,
            npc=self.npc,
            nearby=nearby,
            threats=threats,
            blocks_of_interest=[{"id": "stone", "pos": {"x": 10, "y": 64, "z": 4}}],
            inventory_hints=["wood", "stone"],
        )

    def player_demo(self) -> PlayerActionDemo:
        if self.t % 40 > 25 and self.threat_alive:
            return PlayerActionDemo(action="attack", target="mob1", success=True, keys=["LMB"])
        choices = [
            PlayerActionDemo(action="follow_player", success=True, keys=["W"]),
            PlayerActionDemo(action="mine_block", target="stone", success=True, keys=["LMB"]),
            PlayerActionDemo(action="explore", success=True, keys=["W", "A"]),
            PlayerActionDemo(action="pickup", success=True, keys=["E"]),
        ]
        return random.choice(choices)


async def run_sim_loop(brain: "BrainService", interval: float = 0.5) -> None:  # noqa: F821
    world = SimWorld()
    log.info("SIM_MODE active — generating synthetic player demos + states")
    while True:
        state = world.step()
        demo = world.player_demo()
        await brain.ingest_state(state)
        await brain.ingest_player_demo(demo, state)
        action = await brain.decide_and_learn(state)
        # crude sim outcome
        ok = action.name in {"follow_player", "attack", "explore", "mine_block", "flee", "idle"}
        if action.name == "attack" and state.threats:
            world.threat_alive = random.random() > 0.3
        brain.policy.learn_from_result(state, action, result_ok=ok)
        await asyncio.sleep(interval)
