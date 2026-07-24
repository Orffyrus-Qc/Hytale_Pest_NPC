"""Dual-layer experience bank: per-case entries + distilled global patterns."""

from __future__ import annotations

import time
import uuid
from collections import Counter
from pathlib import Path
from typing import Any

import orjson


class ExperienceBank:
    def __init__(self, data_dir: Path, max_entries: int = 5000) -> None:
        self.dir = data_dir / "experience"
        self.dir.mkdir(parents=True, exist_ok=True)
        self.patterns_dir = data_dir / "patterns"
        self.patterns_dir.mkdir(parents=True, exist_ok=True)
        self.entries_path = self.dir / "entries.jsonl"
        self.patterns_path = self.patterns_dir / "global_patterns.json"
        self.max_entries = max_entries
        self._count = 0
        self._action_success: Counter[str] = Counter()
        self._action_fail: Counter[str] = Counter()
        self._load_stats()

    def _load_stats(self) -> None:
        if not self.entries_path.exists():
            return
        with self.entries_path.open("rb") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                self._count += 1
                try:
                    row = orjson.loads(line)
                    act = str(row.get("action", "idle"))
                    if row.get("reward", 0) >= 0.5:
                        self._action_success[act] += 1
                    else:
                        self._action_fail[act] += 1
                except Exception:
                    continue

    def add(
        self,
        *,
        situation: str,
        action: str,
        reward: float,
        obs: dict[str, Any] | None = None,
        diagnosis: str = "",
        source: str = "play",
    ) -> dict[str, Any]:
        entry = {
            "id": str(uuid.uuid4()),
            "ts": time.time(),
            "situation": situation[:500],
            "action": action,
            "reward": float(reward),
            "obs": obs or {},
            "diagnosis": diagnosis[:500],
            "source": source,
        }
        with self.entries_path.open("ab") as f:
            f.write(orjson.dumps(entry) + b"\n")
        self._count += 1
        if reward >= 0.5:
            self._action_success[action] += 1
        else:
            self._action_fail[action] += 1
        if self._count % 25 == 0:
            self.distill()
        self._trim_if_needed()
        return entry

    def distill(self) -> list[dict[str, Any]]:
        rates: list[dict[str, Any]] = []
        for act in set(self._action_success) | set(self._action_fail):
            s = self._action_success[act]
            f = self._action_fail[act]
            n = s + f
            rates.append(
                {
                    "action": act,
                    "success": s,
                    "fail": f,
                    "n": n,
                    "success_rate": (s / n) if n else 0.0,
                    "lesson": self._lesson(act, s, f),
                }
            )
        rates.sort(key=lambda r: (-r["n"], -r["success_rate"]))
        payload = {"updated_ts": time.time(), "patterns": rates[:40]}
        self.patterns_path.write_bytes(orjson.dumps(payload, option=orjson.OPT_INDENT_2))
        return rates

    def best_actions(self, situation: str, limit: int = 5) -> list[dict[str, Any]]:
        sit = situation.lower()
        scores: list[tuple[float, dict[str, Any]]] = []
        if not self.entries_path.exists():
            return self._pattern_prior(limit)
        # Scan last ~800 lines for similar situations
        lines = self.entries_path.read_bytes().splitlines()[-800:]
        for line in lines:
            try:
                row = orjson.loads(line)
            except Exception:
                continue
            blob = str(row.get("situation", "")).lower()
            overlap = sum(1 for tok in sit.split() if tok and tok in blob)
            score = overlap + float(row.get("reward", 0))
            if score > 0:
                scores.append((score, row))
        scores.sort(key=lambda x: -x[0])
        out = []
        seen = set()
        for _, row in scores:
            act = row.get("action")
            if act in seen:
                continue
            seen.add(act)
            out.append(row)
            if len(out) >= limit:
                break
        return out or self._pattern_prior(limit)

    def _pattern_prior(self, limit: int) -> list[dict[str, Any]]:
        if not self.patterns_path.exists():
            return []
        data = orjson.loads(self.patterns_path.read_bytes())
        pats = data.get("patterns", [])
        pats = sorted(pats, key=lambda p: (-p.get("success_rate", 0), -p.get("n", 0)))
        return [
            {
                "situation": p.get("lesson", ""),
                "action": p["action"],
                "reward": p.get("success_rate", 0),
                "source": "pattern",
            }
            for p in pats[:limit]
        ]

    def stats(self) -> dict[str, Any]:
        return {
            "entries": self._count,
            "top_success": self._action_success.most_common(8),
            "top_fail": self._action_fail.most_common(8),
            "patterns_file": str(self.patterns_path),
        }

    def _trim_if_needed(self) -> None:
        if self._count <= self.max_entries:
            return
        lines = self.entries_path.read_bytes().splitlines()
        keep = lines[-self.max_entries :]
        self.entries_path.write_bytes(b"\n".join(keep) + b"\n")
        self._count = len(keep)

    @staticmethod
    def _lesson(act: str, s: int, f: int) -> str:
        n = s + f
        if n == 0:
            return f"{act}: no data"
        rate = s / n
        if rate >= 0.7:
            return f"{act} works often ({s}/{n}); prefer when situation matches prior successes."
        if rate <= 0.3:
            return f"{act} fails often ({f}/{n}); avoid unless no alternative."
        return f"{act} is mixed ({s}/{n}); use context from similar cases."
