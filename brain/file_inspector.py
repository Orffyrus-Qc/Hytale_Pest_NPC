"""Inspect Hytale game files (read-only mount) and build a learning index."""

from __future__ import annotations

import json
import logging
import re
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import orjson

log = logging.getLogger("hytale_ai.files")

INTERESTING_NAME_RE = re.compile(
    r"(npc|item|block|recipe|drop|interaction|role|entity|loot|biome|world)",
    re.I,
)


@dataclass
class FileHit:
    path: str
    kind: str
    summary: str
    keywords: list[str] = field(default_factory=list)
    size: int = 0


class GameFileInspector:
    """Walks mounted Hytale install + optionally peeks into Assets.zip."""

    def __init__(self, hytale_root: Path, index_dir: Path) -> None:
        self.root = hytale_root
        self.index_dir = index_dir
        self.index_dir.mkdir(parents=True, exist_ok=True)
        self.index_path = self.index_dir / "file_index.json"
        self.hits: list[FileHit] = []
        self.last_scan_ts: float = 0.0

    def available(self) -> bool:
        return self.root.exists()

    def status(self) -> dict[str, Any]:
        return {
            "mounted": self.available(),
            "root": str(self.root),
            "hits": len(self.hits),
            "last_scan_ts": self.last_scan_ts,
            "assets_zip": (self.root / "install" / "release" / "package" / "game" / "latest" / "Assets.zip").exists()
            if self.available()
            else False,
        }

    def scan(self, max_files: int = 400) -> list[FileHit]:
        if not self.available():
            log.warning("Hytale mount missing: %s", self.root)
            return []

        hits: list[FileHit] = []
        roots = [
            self.root / "UserData" / "Mods",
            self.root / "install" / "release" / "package" / "game" / "latest" / "Server",
            self.root / "UserData" / "Saves",
        ]

        for base in roots:
            if not base.exists():
                continue
            for path in base.rglob("*"):
                if len(hits) >= max_files:
                    break
                if not path.is_file():
                    continue
                if path.suffix.lower() not in {".json", ".txt", ".yml", ".yaml", ".md", ".properties"}:
                    # still index jar names as presence
                    if path.suffix.lower() == ".jar":
                        hits.append(
                            FileHit(
                                path=self._rel(path),
                                kind="plugin_jar",
                                summary=f"Plugin/mod jar: {path.name}",
                                keywords=[path.stem.lower()],
                                size=path.stat().st_size,
                            )
                        )
                    continue
                if path.stat().st_size > 2_000_000:
                    continue
                kind = self._classify(path)
                summary, keywords = self._summarize_jsonish(path)
                hits.append(
                    FileHit(
                        path=self._rel(path),
                        kind=kind,
                        summary=summary,
                        keywords=keywords,
                        size=path.stat().st_size,
                    )
                )

        # Lightweight Assets.zip catalog (names only — zip is multi-GB)
        assets = self.root / "install" / "release" / "package" / "game" / "latest" / "Assets.zip"
        if assets.exists() and len(hits) < max_files:
            hits.extend(self._catalog_assets_zip(assets, limit=max_files - len(hits)))

        self.hits = hits
        self.last_scan_ts = time.time()
        self._save()
        log.info("File scan complete: %d hits under %s", len(hits), self.root)
        return hits

    def search(self, query: str, limit: int = 12) -> list[dict[str, Any]]:
        if not self.hits:
            self.load()
        if not self.hits:
            self.scan()
        q = query.lower().strip()
        if not q:
            return [self._hit_dict(h) for h in self.hits[:limit]]
        tokens = [t for t in re.split(r"\W+", q) if t]
        scored: list[tuple[float, FileHit]] = []
        for h in self.hits:
            blob = f"{h.path} {h.kind} {h.summary} {' '.join(h.keywords)}".lower()
            score = sum(1.0 for t in tokens if t in blob)
            if INTERESTING_NAME_RE.search(blob):
                score += 0.25
            if score > 0:
                scored.append((score, h))
        scored.sort(key=lambda x: (-x[0], x[1].path))
        return [self._hit_dict(h) for _, h in scored[:limit]]

    def load(self) -> None:
        if not self.index_path.exists():
            return
        try:
            data = orjson.loads(self.index_path.read_bytes())
            self.hits = [FileHit(**row) for row in data.get("hits", [])]
            self.last_scan_ts = float(data.get("last_scan_ts", 0))
        except Exception as e:
            log.warning("Failed to load file index: %s", e)

    def knowledge_brief(self) -> str:
        if not self.hits:
            self.load()
        jars = [h for h in self.hits if h.kind == "plugin_jar"]
        npcs = [h for h in self.hits if "npc" in h.kind or "npc" in h.path.lower()]
        items = [h for h in self.hits if "item" in h.kind or "item" in h.path.lower()]
        return (
            f"Hytale file knowledge: {len(self.hits)} indexed entries; "
            f"{len(jars)} jars; ~{len(npcs)} NPC-related; ~{len(items)} item-related. "
            f"Mount={'ok' if self.available() else 'missing'}."
        )

    def _save(self) -> None:
        payload = {
            "last_scan_ts": self.last_scan_ts,
            "hits": [self._hit_dict(h) for h in self.hits],
        }
        self.index_path.write_bytes(orjson.dumps(payload, option=orjson.OPT_INDENT_2))

    def _rel(self, path: Path) -> str:
        try:
            return str(path.relative_to(self.root)).replace("\\", "/")
        except ValueError:
            return str(path).replace("\\", "/")

    def _classify(self, path: Path) -> str:
        p = str(path).lower().replace("\\", "/")
        for key in ("npc", "item", "block", "recipe", "interaction", "loot", "drop", "role"):
            if key in p:
                return key
        return path.suffix.lower().lstrip(".") or "file"

    def _summarize_jsonish(self, path: Path) -> tuple[str, list[str]]:
        keywords: list[str] = [path.stem.lower()]
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")[:8000]
        except Exception:
            return path.name, keywords
        if path.suffix.lower() == ".json":
            try:
                obj = json.loads(text)
                if isinstance(obj, dict):
                    keys = list(obj.keys())[:12]
                    keywords.extend(str(k).lower() for k in keys)
                    name = obj.get("Name") or obj.get("name") or obj.get("Id") or obj.get("id")
                    if name:
                        keywords.append(str(name).lower())
                        return f"JSON {path.name}: keys={keys[:6]} name={name}", keywords
                    return f"JSON {path.name}: keys={keys[:8]}", keywords
            except Exception:
                pass
        line = " ".join(text.split())[:180]
        return f"{path.name}: {line}", keywords

    def _catalog_assets_zip(self, assets: Path, limit: int = 200) -> list[FileHit]:
        out: list[FileHit] = []
        try:
            with zipfile.ZipFile(assets, "r") as zf:
                for name in zf.namelist():
                    if len(out) >= limit:
                        break
                    lower = name.lower()
                    if not INTERESTING_NAME_RE.search(lower):
                        continue
                    if not lower.endswith((".json", ".png", ".ogg", ".wav", ".yaml")):
                        continue
                    # Prefer Server/NPC and item-like paths
                    kind = "assets_entry"
                    for key in ("npc", "item", "block", "recipe", "interaction"):
                        if key in lower:
                            kind = f"assets_{key}"
                            break
                    out.append(
                        FileHit(
                            path=f"Assets.zip!{name}",
                            kind=kind,
                            summary=f"Asset pack entry: {name}",
                            keywords=[Path(name).stem.lower()],
                            size=0,
                        )
                    )
        except Exception as e:
            log.warning("Assets.zip catalog failed: %s", e)
        return out

    @staticmethod
    def _hit_dict(h: FileHit) -> dict[str, Any]:
        return {
            "path": h.path,
            "kind": h.kind,
            "summary": h.summary,
            "keywords": h.keywords,
            "size": h.size,
        }
