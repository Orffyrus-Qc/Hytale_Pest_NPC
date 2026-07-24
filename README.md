# Pest — Hytale AI NPC (independent Docker brain)

Standalone learning agent for **Hytale Early Access**.  
Runs in **its own Docker stack** (separate from Mori/OpenHands/NpcAiStack). The game stays on Windows; the brain learns and decides inside the container.

**NPC name:** `Pest` (male — he/him). Change with `NPC_NAME` / `configs/harness.yaml` if needed.

> **Commands are `/pest …` (not `/auri`).** The old Auri name was fully renamed.

### Companion priorities (live policy)

1. **Fight** near hostiles (unless staying home)  
2. **Follow** if you are **≥200 blocks from Pest’s base**  
3. **Stay home** while you are **&lt;200** from his base (guards bed)  
4. **Hunt** if you stand still **30–300 s** (random threshold)  
5. **Build home** when you place **&gt;20 blocks in 300 s** → he builds **~100 blocks** from your build cluster (empty spot, floor **+1 Y**, **pathable** doorway, **bed** for respawn)  
6. **Invulnerable** until his base+bed exist  

### Slash commands (`/pest`)

| Command | Effect |
|---------|--------|
| `/pest` or `/pest help` | This help list |
| `/pest status` | Brain link, inventory, base, timers |
| `/pest spawn` | Spawn Pest near you |
| `/pest spawn force` | Despawn clones + respawn + full kit; resets trackers |
| `/pest regear` | Re-apply tools/planks/door/bed kit |
| `/pest places` | How many blocks you placed (home trigger counter) |
| `/pest base force` | Build home **now** (no 20-block wait) |
| `/pest bed` | Set spawn at Pest Camp if base exists |
| `/pest hunt` | Hunt mode ~30s (safe prey) |
| `/pest loot` | Loot / pickup window |
| `/pest mode hybrid` | `observe` \| `imitate` \| `hybrid` \| `autonomous` |
| `/pest creative on\|off` | Auto-spawn in Creative |

### Chat keywords (instant intents)

Address him by name first (case-insensitive), e.g. **`Pest, follow me`**.  
If you already spoke to him recently, you can omit the name for a short while.

| Intent | Keywords / phrases (message must contain one) |
|--------|-----------------------------------------------|
| **Build home** | `build base`, `build a base`, `build house`, `make a base`, `make base`, `shelter`, `build room`, `build home`, `set up camp`, `make camp`, `build camp` |
| **Rebuild home** | `rebuild`, `new base`, `another base` |
| **Set spawn / bed** | `set spawn`, `set bed`, `place bed`, `sleep here`, `respawn here` |
| **Hunt** | `hunt`, `kill animals`, `get hide`, `find prey` |
| **Loot** | `loot`, `pick up`, `pickup`, `gather drops` |
| **Status** | `status`, `what are you doing`, `inventory`, `where is base` |
| **Follow** | `follow me`, `come here`, `stay close`, `follow` |
| **Stand down** | `stop hunting`, `stop fight`, `stand down`, `peace` |
| **Move nudge** | `forward`, `walk`, `go ahead`, `jump`, `hop` (or combine, e.g. jump + forward) |

**Examples**

```text
Pest, follow me
Pest, build a base
Pest, hunt
Pest, loot
Pest, status
Pest, stand down
Pest, set spawn
```

Anything else that addresses Pest (and isn’t in the table) goes to the Docker brain for a free-form reply / plan.

---

## What it does

| Phase | How |
|-------|-----|
| **1. Inspect game files** | Read-only mount of `%APPDATA%\Hytale` → indexes mods, server JSON, jar names, `Assets.zip` entries |
| **2. “Game memory”** | **Structured state** from a server plugin WebSocket (preferred). Optional sanitized host probe — raw RAM scrapers are off by default |
| **3. Learn from player** | Plugin streams `player_action` demos while you play → imitation store + experience bank |
| **4. Learn how to play** | Dual-layer experience (cases + distilled patterns) + rule/policy hybrid |
| **5. Play by itself** | Modes: `observe` → `imitate` → `hybrid` → `autonomous`; emits `action` for the plugin/NPC to execute |

Inspired by harness ideas (context / tools / generation / orchestration / memory / output) without depending on the MemoHarness research repo.

---

## Architecture

```
  ┌──────────────────────────── Windows host ─────────────────────────────┐
  │  HytaleClient.exe  +  HytaleServer (local SP)                         │
  │       │                                                                │
  │       │  Java plugin (you wire)  ──WebSocket──►  ws://127.0.0.1:8766  │
  │       │  player demos + state                     action proposals     │
  │       │                                                                │
  │  %APPDATA%\Hytale  ──read-only bind mount──┐                           │
  └────────────────────────────────────────────┼───────────────────────────┘
                                               ▼
                              ┌────────────────────────────────┐
                              │  Docker: hytale-pest-npc-brain │
                              │  • file_inspector              │
                              │  • demo_store (imitation)      │
                              │  • experience bank             │
                              │  • play policy                 │
                              │  • HTTP :8780  WS :8766        │
                              └────────────────────────────────┘
```

