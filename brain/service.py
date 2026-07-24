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
from brain.wiki_search import WikiSearch

log = logging.getLogger("hytale_ai.service")


class BrainService:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.settings.ensure_dirs()
        self.harness = load_harness(settings.harness_path)
        if self.harness.get("npc_name"):
            self.settings.npc_name = str(self.harness["npc_name"])

        tools = self.harness.get("tools") or {}
        wiki_on = bool(tools.get("wiki_search", False))
        self.files = GameFileInspector(settings.hytale_mount, settings.data_dir / "file_index")
        self.wiki = WikiSearch(
            enabled=wiki_on,
            api_url=settings.wiki_api_url,
            cache_dir=settings.data_dir / "wiki_cache",
        )
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
            wiki=self.wiki,
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
        log.info(
            "Wiki search: enabled=%s api=%s",
            self.wiki.enabled,
            self.wiki.api_url,
        )

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

    async def answer_chat(self, text: str, state: GameState | None = None) -> tuple[str, NpcAction]:
        """Chat reply: game questions → Hytale wiki (strict relevance); else short companion reply."""
        state = state or self.last_state or GameState()
        query = WikiSearch.extract_query_from_chat(text)

        # --- Game knowledge: Hytale wiki only when the message is a real question/topic ---
        if self.wiki.enabled and WikiSearch.is_game_question(text):
            wiki_answer = await self.wiki.answer_player_question(text, limit=4)
            action = NpcAction(
                name="chat",
                urgency=0.2,
                reason=f"wiki Q&A: {query[:80]}",
                text=wiki_answer or "",
                source="wiki",
            )
            self.last_action = action
            if wiki_answer:
                polished = await self.policy.polish_wiki_answer(query, wiki_answer, state)
                return (polished or wiki_answer), action
            # No relevant hit — say so (do not invent unrelated pages)
            return (
                f"I looked on the Hytale wiki for “{query}” but nothing matched closely. "
                "Name a specific item, mob, zone, or craft."
            ), action

        # Chit-chat / acknowledgements while in conversation
        qlow = (query or "").lower().strip(" ?!.…,")
        if qlow in {"ok", "okay", "k", "kk", "thanks", "thank you", "thx", "ty", "cool", "nice", "got it", "gotcha"}:
            action = NpcAction(name="chat", urgency=0.1, reason="ack", source="policy")
            self.last_action = action
            if self.wiki.last_topic_title:
                return f"Anytime. Last wiki topic was {self.wiki.last_topic_title}.", action
            return "Got it.", action

        action = await self.decide_and_learn(state)
        # Free-form chat: only wiki if it looks like a topic phrase with good hits
        if self.wiki.enabled and query and not WikiSearch._looks_like_command(query):
            wiki_answer = await self.wiki.answer_player_question(text, limit=3)
            if wiki_answer and "nothing matched" not in wiki_answer.lower() and "couldn't reach" not in wiki_answer.lower():
                return wiki_answer, action

        reply = await self.policy.maybe_chat_reply(text, state, action)
        if not reply:
            reply = (
                f"I'm {self.settings.npc_name}. "
                f"Ask me a Hytale question (e.g. what is a crude bed?) "
                f"or give me a command (hunt, follow, build a base)."
            )
        return reply, action

    def status(self) -> dict[str, Any]:
        tools = self.harness.get("tools") or {}
        return {
            "npc": self.settings.npc_name,
            "mode": self.policy.mode,
            "uptime_s": round(time.time() - self.started_ts, 1),
            "sim_mode": self.settings.sim_mode,
            "ws_clients": self.ws_clients,
            "files": self.files.status(),
            "wiki": self.wiki.status(),
            "tools": {
                "file_inspect": bool(tools.get("file_inspect", True)),
                "wiki_search": self.wiki.enabled,
            },
            "experience": self.bank.stats(),
            "demos": self.demos.stats(),
            "last_action": self.last_action.model_dump() if self.last_action else None,
            "last_situation": self.policy.situation_text(self.last_state) if self.last_state else None,
            "llm": {
                "openai": bool(self.settings.openai_api_key),
                "ollama": bool(self.settings.ollama_base_url),
            },
        }
