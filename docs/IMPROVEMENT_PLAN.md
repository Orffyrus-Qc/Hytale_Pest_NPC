# Pest — improvement plan (post-MVP)

**Status:** MVP **shipped** · Project **paused** after this plan  
**Repo:** https://github.com/Orffyrus-Qc/Hytale_Pest_NPC  
**As of:** 2026-07-24 (wiki Q&A + install docs on `main`)

This is a roadmap for when work resumes. Do **not** treat it as incomplete MVP — the companion is usable today. Items below are **enhancements**.

---

## Current baseline (done)

| Area | What works |
|------|------------|
| Docker brain | Policy hybrid, demos, experience bank, HTTP + WS |
| Plugin | `/pest *`, spawn, chat intents, state/actions bridge |
| Behavior | Fight, follow (≥200 from base), stay home, idle hunt, auto-home, invuln until base+bed |
| Building | Larger home, pathable doorway, door last, bed/respawn, stand ~6 before build |
| Knowledge | Hytale wiki Q&A (definition + Obtaining/where-found for resources) |
| Docs / ship | Clean GitHub tree, clone/install expectations, prebuilt jar, scripts |

**Pause point:** Brain + plugin + wiki + GitHub install path are good enough to leave stable.

---

## Goals when we resume

1. **Easier install** — agents and new PCs succeed more often  
2. **Smarter companion** — better plans, memory, and craft answers  
3. **More reliable plugin** — survive Hytale patches, clearer failures  
4. **Quality bar** — smoke tests / CI so regressions are obvious  

---

## Phase 0 — Hygiene (small, do first on resume)

| ID | Item | Why |
|----|------|-----|
| P0.1 | Single `scripts/install.ps1` (init-env → up → install-jar → print next steps) | One agent/human command path |
| P0.2 | Compose **sim profile** that does not require a real Hytale mount | Clone works without the game |
| P0.3 | Fix any remaining machine-local paths in docs/scripts | Clone-folder only |
| P0.4 | Tag release `v0.1.0` on GitHub from current `main` | Clear resume baseline |

**Exit:** `git clone` + one script → healthy brain (sim or real).

---

## Phase 1 — Install & ops (agent-friendly)

| ID | Item | Notes |
|----|------|--------|
| P1.1 | `install.sh` or documented non-Windows path (best-effort) | Or explicit “Windows-only” badge |
| P1.2 | Health script: ports, wiki, WS, jar present | `scripts/doctor.ps1` |
| P1.3 | CI: build Docker image + `curl /health` on PR | GitHub Actions |
| P1.4 | Plugin version in `/pest status` + brain `/status` | Debug mismatched jars |
| P1.5 | README troubleshooting (mount fail, port busy, no wiki) | Reduce support friction |

**Exit:** Green CI; doctor script catches 80% of setup mistakes.

---

## Phase 2 — Wiki & knowledge (smarter answers)

| ID | Item | Notes |
|----|------|--------|
| P2.1 | Craft/recipe answers (ingredients, bench, pocket craft) | Parse Usage/Crafting sections more aggressively |
| P2.2 | Multi-hop: “how do I get iron tools?” → ore → smelt → anvil | Related-page chain with focus token |
| P2.3 | Offline fallback index from mounted game JSON | When wiki is down or thin |
| P2.4 | Cite 1–2 wiki URLs only; shorter in-game lines | Chat length limits |
| P2.5 | Conversation memory: last N Q&A topics | Better “and the pickaxe?” follow-ups |
| P2.6 | Optional LLM polish always grounded on wiki extract | Never invent locations |

**Exit:** Craft + “where + how” answers stay on-topic ≥ most resource questions.

---

## Phase 3 — Companion behavior

| ID | Item | Notes |
|----|------|--------|
| P3.1 | Stronger pathing / less teleport reliance where API allows | Depends on Hytale server APIs |
| P3.2 | Inventory-aware goals (need hide → hunt; need fibre → gather) | Policy already partial |
| P3.3 | Shared base with player (expand room, chests, lights) | Beyond single room+bed |
| P3.4 | Combat roles: tank vs support vs flee thresholds | Config in harness |
| P3.5 | “Stay / follow / home” as sticky modes with UI feedback | Clearer than chat-only |
| P3.6 | Death / disconnect recovery | Respawn at bed, re-pair owner |

**Exit:** Fewer stuck states; clearer mode machine.

---

## Phase 4 — Learning system

| ID | Item | Notes |
|----|------|--------|
| P4.1 | Better demo labeling (mine vs place vs attack) | Richer imitation |
| P4.2 | Distill + prune bad experience (attack priors thrash) | Already partially mitigated |
| P4.3 | Export/import `data/` snapshot | Move learning between PCs |
| P4.4 | Optional true planner LLM for high-level goals only | Policy stays safety net |
| P4.5 | Curriculum YAML expansion (zones, ores, food) | Teachable lessons |

**Exit:** Learning improves play over a session without breaking safety rules.

---

## Phase 5 — Plugin robustness

| ID | Item | Notes |
|----|------|--------|
| P5.1 | Track Hytale server version; warn on mismatch | Patch survival |
| P5.2 | Safer JSON parse for chat_reply (or small JSON lib) | Less brittle extractors |
| P5.3 | Unit/integration tests where server jar allows | Or protocol-level tests with example_client |
| P5.4 | Config file for distances (200 follow, 20 places, 100 home) | No recompile for tuning |
| P5.5 | Remove dead Auri leftovers if any remain on disk | Clean Mods folder docs |

**Exit:** Upgrade Hytale → clear rebuild/install steps, fewer silent fails.

---

## Phase 6 — Stretch / later

- Voice or TTS replies  
- Map markers for Pest base  
- Multiplayer: one Pest per owner  
- Public release packaging (Modrinth-style if ecosystem exists)  
- Linux host first-class support  
- Full design doc + execute-plan stack only if scope grows large  

---

## Priority order (recommended when un-pausing)

```text
P0 (hygiene + tag v0.1)
  → P1 (install/CI/doctor)
  → P2 (wiki/craft knowledge)
  → P3 (behavior)
  → P4 (learning)
  → P5 (plugin robustness)
  → P6 (stretch)
```

Do **one phase at a time**; keep GitHub clean-root pushes only (not the monorepo).

---

## Out of scope (for now)

- Replacing the rule policy entirely with an unconstrained LLM  
- Reading raw game process RAM as the primary sensor  
- Shipping Hytale itself or licensed assets  
- Guaranteeing Linux/Mac as primary without explicit work in P6  

---

## How to resume

1. Open https://github.com/Orffyrus-Qc/Hytale_Pest_NPC (or local clean clone).  
2. Read this file + README “What will usually work”.  
3. Verify `docker compose` + `/health` + `/search-wiki` still pass.  
4. Start at **P0.1** unless a bug forces a hotfix.  
5. Update this plan’s **Status** line when active again.

---

## Pause checklist (now)

- [x] MVP behavior + wiki Q&A on `main`  
- [x] Install expectations documented in README  
- [x] This improvement plan written  
- [ ] Optional: tag `v0.1.0` (do on resume or as last unpause action)  
- [x] **Project paused** — no further feature work until explicitly resumed  

When pausing, leave Docker/data as the user prefers; no requirement to tear down.
