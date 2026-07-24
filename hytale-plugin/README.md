# PestAiNpc — Hytale Java plugin

Companion NPC **pest** that bridges to the independent Docker brain:

```
ws://127.0.0.1:8766
```

(Not NpcAiStack / Mori on 8765.)

## Features

| Feature | How |
|---------|-----|
| Auto-spawn | Adventure (and Creative by default) via `PlayerReadyEvent` |
| **Player-like model** | Appearance `Outlander_Hunter` (humanoid, not Kweebec) |
| **Inventory / tools** | Hotbar: iron pick, hatchet, shovel, axe + dirt/stone stacks |
| **Use world** | Pickup drops, place dirt (brain `place_block` / jump intent), mine intent |
| Follow / fight | Role AI + held weapon equip on combat |
| Chat | Say `Pest, …` in chat → Docker brain `chat` / `chat_reply` |
| Learn from you | Break/place block demos + chat demos → `player_action` |
| Learn loop | 2 Hz `state` snapshots (player + threats + inventory) → brain `action` |
| Modes | `/pest mode observe\|imitate\|hybrid\|autonomous` |

## Build & install

```powershell
cd W:\Grok\home\bin\hytale-pest-npc
.\scripts\build-plugin.ps1
```

Or:

```powershell
cd hytale-plugin
.\gradlew.bat shadowJar
Copy-Item build\libs\PestAiNpc-0.1.0.jar $env:APPDATA\Hytale\UserData\Mods\ -Force
```

Requires **JDK 25** and network once for `com.hypixel.hytale:Server` from maven.hytale.com.

## Play

1. Start Docker brain: `..\scripts\up.ps1` (SIM_MODE=0 for real play)
2. Launch Hytale, load a world (restart after installing jar)
3. **`/pest status`** — brain link (command is **`/pest`**, not `/auri`)
4. **`/pest spawn force`** if needed
5. Chat: `Pest, follow me` / `Pest, what's nearby?`
6. Mine/place blocks — demos stream to the brain

## Conflict note

You can keep **NpcAiStack** (Mori) installed. Use different names/commands:

- Mori → orchestrator **8765**
- **Pest** → brain **8766**, commands **`/pest …`**

If both auto-spawn companions, disable one side’s creative/adventure auto-spawn as needed.

## Protocol

See `../plugin-bridge/PROTOCOL.md`.
