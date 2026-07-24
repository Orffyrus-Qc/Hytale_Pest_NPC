"""Entry point: HTTP API + WebSocket bridge (+ optional sim loop)."""

from __future__ import annotations

import asyncio
import logging
import sys

import uvicorn

from brain.api import create_app
from brain.config import get_settings
from brain.service import BrainService
from brain.ws_server import WsBridge

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    stream=sys.stdout,
)
log = logging.getLogger("hytale_ai.main")


async def run() -> None:
    settings = get_settings()
    settings.ensure_dirs()
    brain = BrainService(settings)
    brain.bootstrap()

    app = create_app(brain)
    config = uvicorn.Config(
        app,
        host=settings.brain_host,
        port=settings.api_port,
        log_level="info",
        access_log=False,
    )
    server = uvicorn.Server(config)
    bridge = WsBridge(brain, settings.brain_host, settings.brain_port)

    async def _warm_llm() -> None:
        # Load Qwen into RAM so first in-game question is not a multi-minute cold start
        await asyncio.sleep(2)
        try:
            await brain.policy.warm_ollama()
        except Exception as e:
            log.warning("LLM warm task failed: %s", e)

    tasks = [
        asyncio.create_task(server.serve(), name="http"),
        asyncio.create_task(bridge.run(), name="ws"),
        asyncio.create_task(_warm_llm(), name="warm-llm"),
    ]

    if settings.sim_mode:
        from brain.sim import run_sim_loop

        tasks.append(asyncio.create_task(run_sim_loop(brain), name="sim"))
        log.info("SIM_MODE=1 — synthetic learner running")

    log.info(
        "Hytale AI NPC brain up | NPC=%s | HTTP=:%s | WS=:%s | mount=%s | ollama=%s model=%s",
        settings.npc_name,
        settings.api_port,
        settings.brain_port,
        settings.hytale_mount,
        settings.ollama_base_url or "-",
        settings.ollama_model or "-",
    )
    await asyncio.gather(*tasks)


def main() -> None:
    asyncio.run(run())


if __name__ == "__main__":
    main()
