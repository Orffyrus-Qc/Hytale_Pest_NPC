from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import yaml
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    data_dir: Path = Field(default=Path("/data"))
    hytale_mount: Path = Field(default=Path("/hytale"))
    brain_host: str = "0.0.0.0"
    brain_port: int = 8766
    api_port: int = 8780
    sim_mode: bool = False
    npc_name: str = "Pest"

    # OpenAI-compatible cloud API (optional; local Qwen/Ollama is preferred)
    openai_api_key: str = ""
    openai_base_url: str = ""
    openai_model: str = ""
    # Local Qwen via project Ollama (default for Docker stack)
    # 3b is much faster on CPU; set OLLAMA_MODEL=qwen2.5:7b for quality if you have GPU.
    ollama_base_url: str = "http://ollama:11434"
    ollama_model: str = "qwen2.5:3b"

    harness_path: Path = Field(default=Path("/app/configs/harness.yaml"))
    wiki_api_url: str = "https://hytalewiki.org/api.php"

    def ensure_dirs(self) -> None:
        for sub in (
            "experience",
            "demos",
            "file_index",
            "wiki_cache",
            "policies",
            "sessions",
            "logs",
            "patterns",
        ):
            (self.data_dir / sub).mkdir(parents=True, exist_ok=True)


def load_harness(path: Path) -> dict[str, Any]:
    if not path.exists():
        # Fallback when running outside Docker
        alt = Path(__file__).resolve().parents[1] / "configs" / "harness.yaml"
        path = alt if alt.exists() else path
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def get_settings() -> Settings:
    s = Settings()
    # SIM_MODE may arrive as "0"/"1"
    raw = os.getenv("SIM_MODE", "0").strip().lower()
    s.sim_mode = raw in {"1", "true", "yes", "on"}
    name = os.getenv("NPC_NAME")
    if name:
        s.npc_name = name
    wiki = os.getenv("WIKI_API_URL", "").strip()
    if wiki:
        s.wiki_api_url = wiki

    # Local Qwen first (project Docker Ollama). Cloud only if no Ollama URL.
    ollama = os.getenv("OLLAMA_BASE_URL", "").strip()
    if ollama:
        s.ollama_base_url = ollama
    elif not s.ollama_base_url:
        s.ollama_base_url = "http://ollama:11434"
    om = os.getenv("OLLAMA_MODEL", "").strip()
    if om:
        s.ollama_model = om
    elif not s.ollama_model:
        s.ollama_model = "qwen2.5:3b"

    xai = os.getenv("XAI_API_KEY", "").strip()
    openai_key = os.getenv("OPENAI_API_KEY", "").strip()
    # Optional cloud override (only used if Ollama is disabled via empty OLLAMA_BASE_URL)
    use_cloud = os.getenv("OLLAMA_BASE_URL", "http://ollama:11434").strip() == ""
    if use_cloud and (xai or openai_key):
        if xai and not openai_key:
            s.openai_api_key = xai
            s.openai_base_url = os.getenv("OPENAI_BASE_URL", "https://api.x.ai/v1").strip() or "https://api.x.ai/v1"
            s.openai_model = os.getenv("OPENAI_MODEL", "grok-4.5").strip() or "grok-4.5"
        elif openai_key:
            s.openai_api_key = openai_key
            base = os.getenv("OPENAI_BASE_URL", "").strip()
            if base:
                s.openai_base_url = base
            model = os.getenv("OPENAI_MODEL", "").strip()
            if model:
                s.openai_model = model
    return s

def has_llm(settings: Settings) -> bool:
    return bool(settings.openai_api_key or settings.ollama_base_url)
