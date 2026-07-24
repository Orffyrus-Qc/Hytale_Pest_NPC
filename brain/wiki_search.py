"""Hytale wiki search via MediaWiki API (read-only, cached)."""

from __future__ import annotations

import logging
import re
import time
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx
import orjson

log = logging.getLogger("hytale_ai.wiki")

DEFAULT_API = "https://hytale.wiki.gg/api.php"
USER_AGENT = "PestAiNpc/0.1 (Hytale companion; read-only wiki research)"
CACHE_TTL_S = 3600.0
CACHE_VERSION = "v8"  # bump when ranking / query variants change

# Minimum score to treat a hit as related to the player's question
MIN_RELEVANCE = 8.0

# Common player words → better wiki search terms
_SYNONYMS: dict[str, list[str]] = {
    "bed": ["bedroll", "crude bedroll", "kweebec bed"],
    "beds": ["bedroll", "crude bedroll"],
    "sleep": ["bedroll", "respawn"],
    "spawn": ["bedroll", "respawn"],
    "respawn": ["bedroll", "crude bedroll"],
    "pick": ["pickaxe"],
    "axe": ["hatchet", "axe"],
    "ore": ["iron ore", "copper ore"],
    # Metals: always also look up the ore (where found)
    "iron": ["iron ore", "iron ingot"],
    "copper": ["copper ore", "copper ingot"],
    "thorium": ["thorium ore", "thorium ingot"],
    "cobalt": ["cobalt ore", "cobalt ingot"],
    "gold": ["gold ore", "gold ingot"],
    "silver": ["silver ore", "silver ingot"],
}

# Metals / resources where "where is it found?" is always useful
_RESOURCE_ROOTS = frozenset(
    {
        "iron",
        "copper",
        "thorium",
        "cobalt",
        "gold",
        "silver",
        "ore",
        "ingot",
        "wood",
        "fibre",
        "fiber",
        "hide",
        "stone",
        "basalt",
        "coal",
    }
)

# Match both "== Obtaining ==" and plain "Obtaining" section headers
_OBTAIN_SECTION_RE = re.compile(
    r"(?is)(?:^|\n)\s*(?:=+\s*)?(obtaining|location|locations|source|sources|where to find|"
    r"generation|mining|farming|gathering|drops|loot)\s*(?:=+\s*)?\n+(.*?)(?=\n\s*=+\s*\w|\n\s*(?:Usage|Crafting|Salvaging|Data|History|Navigation|Armor|Weapons|Tools)\b|\Z)"
)
_USAGE_SECTION_RE = re.compile(
    r"(?is)(?:^|\n)\s*(?:=+\s*)?(usage|uses|crafting|smelting|purpose)\s*(?:=+\s*)?\n+(.*?)(?=\n\s*=+\s*\w|\n\s*(?:Obtaining|Salvaging|Data|History|Navigation)\b|\Z)"
)
_LOCATION_LINE_RE = re.compile(
    r"(?i)\b(found|generates?|appears?|located|spawns?|mined|caves?|zone|wilds|"
    r"fens|basalt|underground|chest|outpost|vein|deeper|common|rare)\b"
)

# Words stripped when turning a player question into a wiki search query
_STOP = re.compile(
    r"\b("
    r"a|an|the|is|are|was|were|be|been|being|do|does|did|can|could|would|should|"
    r"will|shall|may|might|me|my|you|your|we|our|i|im|i'm|please|tell|about|"
    r"what|what's|whats|which|who|whom|whose|where|when|why|how|hows|"
    r"explain|describe|info|information|know|think|mean|means|called|"
    r"in|on|of|for|to|from|with|into|this|that|these|those|and|or|but|"
    r"game|hytale|pest|hey|hi|hello|just|really|also|something|anything|"
    r"like|need|want|use|used|using|get|got|make|made"
    r")\b",
    re.I,
)

_QUESTION_RE = re.compile(
    r"(?i)("
    r"\?|"
    r"^\s*(what|what's|whats|which|who|where|when|why|how|can|does|do|is|are|tell|explain|describe)\b|"
    r"\b(what is|what are|what does|what about|how do|how does|how to|where is|where do|tell me|explain)\b"
    r")"
)

# Chit-chat / acknowledgements — never wiki-search these alone
_FILLERS = frozenset(
    {
        "ok",
        "okay",
        "k",
        "kk",
        "yes",
        "yeah",
        "yep",
        "yup",
        "no",
        "nope",
        "nah",
        "thanks",
        "thank",
        "thx",
        "ty",
        "cool",
        "nice",
        "great",
        "good",
        "sure",
        "alright",
        "lol",
        "lmao",
        "haha",
        "hmm",
        "huh",
        "wow",
        "true",
        "right",
        "got it",
        "gotcha",
        "understood",
        "bye",
        "goodbye",
        "later",
        "more",
        "continue",
        "go on",
        "and",
        "so",
        "well",
        "idk",
        "dunno",
    }
)

