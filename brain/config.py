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

    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    openai_model: str = "gpt-4o-mini"
    ollama_base_url: str = ""
    ollama_model: str = "llama3.2"

    harness_path: Path = Field(default=Path("/app/configs/harness.yaml"))
    wiki_api_url: str = "https://hytale.wiki.gg/api.php"

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
    return s
