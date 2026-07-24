# Local models (Qwen via Ollama)

Pest’s wiki Q&A uses a **local Qwen** model served by Ollama in Docker.

| Path | Role |
|------|------|
| `models/ollama/` | Ollama data dir (weights live here, **inside this project**) |
| Default model | `qwen2.5:3b` (fast enough on CPU; override in `.env`) |

Weights are **not** committed to git (too large). Pull once:

```powershell
.\scripts\pull-qwen.ps1
# or:
docker compose up -d ollama
docker exec hytale-pest-npc-ollama ollama pull qwen2.5:3b
```

Higher quality (slower on CPU): set `OLLAMA_MODEL=qwen2.5:7b` then re-run `pull-qwen.ps1`.