_FOLLOWUP_MORE_RE = re.compile(
    r"(?i)^\s*(tell me more|more (please|info|information|details)?|and then|"
    r"what else|anything else|go on|continue|elaborate|explain more)\s*[?.!]?\s*$"
)

_PRONOUN_TOPIC_RE = re.compile(
    r"(?i)\b(it|that|this|them|those|one)\b|"
    r"\b(what about (it|that|this)|how about (it|that|this)|and (it|that|this))\b"
)


class WikiSearch:
    """Opensearch + fulltext search + page extracts against the public Hytale wiki."""

    def __init__(
        self,
        *,
        enabled: bool = False,
        api_url: str = DEFAULT_API,
        cache_dir: Path | None = None,
        timeout_s: float = 12.0,
    ) -> None:
        self._enabled = bool(enabled)
        self.api_url = (api_url or DEFAULT_API).rstrip("?")
        self.timeout_s = timeout_s
        self.cache_dir = cache_dir
        if cache_dir is not None:
            cache_dir.mkdir(parents=True, exist_ok=True)
            self.cache_path = cache_dir / "wiki_cache.json"
        else:
            self.cache_path = None
        self._cache: dict[str, Any] = {}
        self._load_cache()
        self.last_query: str = ""
        self.last_hit_count: int = 0
        self.last_error: str = ""
        self.last_ok_ts: float = 0.0
        # Conversation memory so follow-ups stay on-topic
        self.last_topic_query: str = ""
        self.last_topic_title: str = ""
        self.last_topic_url: str = ""
        self.last_answer: str = ""

    @property
    def enabled(self) -> bool:
        return self._enabled

    def set_enabled(self, on: bool) -> None:
        self._enabled = bool(on)

    def status(self) -> dict[str, Any]:
        return {
            "enabled": self._enabled,
            "api_url": self.api_url,
            "cache_entries": len(self._cache),
            "last_query": self.last_query,
            "last_hit_count": self.last_hit_count,
            "last_error": self.last_error or None,
            "last_ok_ts": self.last_ok_ts or None,
            "last_topic_title": self.last_topic_title or None,
            "last_topic_query": self.last_topic_query or None,
        }

    async def search(self, query: str, limit: int = 5) -> list[dict[str, Any]]:
        """Return wiki hits: title, url, snippet, extract (relevance-ranked)."""
        raw = (query or "").strip()
        if not self._enabled:
            return []
        if not raw:
            return []
        limit = max(1, min(int(limit), 12))
        variants = self._query_variants(raw)
        self.last_query = raw
        cache_key = f"{CACHE_VERSION}:s:{raw.lower()}:{limit}"
        cached = self._get_cache(cache_key)
        if cached is not None:
            self.last_hit_count = len(cached)
            self.last_error = ""
            self.last_ok_ts = time.time()
            return cached

        try:
            # Gather candidates from ALL variants (don't stop at first fill)
            titles: list[str] = []
            per_variant = max(limit, 5)
            for v in variants:
                if not v:
                    continue
                for t in await self._opensearch(v, limit=per_variant):
                    if t not in titles:
                        titles.append(t)
                for t in await self._fulltext_search(v, limit=per_variant):
                    if t not in titles:
                        titles.append(t)
                if len(titles) >= limit * 3:
                    break
            titles = titles[: max(limit * 3, 12)]

            hits: list[dict[str, Any]] = []
            if titles:
                extracts = await self._extracts(titles)
                for title in titles:
                    info = extracts.get(title) or {}
                    extract = (info.get("extract") or "").strip()
                    hits.append(
                        {
                            "title": title,
                            "url": info.get("url")
                            or f"https://hytalewiki.org/w/{quote(title.replace(' ', '_'))}",
                            "snippet": extract[:280],
                            "extract": extract[:900],
                            "source": "hytale_wiki",
                        }
                    )
                tokens = self._score_tokens(raw)
                for h in hits:
                    h["_score"] = self._hit_score(h, tokens, raw_query=raw)
                hits.sort(key=lambda h: float(h.get("_score") or 0), reverse=True)
                hits = hits[:limit]
            self.last_hit_count = len(hits)
            self.last_error = ""
            self.last_ok_ts = time.time()
            # Don't persist internal score fields in a way that breaks ranking later
            store = [{k: v for k, v in h.items() if k != "_score"} | {"_score": h.get("_score", 0)} for h in hits]
            self._set_cache(cache_key, store)
            return hits
        except Exception as e:
            self.last_error = str(e)
            self.last_hit_count = 0
            log.warning("Wiki search failed for %r: %s", raw, e)
            return []

    async def knowledge_brief(self, query: str, limit: int = 3) -> str:
        """Compact text for LLM / chat context."""
        hits = await self.search(query, limit=limit)
        hits = self._relevant_hits(hits, query)
        if not hits:
            if not self._enabled:
                return "Wiki search disabled."
            if self.last_error:
                return f"Wiki search error: {self.last_error[:120]}"
            return f"No wiki hits for {query!r}."
        parts = []
        for h in hits:
            snip = (h.get("extract") or h.get("snippet") or "").strip()
            snip = re.sub(r"\s+", " ", snip)[:320]
            parts.append(f"- {h.get('title')}: {snip} ({h.get('url')})")
        return "Hytale wiki:\n" + "\n".join(parts)

    async def answer_player_question(self, player_text: str, limit: int = 3) -> str | None:
        """
        Answer from Hytale wiki: parse the whole question, pull definition +
        where/how to obtain (e.g. iron → iron ore locations), stay on-topic.
        """
        if not self._enabled:
            return None
        query = self.extract_query_from_chat(player_text)
        if not query:
            return None

        if self._is_filler(query):
            return None

        search_q = self._resolve_followup_query(query)
        if not search_q:
            if self._is_more_followup(query) and not self.last_topic_title:
                return "What topic should I look up on the Hytale wiki?"
            return None

        intent = self._question_intent(search_q)
        related_queries = self._related_topic_queries(search_q, intent)

        # Primary + related pages (ore/location for metals, etc.)
        all_hits: list[dict[str, Any]] = []
        seen_titles: set[str] = set()
        for rq in [search_q, *related_queries]:
            hits = await self.search(rq, limit=max(limit, 6))
            for h in self._relevant_hits(hits, rq):
                t = (h.get("title") or "").lower()
                if t and t not in seen_titles:
                    seen_titles.add(t)
                    all_hits.append(h)

        if not all_hits:
            bag = self._content_bag(search_q)
            if bag and bag.lower() != search_q.lower():
                hits = await self.search(bag, limit=max(limit, 6))
                all_hits = self._relevant_hits(hits, bag)

        if not all_hits:
            if self.last_error:
                return (
                    f"I couldn't reach the Hytale wiki just now ({self.last_error[:80]}). "
                    "Ask again in a moment."
                )
            return (
                f"I checked the Hytale wiki for “{search_q}” but nothing matched closely enough. "
                "Try a clearer item, mob, zone, or craft name."
            )

        # Prefer ore/resource pages when player asked about a metal and we need "where found"
        primary = self._pick_primary_hit(all_hits, search_q, intent)
        reply = self._compose_answer(search_q, intent, primary, all_hits)

        title = primary.get("title") or "that"
        url = primary.get("url") or ""
        self.last_topic_query = search_q
        self.last_topic_title = str(title)
        self.last_topic_url = str(url)
        self.last_answer = reply
        return reply

    def _question_intent(self, query: str) -> dict[str, bool]:
        """Analyze the rest of the question for what the player needs."""
        q = query.lower()
        wants_where = bool(
            re.search(
                r"\b(where|find|found|locate|location|get|obtain|mine|gather|farm|spawn|"
                r"appear|generate|source|how (do|can) i (get|find|mine)|look for)\b",
                q,
            )
        )
        wants_how = bool(
            re.search(r"\b(how|craft|smelt|make|build|recipe|use|usage)\b", q)
        )
        wants_what = bool(
            re.search(r"\b(what|what's|whats|which|explain|tell|about|is)\b", q)
        ) or not (wants_where or wants_how)
        # Metals / resources: always try to include where-found when wiki has it
        bag = self._content_bag(query).lower()
        tokens = [t for t in re.split(r"\W+", bag) if t]
        is_resource = any(t in _RESOURCE_ROOTS for t in tokens) or any(
            t.endswith("ore") or t.endswith("ingot") for t in tokens
        )
        if is_resource:
            wants_where = True
            wants_what = True
        return {
            "where": wants_where,
            "how": wants_how,
            "what": wants_what,
            "resource": is_resource,
        }

    def _related_topic_queries(self, search_q: str, intent: dict[str, bool]) -> list[str]:
        """Extra wiki lookups from the question (iron → iron ore, etc.)."""
        bag = self._content_bag(search_q)
        tokens = [t.lower() for t in re.split(r"\W+", bag) if len(t) > 2]
        extra: list[str] = []
        for t in tokens:
            for syn in _SYNONYMS.get(t, []):
                if syn not in extra and syn.lower() != bag.lower():
                    extra.append(syn)
            if t in _RESOURCE_ROOTS and f"{t} ore" not in extra and t != "ore":
                extra.append(f"{t} ore")
            if intent.get("where") and t not in {"where", "find", "found"}:
                # dedicated location search still keyed to topic
                loc = f"{t} ore" if t in _RESOURCE_ROOTS and t != "ore" else t
                if loc not in extra:
                    extra.append(loc)
        # Prefer ore pages first for resource questions
        extra.sort(key=lambda s: (0 if "ore" in s.lower() else 1, s))
        return extra[:4]

    def _pick_primary_hit(
        self, hits: list[dict[str, Any]], search_q: str, intent: dict[str, bool]
    ) -> dict[str, Any]:
        """Choose the best main page (prefer matching resource + ore page)."""
        if not hits:
            return {}
        bag = self._content_bag(search_q).lower()
        tokens = [t for t in re.split(r"\W+", bag) if t]
        focus = self._resource_focus(search_q)

        def rank(h: dict[str, Any]) -> float:
            title = (h.get("title") or "").lower()
            extract = h.get("extract") or ""
            score = float(h.get("_score") or 0)
            # Hard preference: title must match focus resource when we have one
            if focus and focus in title:
                score += 40.0
            elif focus and not self._title_matches_focus(title, focus):
                score -= 30.0
            if intent.get("resource") or intent.get("where"):
                if title.endswith(" ore") or title.endswith("ore"):
                    score += 20.0
                if self._extract_obtaining(extract):
                    score += 12.0
            if bag and bag in title:
                score += 12.0
            if any(t == title for t in tokens):
                score += 10.0
            return score

        return max(hits, key=rank)

    def _resource_focus(self, search_q: str) -> str:
        """Main resource token (iron, copper, bed, …) used to avoid cross-metal mixups."""
        bag = self._content_bag(search_q).lower()
        tokens = [t for t in re.split(r"\W+", bag) if len(t) > 2]
        for t in tokens:
            if t in _RESOURCE_ROOTS and t not in {"ore", "ingot", "stone"}:
                return t
        # first meaty token
        for t in tokens:
            if t not in {"where", "find", "found", "about", "what", "craft"}:
                return t
        return tokens[0] if tokens else ""

    def _title_matches_focus(self, title: str, focus: str) -> bool:
        if not focus:
            return True
        t = (title or "").lower()
        return focus in t or any(
            syn.split()[0] in t for syn in _SYNONYMS.get(focus, []) if syn
        )

    def _compose_answer(
        self,
        search_q: str,
        intent: dict[str, bool],
        primary: dict[str, Any],
        all_hits: list[dict[str, Any]],
    ) -> str:
        """Definition + where found + usage, from wiki text only (same resource)."""
        title = primary.get("title") or "that"
        url = primary.get("url") or ""
        extract = (primary.get("extract") or primary.get("snippet") or "").strip()
        focus = self._resource_focus(search_q)

        summary = self._extract_summary(extract)
        obtaining = self._extract_obtaining(extract)
        usage = self._extract_usage(extract)

        # If primary lacks Obtaining, only borrow from same-resource pages (iron→iron ore, not copper)
        if not obtaining and (intent.get("where") or intent.get("resource") or intent.get("what")):
            same = [
                h
                for h in all_hits
                if self._title_matches_focus(h.get("title") or "", focus)
            ]
            # Prefer "* Ore" pages for locations
            same.sort(
                key=lambda h: (
                    0 if "ore" in (h.get("title") or "").lower() else 1,
                    -float(h.get("_score") or 0),
                )
            )
            for h in same:
                obt = self._extract_obtaining(h.get("extract") or "")
                if not obt:
                    continue
                obtaining = obt
                if not summary or len(summary) < 40:
                    summary = self._extract_summary(h.get("extract") or "") or summary
                # Prefer showing the ore title when locations came from the ore page
                ht = h.get("title") or title
                if "ore" in ht.lower() and "ore" not in title.lower():
                    title = ht
                    url = h.get("url") or url
                break

        if not usage and intent.get("how"):
            for h in all_hits:
                if not self._title_matches_focus(h.get("title") or "", focus):
                    continue
                u = self._extract_usage(h.get("extract") or "")
                if u:
                    usage = u
                    break

        parts: list[str] = []
        if summary:
            parts.append(f"{title}: {summary}")
        else:
            parts.append(f"{title}.")

        if obtaining and (intent.get("where") or intent.get("resource") or intent.get("what")):
            parts.append(f"Where to find / get it: {obtaining}")
        elif intent.get("where") and not obtaining:
            parts.append(
                "The wiki page I found does not list a clear location yet — "
                "try a more specific name (e.g. iron ore)."
            )

        if usage and (intent.get("how") or intent.get("resource")):
            parts.append(f"Use: {usage}")

        related_titles = []
        for h in all_hits:
            ht = h.get("title") or ""
            if not ht or ht.lower() == title.lower():
                continue
            if not self._title_matches_focus(ht, focus):
                continue
            if ht not in related_titles:
                related_titles.append(ht)
            if len(related_titles) >= 2:
                break
        if related_titles:
            parts.append("Also see: " + ", ".join(related_titles) + ".")

        if url:
            parts.append(f"(wiki: {url})")

        reply = " ".join(parts)
        if len(reply) > 900:
            reply = reply[:897].rsplit(" ", 1)[0] + "…"
        return reply

    @staticmethod
    def _extract_summary(extract: str) -> str:
        """First paragraph / lead before section headings."""
        if not extract:
            return ""
        text = extract.strip()
        # Cut at first == Section ==
        text = re.split(r"\n\s*==", text, maxsplit=1)[0]
        text = re.sub(r"\s+", " ", text).strip()
        # Drop "X may also refer to..." disambiguation tails for summary length
        if "may also refer" in text.lower():
            text = re.split(r"(?i)may also refer", text, maxsplit=1)[0].strip(" .")
            if text:
                text += "."
        if len(text) > 320:
            cut = text[:320]
            text = cut.rsplit(" ", 1)[0] + "…"
        return text

    @classmethod
    def _extract_obtaining(cls, extract: str) -> str:
        """Pull Obtaining/location section or location-like sentences."""
        if not extract:
            return ""
        # Prefer dedicated section
        m = _OBTAIN_SECTION_RE.search("\n" + extract)
        if m:
            body = m.group(2)
            return cls._clean_section_body(body, max_len=480)

        # Fallback: sentences that sound like locations
        sentences = re.split(r"(?<=[.!?])\s+|\n+", extract)
        loc_sents = []
        for s in sentences:
            s1 = s.strip()
            if len(s1) < 20:
                continue
            if _LOCATION_LINE_RE.search(s1) and not s1.startswith("=="):
                loc_sents.append(re.sub(r"\s+", " ", s1))
            if len(loc_sents) >= 4:
                break
        if not loc_sents:
            return ""
        return cls._clean_section_body(" ".join(loc_sents), max_len=420)

    @classmethod
    def _extract_usage(cls, extract: str) -> str:
        if not extract:
            return ""
        m = _USAGE_SECTION_RE.search("\n" + extract)
        if m:
            return cls._clean_section_body(m.group(2), max_len=280)
        # Short lead mentions like "smelted into"
        for s in re.split(r"(?<=[.!?])\s+", extract):
            if re.search(r"(?i)\b(smelt|craft|used to|can be used)\b", s):
                return cls._clean_section_body(s, max_len=220)
        return ""

    @staticmethod
    def _clean_section_body(body: str, max_len: int = 400) -> str:
        text = body.strip()
        # Strip wiki leftover bullets / empty subheadings
        text = re.sub(r"^\s*[\*\-•]\s*", "", text, flags=re.M)
        text = re.sub(r"=+\s*\w[\w\s]*\s*=+", " ", text)
        text = re.sub(r"\n+", " ", text)
        text = re.sub(r"\s+", " ", text).strip(" .")
        # Drop empty section shells / header-only leftovers
        if len(text) < 12:
            return ""
        if re.fullmatch(r"(?i)[\w\s]{0,40}", text) and not re.search(
            r"(?i)\b(found|cave|zone|craft|smelt|generat|mine|ore|ingot|place|respawn)\b",
            text,
        ):
            return ""
        if len(text) > max_len:
            cut = text[:max_len]
            text = cut.rsplit(" ", 1)[0] + "…"
        return text

    def _resolve_followup_query(self, query: str) -> str:
        """Map follow-ups onto the last wiki topic when needed."""
        q = query.strip()
        if self._is_more_followup(q):
            if self.last_topic_title:
                return self.last_topic_title
            if self.last_topic_query:
                return self.last_topic_query
            return ""
        if _PRONOUN_TOPIC_RE.search(q) and self.last_topic_title:
            # "what about it" / "how does that work" → last title + remaining content words
            bag = self._content_bag(q)
            # strip bare pronouns from bag
            bag = re.sub(r"(?i)\b(it|that|this|them|those|one)\b", " ", bag)
            bag = re.sub(r"\s+", " ", bag).strip()
            if bag:
                return f"{self.last_topic_title} {bag}"
            return self.last_topic_title
        return q

    @classmethod
    def _is_filler(cls, query: str) -> bool:
        c = query.lower().strip(" ?!.…,")
        if not c:
            return True
        if c in _FILLERS:
            return True
        # multi-word pure chit-chat
        if re.sub(r"\s+", " ", c) in _FILLERS:
            return True
        return False

    @classmethod
    def _is_more_followup(cls, query: str) -> bool:
        return bool(_FOLLOWUP_MORE_RE.match(query.strip()))

    def _relevant_hits(self, hits: list[dict[str, Any]], query: str) -> list[dict[str, Any]]:
        """Keep only hits with real title/token overlap for this query."""
        tokens = self._score_tokens(query)
        out: list[dict[str, Any]] = []
        for h in hits:
            score = float(h.get("_score") or self._hit_score(h, tokens, raw_query=query))
            extract = (h.get("extract") or "").strip()
            if "may refer to" in extract.lower() and score < 15:
                continue
            if score < MIN_RELEVANCE:
                continue
            if not extract and score < 12:
                continue
            out.append(h)
        return out

    @staticmethod
    def is_game_question(text: str) -> bool:
        """True when the player is asking for game knowledge (not chit-chat/commands)."""
        q = WikiSearch.extract_query_from_chat(text)
        if not q:
            return False
        if WikiSearch._is_filler(q):
            return False
        # "tell me more" only counts if we treat it as wiki follow-up later
        if WikiSearch._is_more_followup(q):
            return True
        content = WikiSearch._content_bag(q)
        if not content:
            return False
        if _QUESTION_RE.search(q):
            return True
        # Explicit topic phrases only (not every short message)
        words = [w for w in re.split(r"\W+", content) if w]
        if 1 <= len(words) <= 5 and not WikiSearch._looks_like_command(q):
            # Require at least one "meaty" token (item-like length)
            if any(len(w) >= 3 for w in words):
                return True
        return False

    @staticmethod
    def _looks_like_command(q: str) -> bool:
        c = q.lower().strip()
        commands = (
            "build ",
            "rebuild",
            "follow",
            "come here",
            "stay close",
            "stand down",
            "stop ",
            "loot",
            "pick up",
            "pickup",
            "go forward",
            "walk",
            "jump",
            "set spawn",
            "set bed",
            "place bed",
            "regear",
            "status",
        )
        bare = {
            "hunt",
            "follow",
            "follow me",
            "come here",
            "loot",
            "status",
            "jump",
            "forward",
            "peace",
            "rebuild",
        }
        if c in bare:
            return True
        return any(c.startswith(p) for p in commands)

    @staticmethod
    def extract_query_from_chat(text: str) -> str:
        """Strip plugin situation suffix and noise from player chat."""
        if not text:
            return ""
        # Plugin appends: "player text | situation: ..."
        if " | situation:" in text:
            text = text.split(" | situation:", 1)[0]
        text = text.strip()
        # Drop leading name address if still present
        text = re.sub(r"^(pest|hey pest|hi pest|yo pest)[,:\s]+", "", text, flags=re.I)
        return text.strip()

    @classmethod
    def _content_bag(cls, raw: str) -> str:
        q = cls.extract_query_from_chat(raw).strip(" ?!.…")
        cleaned = re.sub(
            r"(?i)^(can you |could you |please )?(tell me |explain |describe )?(about |what is |what are |what does |what about |what's |whats |how do i |how do you |how to |how do |where is |where do i |where can i |what can i )?",
            "",
            q,
        ).strip(" ?!.…")
        cleaned = re.sub(r"(?i)\b(work|works|working|for)\b", " ", cleaned)
        bag = _STOP.sub(" ", cleaned or q)
        bag = re.sub(r"[^\w\s\-']", " ", bag)
        bag = re.sub(r"\s+", " ", bag).strip()
        return bag

    @classmethod
    def _query_variants(cls, raw: str) -> list[str]:
        """Turn natural language into better wiki search strings."""
        q = cls.extract_query_from_chat(raw).strip(" ?!.…")
        variants: list[str] = []

        cleaned = re.sub(
            r"(?i)^(can you |could you |please )?(tell me |explain |describe )?(about |what is |what are |what does |what about |what's |whats |how do i |how do you |how to |how do |where is |where do i |where can i |what can i )?",
            "",
            q,
        ).strip(" ?!.…")
        cleaned = re.sub(r"(?i)\b(work|works|working)\b", " ", cleaned)
        cleaned = re.sub(r"\s+", " ", cleaned).strip(" ?!.…")

        bag = cls._content_bag(q)

        # Multi-word bag first (best signal), then full phrase, then individual nouns
        if bag and " " in bag:
            variants.append(bag)
        if cleaned and cleaned.lower() not in {v.lower() for v in variants}:
            variants.append(cleaned)
        if bag and bag.lower() not in {v.lower() for v in variants}:
            variants.append(bag)
        if bag:
            words = sorted((w for w in bag.split() if len(w) > 2), key=len, reverse=True)
            for w in words[:4]:
                if w not in variants:
                    variants.append(w)
                if w.endswith("s") and len(w) > 3:
                    stem = w[:-1]
                    if stem not in variants:
                        variants.append(stem)
                # Synonym expansion (bed → bedroll) — insert near front
                # Don't expand bed→bedroll when player clearly asked for "bed wars"
                if "wars" in q.lower() and w.lower() in {"bed", "beds"}:
                    continue
                for syn in _SYNONYMS.get(w.lower(), []):
                    if syn not in variants:
                        variants.insert(0, syn)
        if q and q.lower() not in {v.lower() for v in variants}:
            variants.append(q)

        ordered: list[str] = []
        for v in variants:
            v = v.strip()
            if v and v not in ordered:
                ordered.append(v)
        return ordered[:10]

    @classmethod
    def _score_tokens(cls, raw: str) -> list[str]:
        bag = cls._content_bag(raw)
        toks = [t.lower() for t in re.split(r"\W+", bag) if len(t) > 2]
        out: list[str] = []
        skip = {
            "the",
            "and",
            "for",
            "how",
            "what",
            "tell",
            "about",
            "wiki",
            "hytale",
            "more",
            "please",
            "info",
            "craft",  # too common alone; still kept if with another token via bag
        }
        for t in toks:
            if t not in out and t not in skip:
                out.append(t)
        # If we skipped everything (e.g. "craft"), keep original toks
        if not out and toks:
            out = [t for t in toks if t not in {"the", "and", "for"}][:4]
        return out

    @staticmethod
    def _hit_score(hit: dict[str, Any], tokens: list[str], raw_query: str = "") -> float:
        title = (hit.get("title") or "").lower()
        title_words = [w for w in re.split(r"\W+", title) if w]
        title_word_set = set(title_words)
        extract = (hit.get("extract") or hit.get("snippet") or "").lower()
        raw_l = (raw_query or "").lower()
        score = 0.0
        if not tokens:
            return 0.0

        # Expand tokens with synonyms for scoring only
        score_tokens = list(tokens)
        for t in tokens:
            for syn in _SYNONYMS.get(t, []):
                for part in re.split(r"\W+", syn):
                    if len(part) > 2 and part not in score_tokens:
                        score_tokens.append(part)

        matched = 0
        for t in score_tokens:
            if title == t:
                score += 22.0
                matched += 1
            elif t in title_word_set:
                score += 14.0
                matched += 1
            # Strong exact resource match: query "iron" → title "Iron Ore"
            elif t in title_word_set or (len(t) >= 4 and t in title.split()):
                pass  # already handled
            elif any(tw == t + "s" or tw == t + "es" for tw in title_words):
                # plural only (zone→zones) — NOT bed→bedrock
                score += 16.0
                matched += 1
            elif t in title and (
                f" {t} " in f" {title} "
                or title.startswith(t + " ")
                or title.endswith(" " + t)
            ):
                score += 10.0
                matched += 1
            elif t in extract:
                score += 1.0
        # Require some match — pure extract-length bonus alone must not win
        if matched == 0:
            return min(score, 3.0)
        if extract and "may refer to" in extract and "obtaining" not in extract.lower():
            score -= 10.0
        if len(extract) > 60:
            score += 1.5
        elif not extract:
            score -= 4.0
        # Prefer pages that actually document where/how to get the thing
        if re.search(r"(?i)==\s*obtaining\s*==", extract) or re.search(
            r"(?i)\b(generates? naturally|found in|appears deeper|caves? in)\b", extract
        ):
            score += 8.0
        if matched >= 2:
            score += 4.0

        # Prefer survival/furniture pages for craft/build wording
        if any(w in raw_l for w in ("craft", "build", "make", "recipe", "place", "sleep")):
            if any(k in title or k in extract for k in (
                "craft", "workbench", "recipe", "furniture", "bedroll", "bench", "place", "respawn",
            )):
                score += 12.0
            if any(k in title for k in ("wars", "minigame", "lobby", "bedrock")):
                score -= 14.0

        # Soft penalty: multi-word title where only one short token matched (e.g. Bed Wars)
        if len(title_words) >= 2 and matched == 1 and max(len(t) for t in tokens) <= 4:
            score -= 5.0

        # Strong boost when the full content bag appears in the title
        bag = " ".join(tokens)
        if bag and bag in title:
            score += 12.0
        # Explicit bedroll boost for bed questions (not "bed wars")
        if "wars" not in raw_l and ("bed" in tokens or "beds" in tokens or "bed" in raw_l):
            if "bedroll" in title:
                score += 18.0
            if title in {"bedrock", "bed wars"} and "rock" not in raw_l:
                score -= 15.0
        if "wars" in raw_l and "wars" in title:
            score += 20.0
        return score

    async def _opensearch(self, query: str, limit: int) -> list[str]:
        params = {
            "action": "opensearch",
            "search": query,
            "limit": str(limit),
            "namespace": "0",
            "format": "json",
        }
        data = await self._get_json(params)
        if isinstance(data, list) and len(data) >= 2 and isinstance(data[1], list):
            return [str(t) for t in data[1] if t][:limit]
        return []

    async def _fulltext_search(self, query: str, limit: int) -> list[str]:
        """MediaWiki list=search — better for multi-word / natural questions."""
        params = {
            "action": "query",
            "list": "search",
            "srsearch": query,
            "srlimit": str(limit),
            "srnamespace": "0",
            "format": "json",
        }
        data = await self._get_json(params)
        rows = ((data.get("query") or {}).get("search")) or []
        out: list[str] = []
        for row in rows:
            if isinstance(row, dict) and row.get("title"):
                out.append(str(row["title"]))
        return out[:limit]

    async def _extracts(self, titles: list[str]) -> dict[str, dict[str, Any]]:
        """Fetch page extracts one title at a time.

        MediaWiki TextExtracts shares a budget across multi-title queries and
        often returns empty extracts for later titles (Iron Ore was blank in
        batches). Single-title calls return full Obtaining sections.
        """
        out: dict[str, dict[str, Any]] = {}
        if not titles:
            return out
        for title in titles:
            if not title:
                continue
            try:
                info = await self._extract_one(title)
                if info is not None:
                    out[title] = info
                    resolved = info.get("resolved_title")
                    if resolved and str(resolved) not in out:
                        out[str(resolved)] = info
            except Exception as e:
                log.warning("extract failed for %r: %s", title, e)
        return out

    async def _extract_one(self, title: str) -> dict[str, Any] | None:
        params = {
            "action": "query",
            "prop": "extracts|info",
            "explaintext": "1",
            "exchars": "2200",
            "exlimit": "1",
            "inprop": "url",
            "redirects": "1",
            "titles": title,
            "format": "json",
        }
        data = await self._get_json(params)
        pages = (data.get("query") or {}).get("pages") or {}
        for page in pages.values():
            if not isinstance(page, dict) or page.get("missing") is not None:
                continue
            resolved = str(page.get("title") or title)
            extract = str(page.get("extract") or "").strip()
            if not extract:
                extract = await self._parse_plaintext(resolved)
            if not extract:
                return None
            return {
                "extract": extract[:2400],
                "url": str(page.get("fullurl") or page.get("canonicalurl") or ""),
                "resolved_title": resolved,
            }
        return None

    async def _parse_plaintext(self, title: str) -> str:
        """Fallback: parse wikitext and strip markup for Obtaining sections."""
        try:
            data = await self._get_json(
                {
                    "action": "parse",
                    "page": title,
                    "prop": "wikitext",
                    "format": "json",
                    "redirects": "1",
                }
            )
            wt = (data.get("parse") or {}).get("wikitext") or {}
            if isinstance(wt, dict):
                wt = wt.get("*", "")
            text = str(wt or "")
            if not text:
                return ""
            text = re.sub(r"\{\{[^{}]*\}\}", " ", text)
            # nested templates (simple multi-pass)
            for _ in range(3):
                nxt = re.sub(r"\{\{[^{}]*\}\}", " ", text)
                if nxt == text:
                    break
                text = nxt
            text = re.sub(r"\[\[([^|\]]+\|)?([^\]]+)\]\]", r"\2", text)
            text = re.sub(r"'{2,}", "", text)
            text = re.sub(r"<[^>]+>", " ", text)
            text = re.sub(r"\n{3,}", "\n\n", text)
            return text.strip()[:2400]
        except Exception as e:
            log.warning("parse fallback failed for %r: %s", title, e)
            return ""

    async def _get_json(self, params: dict[str, str]) -> Any:
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        async with httpx.AsyncClient(timeout=self.timeout_s, follow_redirects=True) as client:
            r = await client.get(self.api_url, params=params, headers=headers)
            r.raise_for_status()
            return r.json()

    def _get_cache(self, key: str) -> list[dict[str, Any]] | None:
        row = self._cache.get(key)
        if not row:
            return None
        if time.time() - float(row.get("ts", 0)) > CACHE_TTL_S:
            return None
        hits = row.get("hits")
        return hits if isinstance(hits, list) else None

    def _set_cache(self, key: str, hits: list[dict[str, Any]]) -> None:
        self._cache[key] = {"ts": time.time(), "hits": hits}
        if len(self._cache) > 200:
            oldest = sorted(self._cache.items(), key=lambda kv: float(kv[1].get("ts", 0)))[:50]
            for k, _ in oldest:
                self._cache.pop(k, None)
        self._save_cache()

    def _load_cache(self) -> None:
        if not self.cache_path or not self.cache_path.exists():
            return
        try:
            data = orjson.loads(self.cache_path.read_bytes())
            if isinstance(data, dict):
                self._cache = data.get("entries") or {}
        except Exception as e:
            log.warning("Wiki cache load failed: %s", e)

    def _save_cache(self) -> None:
        if not self.cache_path:
            return
        try:
            payload = {"entries": self._cache}
            self.cache_path.write_bytes(orjson.dumps(payload))
        except Exception as e:
            log.warning("Wiki cache save failed: %s", e)