**Why not pure “read game RAM in Docker”?**  
Linux containers cannot cleanly attach to a Windows game process. Hytale gameplay is **server-authoritative**; a plugin bridge is the durable, patch-resistant path. Client memory can be added later as an optional host helper.

---

## Quick start (Windows + Docker Desktop)

```powershell
cd W:\Grok\home\bin\hytale-pest-npc
.\scripts\init-env.ps1
.\scripts\up.ps1
```

Smoke-test **without** Hytale (synthetic player + threats):

```powershell
.\scripts\up-sim.ps1
docker logs -f hytale-pest-npc-brain
.\scripts\status.ps1
```

HTTP checks:

```text
http://127.0.0.1:8780/health
http://127.0.0.1:8780/status
http://127.0.0.1:8780/search-files?q=npc
```

Stop:

```powershell
.\scripts\down.ps1
```

---

## Ports

| Port | Service |
|------|---------|
| **8780** | REST API (status, scan, chat, mode) |
| **8766** | WebSocket plugin bridge |

---

## Learning modes

| Mode | Behavior |
|------|----------|
| `observe` | Record only; NPC idles |
| `imitate` | Prefer actions seen in player demos |
| `hybrid` | Safety rules + demos + experience (default) |
| `autonomous` | Experience/policy driven self-play |

```powershell
Invoke-RestMethod -Method POST http://127.0.0.1:8780/mode/autonomous
```

---

## Hytale plugin (pest) — built-in

Java plugin lives in [`hytale-plugin/`](hytale-plugin/) (`com.orffyrus:PestAiNpc`).

```powershell
# Build + install jar into %APPDATA%\Hytale\UserData\Mods\
.\scripts\build-plugin.ps1
```

| In-game | Action |
|---------|--------|
| `/pest status` | companion + brain connection |
| `/pest spawn` / `/pest spawn force` | spawn Pest |
| `/pest mode hybrid` | observe / imitate / hybrid / autonomous |
| `/pest base force` | build home now |
| Chat `Pest, …` | dialogue + instant keywords (see table above) |
| Mine / place | player demos for learning |

Full WebSocket schema: [`plugin-bridge/PROTOCOL.md`](plugin-bridge/PROTOCOL.md)

Host smoke client (no game):

```powershell
pip install websockets
python plugin-bridge/example_client.py
```

**Independent of Mori/NpcAiStack (8765).** Pest uses **8766** only.


---

## Config

| File | Role |
|------|------|
| `.env` | Host Hytale path, ports, optional LLM keys |
| `configs/harness.yaml` | 6-dim control defaults (tools, memory, mode, rate limits) |
| `data/` | Persisted demos, experience, file index, patterns |

Optional LLM (dialogue only; play works offline without keys):

- OpenAI-compatible: set `OPENAI_API_KEY`  
- Ollama on host: `OLLAMA_BASE_URL=http://host.docker.internal:11434`

---

## Project layout

```
hytale-pest-npc/
  docker-compose.yml
  Dockerfile
  requirements.txt
  configs/harness.yaml
  brain/                 # Python learning agent
  plugin-bridge/         # Protocol + example WS client
  scripts/               # up / down / sim / status
  data/                  # runtime learning store (gitignored contents)
```

---

## Honest limits (Early Access)

| Capability | Status in this repo |
|------------|---------------------|
| Docker brain + health/status | **Yes** |
| Game file index from mount | **Yes** |
| Player demo → imitation | **Yes** (needs plugin events in real game) |
| Experience bank + distill | **Yes** |
| Autonomous action proposals | **Yes** |
| In-game NPC entity + combat animation | **Plugin work** (protocol ready) |
| Raw process memory reading | **Optional / off** — use plugin state |
| Full RL GPU training | **Not included** — lightweight online experience |

Hytale APIs move between builds. Re-check `HytaleServer.jar` when writing the Java side (`docs/HYTALE_MODDING_AI_NOTES.md` in the parent workspace).

---

## Safety

- Game files are mounted **read-only**.  
- No API keys inside any future plugin JAR — only in Docker `.env`.  
- Do not enable host memory scraping on multiplayer servers you do not own.  
- Skill/code self-rewrite is **not** auto-loaded here (unlike heavier Mori stacks).

---

## License / ownership

Independent scaffold for local use with your own Hytale install and mods. Not affiliated with Hypixel Studios.
