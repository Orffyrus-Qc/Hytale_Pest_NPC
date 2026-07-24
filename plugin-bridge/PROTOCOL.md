# Hytale plugin ↔ Docker brain protocol (`hytale_ai_npc_v1`)

The **brain runs in Docker** on `ws://127.0.0.1:8766`.  
The **Hytale Java plugin** (on the host game/server process) opens a WebSocket client and streams structured state.

> Prefer **plugin-exported state** over reading raw process RAM. Hytale is server-authoritative; client memory is fragile and easy to break across patches.

## Message envelope

```json
{
  "type": "state",
  "ts": 1710000000.0,
  "session_id": "optional",
  "payload": { }
}
```

## Types

| type | direction | purpose |
|------|-----------|---------|
| `hello` | plugin → brain | handshake |
| `hello_ack` | brain → plugin | npc name + protocol |
| `state` | plugin → brain | full snapshot; brain replies with `action` |
| `player_action` | plugin → brain | demonstration frame while player plays |
| `action` | brain → plugin | NPC action to execute |
| `action_result` | plugin → brain | whether action succeeded |
| `chat` / `chat_reply` | both | dialogue |
| `mode` | plugin → brain | `observe` \| `imitate` \| `hybrid` \| `autonomous` |
| `memory_snapshot` | host helper → brain | optional sanitized probe (not required) |
| `ping` / `pong` | both | keepalive |
| `status` | both | diagnostics |

## `state` payload

```json
{
  "type": "state",
  "payload": {
    "state": {
      "world": "MyWorld",
      "tick": 12345,
      "player": {
        "id": "uuid-or-name",
        "name": "Player",
        "pos": { "x": 1, "y": 64, "z": 2 },
        "hp": 0.9,
        "held_item": "sword",
        "game_mode": "Adventure"
      },
      "npc": {
        "id": "pest-1",
        "name": "Pest",
        "pos": { "x": 2, "y": 64, "z": 2 },
        "hp": 1.0,
        "goal": "follow"
      },
      "nearby": [],
      "threats": [
        {
          "id": "mob-9",
          "kind": "hostile",
          "name": "Trork",
          "pos": { "x": 5, "y": 64, "z": 3 },
          "hostile": true,
          "hp": 0.5,
          "dist": 4.2
        }
      ],
      "blocks_of_interest": [],
      "inventory_hints": ["wood", "stone"]
    }
  }
}
```

## `action` reply

```json
{
  "type": "action",
  "payload": {
    "action": {
      "name": "follow_player",
      "target_id": "uuid-or-name",
      "target_pos": { "x": 1, "y": 64, "z": 2 },
      "item": null,
      "text": null,
      "urgency": 0.3,
      "reason": "default companion follow",
      "source": "policy"
    }
  }
}
```

### Action names

`idle` · `move_toward` · `follow_player` · `flee` · `attack` · `mine_block` · `place_block` · `build_base` · `use_item` · `pickup` · `chat` · `explore` · `craft_hint`

### Base building (`build_base`)

Concept: a **closed room** (wood walls + roof) with **one door**, then **bed inside** for spawn.

- Blocks: `Wood_Softwood_Planks`, `Furniture_Crude_Door`, `Furniture_Crude_Bed`
- Plugin: `PestBaseBuilder` bulk-places via `World.setBlock`
- State flags in `raw`: `base_built`, `base_bed`
- Force in-game: `/pest base`

## Learning pipeline

1. **Files** — Docker mounts `%APPDATA%/Hytale` read-only; brain indexes JSON/jars/Assets.zip names.  
2. **Player demos** — plugin sends `player_action` while the human plays.  
3. **Experience** — successful/failed `action_result` updates the dual-layer bank.  
4. **Autonomous** — set mode `autonomous` or `hybrid`; plugin executes `action` each tick.

## Plugin implementation notes

- Connect to `ws://127.0.0.1:8766` from the **server** process (singleplayer local server is fine).  
- Rate-limit `state` to ~2–8 Hz.  
- Map actions onto your NPC role / ECS (see existing Mori / NpcAiStack patterns).  
- Do **not** put API keys in the plugin JAR.

## Optional host memory probe

Only if you need client-only signals the server lacks: a small **Windows host helper** can send `memory_snapshot` with **sanitized fields** (HP bars, coordinates you already own).  
Do not stream raw RAM dumps into the container. Keep `tools.memory_probe: false` in `configs/harness.yaml` unless you knowingly enable it.
