"""Core brain service wiring all learning modules."""

from __future__ import annotations

import logging
import time
from typing import Any

from brain.config import Settings, load_harness
from brain.demo_store import DemoStore
from brain.experience import ExperienceBank
from brain.file_inspector import GameFileInspector
from brain.policy import PlayPolicy
from brain.protocol import GameState, NpcAction, PlayerActionDemo

log = logging.getLogger("hytale_ai.service")


class BrainService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.settings.ensure_dirs()
        self.harness = load_harness(settings.harness_path)
        if self.harness.get("npc_name"):
            self.settings.npc_name = str(self.harness["npc_name"])

        self.files = GameFileInspector(settings.hytale_mount, settings.data_dir / "file_index")
        self.bank = ExperienceBank(
            settings.data_dir,
            max_entries=int(self.harness.get("memory", {}).get("experience_max_entries", 5000)),
        )
        self.demos = DemoStore(
            settings.data_dir,
            max_sessions=int(self.harness.get("memory", {}).get("demo_max_sessions", 200)),
        )
        self.policy = PlayPolicy(
            harness=self.harness,
            bank=self.bank,
            demos=self.demos,
            files=self.files,
            settings=settings,
        )
        self.last_state: GameState | None = None
        self.last_action: NpcAction | None = None
        self.started_ts = time.time()
        self.ws_clients = 0

    def bootstrap(self) -> None:
        log.info("Bootstrapping brain for NPC=%s", self.settings.npc_name)
        self.files.load()
        if not self.files.hits:
            try:
                self.files.scan()
            except Exception as e:
                log.warning("Initial file scan failed: %s", e)
        self.demos.start_session({"npc": self.settings.npc_name, "boot": True})
        # Teach tool use + bed/spawn curriculum (durable lessons)
        try:
            n = self.policy.seed_curriculum_lessons()
            log.info("Curriculum lessons seeded: %s", n)
        except Exception as e:
            log.warning("Curriculum seed failed: %s", e)
        log.info("File knowledge: %s", self.files.knowledge_brief())

    async def ingest_state(self, state: GameState) -> None:
        self.last_state = state

    async def ingest_player_demo(self, demo: PlayerActionDemo, state: GameState | None = None) -> None:
        summary = self.policy.situation_text(state) if state else ""
        self.demos.record(demo, state_summary=summary)
        # Treat successful demos as positive experience
        reward = 1.0 if demo.success is not False else 0.2
        self.bank.add(
            situation=summary or demo.action,
            action=demo.action,
            reward=reward,
            obs=demo.context,
            diagnosis="player_demonstration",
            source="player",
        )

    async def decide_and_learn(self, state: GameState | None = None) -> NpcAction:
        state = state or self.last_state
        if state is None:
            action = NpcAction(name="idle", reason="no state yet", source="fallback")
            self.last_action = action
            return action
        action = self.policy.decide(state)
        # Validate against harness allow-list conceptually
        if self.harness.get("output", {}).get("validate_actions", True):
            if not action.name:
                action = NpcAction(name="idle", reason="invalid empty action", source="fallback")
        self.last_action = action
        self.last_state = state
        return action

    def status(self) -> dict[str, Any]:
        return {
            "npc": self.settings.npc_name,
            "mode": self.policy.mode,
            "uptime_s": round(time.time() - self.started_ts, 1),
            "sim_mode": self.settings.sim_mode,
            "ws_clients": self.ws_clients,
            "files": self.files.status(),
            "experience": self.bank.stats(),
            "demos": self.demos.stats(),
            "last_action": self.last_action.model_dump() if self.last_action else None,
            "last_situation": self.policy.situation_text(self.last_state) if self.last_state else None,
            "llm": {
                "openai": bool(self.settings.openai_api_key),
                "ollama": bool(self.settings.ollama_base_url),
            },
        }
