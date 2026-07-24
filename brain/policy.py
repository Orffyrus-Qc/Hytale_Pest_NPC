"""Policy: fight near, base (closed room+door+bed), tools, hunt, follow."""

from __future__ import annotations

import logging
import time
from typing import Any

import httpx

from brain.demo_store import DemoStore
from brain.experience import ExperienceBank
from brain.file_inspector import GameFileInspector
from brain.protocol import GameState, NpcAction

log = logging.getLogger("hytale_ai.policy")

ITEM_PICK = "Tool_Pickaxe_Crude"
ITEM_AXE = "Weapon_Axe_Stone_Trork"
ITEM_SWORD = "Weapon_Sword_Stone_Trork"
ITEM_WOOD = "Wood_Softwood_Planks"
ITEM_DOOR = "Furniture_Crude_Door"
ITEM_BED = "Furniture_Crude_Bed"
ITEM_FIBRE = "Ingredient_Fibre"
ITEM_HIDE = "Ingredient_Hide_Light"

# Don't re-issue build_base more often than this (plugin also debounces)
_BUILD_ACTION_COOLDOWN_S = 12.0


class PlayPolicy:
    def __init__(
        self,
        *,
        harness: dict[str, Any],
        bank: ExperienceBank,
        demos: DemoStore,
        files: GameFileInspector,
        settings: Any,
    ) -> None:
        self.harness = harness
        self.bank = bank
        self.demos = demos
        self.files = files
        self.settings = settings
        self.mode = str(harness.get("orchestration", {}).get("mode", "hybrid"))
        self._last_build_action_ts = 0.0

    def set_mode(self, mode: str) -> None:
        if mode in {"observe", "imitate", "hybrid", "autonomous"}:
            self.mode = mode

    def situation_text(self, state: GameState) -> str:
        parts: list[str] = []
        if state.player:
            parts.append(f"player_hp={state.player.hp}")
            if state.player.held_item:
                parts.append(f"held={state.player.held_item}")
        if state.threats:
            parts.append(f"threats={len(state.threats)}")
            t0 = state.threats[0]
            parts.append(f"nearest_threat={t0.kind}:{t0.name or t0.id}")
            if t0.dist is not None:
                parts.append(f"threat_dist={t0.dist:.1f}")
        if state.nearby:
            parts.append(f"nearby={len(state.nearby)}")
        if state.inventory_hints:
            parts.append("inv=" + ",".join(state.inventory_hints[:10]))
        if state.blocks_of_interest:
            parts.append(f"blocks={len(state.blocks_of_interest)}")
        raw = state.raw or {}
        if raw.get("base_built"):
            parts.append("base_built=1")
        if raw.get("base_bed"):
            parts.append("base_bed=1")
        if raw.get("base_building"):
            parts.append("base_building=1")
        if raw.get("has_wood"):
            parts.append("has_wood=1")
        if raw.get("has_door"):
            parts.append("has_door=1")
        if raw.get("has_bed"):
            parts.append("has_bed=1")
        if raw.get("can_auto_home"):
            parts.append("can_auto_home=1")
        if raw.get("force_follow"):
            parts.append("force_follow=1")
        if raw.get("idle_hunt"):
            parts.append("idle_hunt=1")
        if raw.get("home_wait_s") is not None:
            parts.append(f"home_wait_s={raw.get('home_wait_s')}")
        if raw.get("owner_dist") is not None:
            parts.append(f"owner_dist={raw.get('owner_dist')}")
        goal = state.npc.goal if state.npc and state.npc.goal else ""
        if goal:
            parts.append(f"goal={goal}")
        return " ".join(parts) or "idle_world"

    def _inv(self, state: GameState) -> str:
        return ",".join(state.inventory_hints or [])

    def _has(self, state: GameState, item: str) -> bool:
        if item in self._inv(state):
            return True
        return any(item in h for h in (state.inventory_hints or []))

    def _has_wood(self, state: GameState) -> bool:
        raw = state.raw or {}
        if raw.get("has_wood"):
            return True
        inv = self._inv(state)
        return ("Plank" in inv) or (ITEM_WOOD in inv) or ("Wood_Softwood" in inv)

    def _has_door(self, state: GameState) -> bool:
        raw = state.raw or {}
        if raw.get("has_door"):
            return True
        return "Door" in self._inv(state) or self._has(state, ITEM_DOOR)

    def _has_bed(self, state: GameState) -> bool:
        raw = state.raw or {}
        if raw.get("has_bed"):
            return True
        return self._has(state, ITEM_BED)

    def _base_done(self, state: GameState) -> bool:
        raw = state.raw or {}
        return bool(raw.get("base_built")) and bool(raw.get("base_bed"))

    def _base_built_only(self, state: GameState) -> bool:
        return bool((state.raw or {}).get("base_built"))

    def _building(self, state: GameState) -> bool:
        return bool((state.raw or {}).get("base_building"))

    def _near_threats(self, state: GameState) -> list:
        """Only threats close enough to interrupt building (~14 blocks)."""
        out = []
        for t in state.threats or []:
            if not t:
                continue
            d = t.dist if t.dist is not None else 0.0
            if d <= 14.0:
                out.append(t)
        return out

    def _best_prey(self, state: GameState):
        from brain.protocol import EntitySnap

        candidates: list = []
        for e in state.nearby or []:
            if not e or not e.id:
                continue
            kind = (e.kind or "").lower()
            name = (e.name or "").lower()
            if kind in {"player"} or "player" in name:
                continue
            # Skip merchant-like names
            if any(b in name for b in ("merchant", "villager", "trader", "guide", "shop")):
                continue
            candidates.append(e)
        for e in self._near_threats(state):
            if e and e.id:
                candidates.append(e)
        if not candidates:
            return None

        def score(ent: EntitySnap) -> float:
            d = ent.dist if ent.dist is not None else 30.0
            s = 100.0 - float(d)
            if ent.hostile:
                s += 5.0
            return s

        candidates.sort(key=score, reverse=True)
        return candidates[0]

    def decide(self, state: GameState) -> NpcAction:
        if self.mode == "observe":
            return NpcAction(name="idle", reason="observe mode", source="fallback")

        sit = self.situation_text(state)
        goal = (state.npc.goal if state.npc and state.npc.goal else "") or ""
        raw = state.raw or {}
        looting = bool(raw.get("looting")) or goal == "loot"
        near_threats = self._near_threats(state)
        force_follow = bool(raw.get("force_follow")) or goal == "follow"
        stay_home = bool(raw.get("stay_home")) or goal == "stay_home"
        idle_hunt = bool(raw.get("idle_hunt")) or bool(raw.get("hunting")) or goal == "hunt"
        can_auto_home = bool(raw.get("can_auto_home"))
        player_places = int(raw.get("player_places") or 0)
        owner_dist_base = float(raw.get("owner_dist_base") or 0.0)

        # --- 0) FIGHT only when hostiles are close (not while forced stay_home) ---
        if near_threats and not stay_home:
            threat = near_threats[0]
            npc_hp = state.npc.hp if state.npc and state.npc.hp is not None else 1.0
            if npc_hp < 0.35:
                return NpcAction(
                    name="flee",
                    target_id=threat.id,
                    item=ITEM_SWORD,
                    urgency=0.95,
                    reason="low hp - disengage then return with sword",
                    source="policy",
                )
            return NpcAction(
                name="attack",
                target_id=threat.id,
                target_pos=threat.pos,
                item=ITEM_SWORD,
                urgency=0.95,
                reason="FIGHT: near hostile — stone sword (do not gather/build under fire)",
                source="policy",
            )

        # --- 1) FOLLOW when player ≥200 blocks from Pest BASE ---
        if force_follow:
            if state.player:
                return NpcAction(
                    name="follow_player",
                    target_id=state.player.id,
                    target_pos=state.player.pos,
                    urgency=0.92,
                    reason=(
                        f"FOLLOW: owner {owner_dist_base:.0f} blocks from my base (≥200) — leave home"
                    ),
                    source="policy",
                )

        # --- 2) STAY HOME until player is 200 blocks from base ---
        if stay_home:
            return NpcAction(
                name="idle",
                urgency=0.55,
                reason=(
                    f"STAY HOME: owner still near base ({owner_dist_base:.0f}<200) — "
                    "guard bed / wait inside house"
                ),
                source="policy",
            )

        # --- 3) LOOT after hunt/fight ---
        if looting and not near_threats:
            return NpcAction(
                name="pickup",
                urgency=0.85,
                reason="LOOT: pick up combat drops after hunt/fight",
                source="policy",
            )

        # --- 4) HUNT when owner stands still 30–300s (not while stay_home) ---
        if idle_hunt and not near_threats:
            prey = self._best_prey(state)
            thr = raw.get("idle_hunt_threshold_s", "?")
            idle_s = raw.get("owner_idle_s", "?")
            if prey is not None:
                return NpcAction(
                    name="attack",
                    target_id=prey.id,
                    target_pos=prey.pos,
                    item=ITEM_SWORD,
                    urgency=0.8,
                    reason=(
                        f"HUNT MODE: owner idle {idle_s}s ≥ threshold {thr}s "
                        "— safe prey for hide/drops"
                    ),
                    source="policy",
                )
            return NpcAction(
                name="explore",
                item=ITEM_SWORD,
                urgency=0.7,
                reason=f"HUNT MODE: owner idle {idle_s}s ≥ {thr}s — search for prey",
                source="policy",
            )

        # --- 5) HOME: player placed >20 blocks in 300s → build ~100 from player base ---
        if self._building(state):
            return NpcAction(
                name="idle",
                urgency=0.5,
                reason="HOME building in progress — empty site + pathable doorway + bed",
                source="policy",
            )

        has_wood = self._has_wood(state)
        has_door = self._has_door(state)
        has_bed = self._has_bed(state)
        base_done = self._base_done(state)
        goal_build = goal in {"build_base", "place_bed"} or "build" in goal

        if not base_done and (can_auto_home or goal_build):
            if not can_auto_home:
                if state.player:
                    return NpcAction(
                        name="follow_player",
                        target_id=state.player.id,
                        target_pos=state.player.pos,
                        urgency=0.35,
                        reason=(
                            f"HOME wait: place >20 blocks in 300s "
                            f"(now {player_places}/20) — then I build ~100 away"
                        ),
                        source="policy",
                    )
            elif not self._base_built_only(state):
                now = time.time()
                if now - self._last_build_action_ts < _BUILD_ACTION_COOLDOWN_S:
                    return NpcAction(
                        name="idle",
                        urgency=0.4,
                        reason="HOME build cooldown — house should appear shortly",
                        source="policy",
                    )
                self._last_build_action_ts = now
                return NpcAction(
                    name="build_base",
                    item=ITEM_WOOD,
                    urgency=0.9,
                    reason=(
                        "HOME BUILD: player built 20+ blocks/300s → 9x9 empty site "
                        "~100 from player base, +1Y floor, pathable doorway, bed respawn"
                    ),
                    text=(
                        "You started a base — building my home about 100 blocks away "
                        "with a walkable doorway and a bed to respawn."
                    ),
                    source="policy",
                )
            if can_auto_home and (has_bed or not raw.get("base_bed")):
                return NpcAction(
                    name="place_block",
                    item=ITEM_BED,
                    urgency=0.82,
                    reason="PLACE BED INSIDE HOME for Pest/player respawn",
                    source="policy",
                )

        if has_bed and not raw.get("base_bed") and self._base_built_only(state):
            return NpcAction(
                name="place_block",
                item=ITEM_BED,
                urgency=0.78,
                reason="PLACE SPAWN bed inside finished room",
                source="policy",
            )

        # --- 5) CRAFT bed mats ---
        has_fibre = self._has(state, ITEM_FIBRE) or self._has(state, "Fibre")
        has_hide = self._has(state, ITEM_HIDE) or self._has(state, "Hide_Light")
        if has_fibre and has_hide and not has_bed:
            return NpcAction(
                name="craft_hint",
                item=ITEM_BED,
                urgency=0.65,
                reason="CRAFT BED: Fieldcraft 3 Fibre + 2 Light Hide → Crude Bed for inside base",
                text=(
                    "I have fibre and hide. Craft Crude Bed at Fieldcraft: "
                    "3 Fibre + 2 Light Hide. Place it inside our base."
                ),
                source="policy",
            )

        # --- 6) Normal hunt/gather when not idle-hunt locked ---
        prey = self._best_prey(state)
        if not has_hide:
            if prey is not None:
                return NpcAction(
                    name="attack",
                    target_id=prey.id,
                    target_pos=prey.pos,
                    item=ITEM_SWORD,
                    urgency=0.7,
                    reason="HUNT safe prey for Ingredient_Hide_Light — sword then loot",
                    source="policy",
                )
            return NpcAction(
                name="explore",
                item=ITEM_SWORD,
                urgency=0.55,
                reason="HUNT SEARCH: patrol for wildlife, then attack for hide drops",
                source="policy",
            )

        if not has_fibre:
            return NpcAction(
                name="explore",
                item=ITEM_AXE,
                urgency=0.5,
                reason="GATHER FIBRE: plants/bushes with stone axe; pickup drops",
                source="policy",
            )

        if prey is not None and self.mode in {"hybrid", "autonomous"} and not has_bed:
            return NpcAction(
                name="attack",
                target_id=prey.id,
                target_pos=prey.pos,
                item=ITEM_SWORD,
                urgency=0.4,
                reason="HUNT bonus while stocking bed mats",
                source="policy",
            )

        if state.blocks_of_interest and base_done:
            return NpcAction(
                name="mine_block",
                item=ITEM_PICK,
                urgency=0.45,
                reason="MINE: crude pick for rock/ore",
                source="policy",
            )

        # --- 5) Experience / imitation ---
        if self.mode in {"imitate", "hybrid"}:
            suggested = self.demos.suggest_from_demos(sit)
            if suggested and suggested not in {"idle", "attack"}:
                act = self._action_from_name(
                    suggested, state, source="imitate", reason="player demo prior"
                )
                if self.mode == "imitate":
                    return act

        if self.mode in {"hybrid", "autonomous"}:
            priors = self.bank.best_actions(sit, limit=3)
            if priors and float(priors[0].get("reward", 0)) >= 0.6:
                top = priors[0]
                aname = str(top.get("action", "follow_player"))
                # Don't let old attack priors override peaceful companion time
                if aname == "attack" and not near_threats and base_done:
                    aname = "follow_player"
                if aname == "build_base" and base_done:
                    aname = "follow_player"
                return self._action_from_name(
                    aname,
                    state,
                    source="policy",
                    reason=f"experience:{top.get('source', 'bank')}",
                )

        if state.player:
            return NpcAction(
                name="follow_player",
                target_id=state.player.id,
                target_pos=state.player.pos,
                urgency=0.3,
                reason="safe companion follow — base ok or waiting",
                source="policy",
            )

        return NpcAction(
            name="explore",
            item=ITEM_PICK,
            reason="no owner — explore with pick ready",
            source="fallback",
        )

    def reward_for(self, action: NpcAction, state: GameState, result_ok: bool | None = None) -> float:
        if result_ok is True:
            base = 1.0
        elif result_ok is False:
            base = 0.0
        else:
            base = 0.5
        near = self._near_threats(state)
        if action.name == "attack" and near:
            base += 0.25
        if action.name == "build_base":
            base += 0.35
        if action.name == "place_block" and action.item == ITEM_BED:
            base += 0.25
        if action.name == "mine_block" and action.item == ITEM_PICK and not near:
            base += 0.1
        if action.name in {"mine_block", "explore", "place_block", "build_base"} and near:
            base -= 0.45
        if action.name == "attack" and not near and self._base_done(state) is False:
            # Hunting for mats is fine; pure thrash is not
            if action.reason and "HUNT" in action.reason.upper():
                base += 0.05
        return max(0.0, min(1.0, base))

    def learn_from_result(
        self,
        state: GameState,
        action: NpcAction,
        result_ok: bool | None = None,
        diagnosis: str = "",
    ) -> None:
        sit = self.situation_text(state)
        reward = self.reward_for(action, state, result_ok)
        self.bank.add(
            situation=sit,
            action=action.name,
            reward=reward,
            obs={
                "threats": len(self._near_threats(state)),
                "nearby": len(state.nearby),
                "item": action.item,
            },
            diagnosis=diagnosis or action.reason,
            source=action.source,
        )

    def seed_curriculum_lessons(self) -> int:
        lessons = [
            ("threats=1 threat_dist=5", "attack", 1.0, ITEM_SWORD, "sword when hostile is close"),
            ("player damaged combat", "attack", 1.0, ITEM_SWORD, "fight if attacked"),
            (
                "goal=build_base has_wood=1 has_door=1 has_bed=1",
                "build_base",
                1.0,
                ITEM_WOOD,
                "BASE: closed room = walls+roof+door; bed inside; then spawn",
            ),
            (
                "has wood door need shelter",
                "build_base",
                1.0,
                ITEM_WOOD,
                "Build closed room first, then place bed inside",
            ),
            (
                "base_built=1 place bed inside",
                "place_block",
                1.0,
                ITEM_BED,
                "Bed goes on the floor inside the finished room",
            ),
            (
                "base concept closed room door bed",
                "build_base",
                1.0,
                ITEM_DOOR,
                "A base is four walls, roof, one door, bed inside for spawn",
            ),
            (
                "need hide prey nearby hunt",
                "attack",
                1.0,
                ITEM_SWORD,
                "HUNT wildlife for hide — never merchants",
            ),
            ("no threats mine stone", "mine_block", 1.0, ITEM_PICK, "pick for rock/ore"),
            ("no threats chop wood fibre", "explore", 0.9, ITEM_AXE, "axe for wood/fibre"),
            ("inv fibre hide no bed", "craft_hint", 0.95, ITEM_BED, "Fieldcraft crude bed"),
            ("safe follow owner base_built=1", "follow_player", 0.85, None, "co-op after base"),
            ("after kill pickup drops", "pickup", 0.9, None, "loot after hunt"),
            ("base_building=1 wait", "idle", 0.7, None, "wait while bulk build runs"),
        ]
        n = 0
        for sit, action, reward, item, diag in lessons:
            self.bank.add(
                situation=sit,
                action=action,
                reward=reward,
                obs={"item": item, "curriculum": True},
                diagnosis=diag,
                source="curriculum",
            )
            n += 1
        self.bank.distill()
        log.info("Seeded %d curriculum lessons (base + combat + tools)", n)
        return n

    async def maybe_narrate(self, state: GameState, action: NpcAction) -> str | None:
        if not self.settings.openai_api_key and not self.settings.ollama_base_url:
            if action.name == "craft_hint" and action.text:
                return action.text
            if action.name == "build_base":
                return (
                    "Building a basic base: closed wood room with a door, "
                    "then placing my bed inside for spawn."
                )
            if action.name == "place_block" and action.item == ITEM_BED:
                return "Placing my bed inside the shelter and setting our respawn."
            if action.name == "attack" and "HUNT" in (action.reason or "").upper():
                return "Hunting for materials — safe prey only."
            if action.name == "attack":
                return "Threat close! Sword out."
            if action.name == "mine_block":
                return "Mining stone."
            if action.name == "follow_player":
                return "Staying with you."
            return None
        brief = self.files.knowledge_brief()
        prompt = (
            f"You are {self.settings.npc_name}, a Hytale companion. "
            f"You build bases: closed wood room + door + bed inside for spawn. "
            f"You never hunt merchants. "
            f"Situation: {self.situation_text(state)}. "
            f"Action: {action.name} item={action.item} ({action.reason}). "
            f"Knowledge: {brief}. One short in-character sentence."
        )
        try:
            if self.settings.ollama_base_url:
                return await self._ollama(prompt)
            return await self._openai(prompt)
        except Exception as e:
            log.warning("narrate failed: %s", e)
            return None

    async def _openai(self, prompt: str) -> str:
        headers = {"Authorization": f"Bearer {self.settings.openai_api_key}"}
        body = {
            "model": self.settings.openai_model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": float(self.harness.get("generation", {}).get("temperature", 0.2)),
            "max_tokens": 80,
        }
        async with httpx.AsyncClient(base_url=self.settings.openai_base_url, timeout=30) as client:
            r = await client.post("/chat/completions", headers=headers, json=body)
            r.raise_for_status()
            data = r.json()
            return data["choices"][0]["message"]["content"].strip()

    async def _ollama(self, prompt: str) -> str:
        body = {"model": self.settings.ollama_model, "prompt": prompt, "stream": False}
        async with httpx.AsyncClient(base_url=self.settings.ollama_base_url, timeout=60) as client:
            r = await client.post("/api/generate", json=body)
            r.raise_for_status()
            return str(r.json().get("response", "")).strip()

    def _action_from_name(
        self, name: str, state: GameState, source: str, reason: str
    ) -> NpcAction:
        allowed = {
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
        }
        if name not in allowed:
            name = "follow_player" if state.player else "idle"
        item = None
        if name == "attack":
            item = ITEM_SWORD
        elif name == "mine_block":
            item = ITEM_PICK
        elif name in {"explore", "pickup"} and not self._near_threats(state):
            item = ITEM_AXE
        elif name == "build_base":
            item = ITEM_WOOD
        elif name == "place_block":
            item = ITEM_BED if self._has_bed(state) else ITEM_WOOD
        target_id = None
        target_pos = None
        if name in {"follow_player", "move_toward"} and state.player:
            target_id = state.player.id
            target_pos = state.player.pos
        near = self._near_threats(state)
        if name in {"attack", "flee"} and near:
            target_id = near[0].id
            target_pos = near[0].pos
        return NpcAction(
            name=name,  # type: ignore[arg-type]
            target_id=target_id,
            target_pos=target_pos,
            item=item,
            reason=reason,
            source=source,
        )
