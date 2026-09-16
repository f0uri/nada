"""Nada — a calm Instagram media saver."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import re
import time
from collections import defaultdict
from pathlib import Path
from typing import Any
from urllib.parse import quote, urljoin, urlparse

import httpx
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"
_FALLBACK_SECRET = bytes((
    0x6e, 0x34, 0x64, 0x34, 0x37, 0x66, 0x33, 0x63,
    0x39, 0x65, 0x32, 0x61, 0x31, 0x62, 0x38, 0x34,
    0x64, 0x65, 0x77, 0x2d, 0x6b, 0x33, 0x79, 0x32,
    0x30, 0x32, 0x36, 0x78,
))
SECRET = (os.environ.get("NADA_MEDIA_SECRET") or "").encode() or _FALLBACK_SECRET
NADA_API_KEY = (os.environ.get("NADA_API_KEY") or "").strip()
TOKEN_TTL = 45 * 60
DOC_ID = "27128499623469141"
POLARIS_DOC = "27130156389949648"
APP_ID = "936619743392459"
IG_ANDROID_APP_ID = "567067343352427"
PROFILE_DOC_IDS = ("27937681195819736", "26672929172408668")

UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
)
IG_MOBILE_UA = (
    "Instagram 192.168.2.5.74 Android (31/12; 420dpi; 1080x2130; "
    "Xiaomi; M2101K6G; alioth; qcom; en_US; 376229147)"
)

URL_RE = re.compile(
    r"(?:https?://)?(?:www\.|m\.|l\.)?(?:instagram\.com|instagr\.am)"
    r"/(?:[^/?#]+/)?(?:p|tv|reel|reels)/([A-Za-z0-9_-]+)",
    re.I,
)
STORY_RE = re.compile(
    r"(?:https?://)?(?:www\.|m\.|l\.)?(?:instagram\.com|instagr\.am)/stories/"
    r"(?:highlights/(\d+)|([A-Za-z0-9._]+)/(\d+)|([A-Za-z0-9._]+)/?)",
    re.I,
)
SHORT_RE = re.compile(r"^[A-Za-z0-9_-]{5,32}$")
SKIP_SEGMENTS = {"p", "tv", "reel", "reels", "videos", "share", "stories", "embed"}
ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

ALLOWED_HOST_PARTS = (
    "cdninstagram.com",
    "fbcdn.net",
    "cdninstagram.net",
    "fbsbx.com",
)
IG_FETCH_HOSTS = {
    "instagram.com",
    "www.instagram.com",
    "l.instagram.com",
    "i.instagram.com",
    "instagr.am",
    "www.instagr.am",
}

_rate: dict[str, list[float]] = defaultdict(list)


class ResolveIn(BaseModel):
    url: str = Field(min_length=4, max_length=500)


class ProfileIn(BaseModel):
    username: str = Field(min_length=1, max_length=80)


USER_RE = re.compile(r"^[A-Za-z0-9._]{1,30}$")
USER_URL_RE = re.compile(
    r"(?:https?://)?(?:www\.|m\.|l\.)?(?:instagram\.com|instagr\.am)/([A-Za-z0-9._]+)/?",
    re.I,
)
RESERVED = {
    "p", "reel", "reels", "tv", "stories", "explore", "accounts", "about",
    "legal", "developer", "directory", "privacy", "terms", "lite", "share", "s",
}


class InstagramSession:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._csrf = ""
        self._lsd = ""
        self._csrf_at = 0.0
        self._post_cache: dict[str, dict[str, Any]] = {}
        self.client = httpx.AsyncClient(
            timeout=httpx.Timeout(10.0, connect=5.0),
            follow_redirects=True,
            headers={
                "User-Agent": UA,
                "Accept-Language": "ar,en-US;q=0.8,en;q=0.6",
                "Accept": "*/*",
            },
        )

    async def ensure(self) -> str:
        async with self._lock:
            if self._csrf and time.time() - self._csrf_at < 25 * 60:
                return self._csrf
            r = await self.client.get("https://www.instagram.com/")
            r.raise_for_status()
            csrf = r.cookies.get("csrftoken") or ""
            if not csrf:
                m = re.search(r'"csrf_token":"([^"]+)"', r.text)
                csrf = m.group(1) if m else ""
            if not csrf:
                raise RuntimeError("csrf")
            eq = re.search(r'<script[^>]*id="__eqmc"[^>]*>(\{.+?\})</script>', r.text)
            lsd = ""
            if eq:
                try:
                    lsd = str((json.loads(eq.group(1)) or {}).get("l") or "")
                except Exception:
                    lsd = ""
            if not lsd:
                m = re.search(r'\["LSD",\[\],\{"token":"([^"]+)"', r.text)
                lsd = m.group(1) if m else ""
            self._csrf = csrf
            self._lsd = lsd
            self._csrf_at = time.time()
            return csrf

    async def web_info(self, shortcode: str) -> dict[str, Any]:
        csrf = await self.ensure()
        variables = json.dumps(
            {
                "shortcode": shortcode,
                "__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider": False,
            },
            separators=(",", ":"),
        )
        headers = {
            "content-type": "application/x-www-form-urlencoded",
            "x-ig-app-id": APP_ID,
            "x-asbd-id": "359341",
            "x-csrftoken": csrf,
            "x-ig-www-claim": "0",
            "x-requested-with": "XMLHttpRequest",
            "origin": "https://www.instagram.com",
            "referer": f"https://www.instagram.com/p/{shortcode}/",
        }
        body = f"variables={quote(variables)}&doc_id={DOC_ID}"
        r = await self.client.post(
            "https://www.instagram.com/graphql/query",
            headers=headers,
            content=body,
        )
        if r.status_code in (401, 403):
            self._csrf_at = 0
            csrf = await self.ensure()
            headers["x-csrftoken"] = csrf
            r = await self.client.post(
                "https://www.instagram.com/graphql/query",
                headers=headers,
                content=body,
            )
        if r.status_code == 429:
            raise HTTPException(429, "rate")
        if r.status_code >= 400:
            raise HTTPException(502, "upstream")
        try:
            payload = r.json()
        except Exception as exc:
            raise HTTPException(502, "upstream") from exc
        items = (
            (payload.get("data") or {})
            .get("xdt_api__v1__media__shortcode__web_info", {})
            .get("items")
            or []
        )
        if items:
            return items[0]
        polaris = await self.polaris_media(shortcode)
        if polaris:
            return polaris
        raise HTTPException(404, "missing")

    async def polaris_media(self, shortcode: str) -> dict[str, Any] | None:
        try:
            csrf = await self.ensure()
        except Exception:
            return None
        pk = shortcode_to_media_id(shortcode)
        if not pk:
            return None
        body = {
            "lsd": self._lsd,
            "fb_api_caller_class": "RelayModern",
            "fb_api_req_friendly_name": "PolarisLoggedOutDesktopWWWPostRootContentQuery",
            "server_timestamps": "true",
            "variables": json.dumps({"media_id": pk}, separators=(",", ":")),
            "doc_id": POLARIS_DOC,
        }
        try:
            r = await self.client.post(
                "https://www.instagram.com/api/graphql",
                headers={
                    "content-type": "application/x-www-form-urlencoded",
                    "x-ig-app-id": APP_ID,
                    "x-asbd-id": "359341",
                    "x-csrftoken": csrf,
                    "x-fb-lsd": self._lsd,
                    "x-fb-friendly-name": "PolarisLoggedOutDesktopWWWPostRootContentQuery",
                    "x-requested-with": "XMLHttpRequest",
                    "origin": "https://www.instagram.com",
                    "referer": f"https://www.instagram.com/reel/{shortcode}/",
                },
                data=body,
            )
            payload = r.json()
        except Exception:
            return None
        polaris = ((payload.get("data") or {}).get("xig_polaris_media")) or {}
        item = polaris.get("if_not_gated_logged_out") or polaris
        if isinstance(item, dict) and (item.get("pk") or item.get("code")):
            return item
        return None

    async def profile(self, username: str) -> dict[str, Any]:
        user = await self.web_profile(username)
        if not user:
            user = await self.profile_from_html(username)
        elif not extract_user_bio(user):
            try:
                html_user = await self.profile_from_html(username)
            except HTTPException:
                html_user = None
            if html_user:
                merge_profile_user(user, html_user)
        if not user:
            raise HTTPException(404, "missing")
        return await self.enrich_profile(user)

    async def web_profile(self, username: str) -> dict[str, Any] | None:
        sources = (
            (
                f"https://www.instagram.com/api/v1/users/web_profile_info/?username={quote(username)}",
                False,
            ),
            (
                f"https://i.instagram.com/api/v1/users/web_profile_info/?username={quote(username)}",
                True,
            ),
        )
        for url, mobile in sources:
            data = await self._ig_get(
                url, f"https://www.instagram.com/{username}/", mobile=mobile
            )
            user = ((data or {}).get("data") or {}).get("user") or {}
            if user.get("username"):
                return user
        return None

    async def profile_from_html(self, username: str) -> dict[str, Any]:
        page = await self.client.get(
            f"https://www.instagram.com/{username}/",
            headers={"referer": "https://www.instagram.com/"},
        )
        if page.status_code == 429:
            raise HTTPException(429, "rate")
        if page.status_code >= 400:
            raise HTTPException(404, "missing")
        html = page.text or ""
        if is_login_wall(html):
            raise HTTPException(404, "blocked")
        decoded = decode_entities(html)
        pic = re.search(r'property="og:image" content="([^"]+)"', decoded)
        title = re.search(r'property="og:title" content="([^"]+)"', decoded)
        desc = re.search(r'property="og:description" content="([^"]+)"', decoded)
        hd = re.search(r'"profile_pic_url_hd":"((?:\\.|[^"\\])*)"', html)
        if not pic and not hd and not title:
            raise HTTPException(404, "missing")
        raw_title = decode_entities(title.group(1)) if title else ""
        name = re.sub(r"\s*[•·].*$", "", raw_title)
        name = re.sub(r"\s*\(@?[^)]*\)\s*", "", name)
        name = re.sub(r"Instagram photos and videos", "", name, flags=re.I).strip() or username
        raw_desc = decode_entities(desc.group(1)) if desc else ""
        uid = extract_html_user_id(html) or extract_html_user_id(decoded)
        avatar = ""
        if hd:
            avatar = unescape_ig(hd.group(1))
        elif pic:
            avatar = pic.group(1).replace("&amp;", "&")
        return {
            "username": username,
            "full_name": name,
            "biography": extract_html_bio(html) or extract_html_bio(decoded),
            "profile_pic_url_hd": avatar,
            "follower_count": parse_og_stat(raw_desc, "Followers"),
            "following_count": parse_og_stat(raw_desc, "Following"),
            "media_count": parse_og_stat(raw_desc, "Posts"),
            "id": uid,
            "pk": uid,
        }

    async def graphql_profile(self, user_id: str) -> dict[str, Any] | None:
        csrf = await self.ensure()
        variables = json.dumps(
            {
                "id": str(user_id),
                "render_surface": "PROFILE",
                "__relay_internal__pv__PolarisCannesGuardianExperienceEnabledrelayprovider": True,
                "__relay_internal__pv__PolarisCASB976ProfileEnabledrelayprovider": False,
                "__relay_internal__pv__PolarisRepostsConsumptionEnabledrelayprovider": False,
                "__relay_internal__pv__PolarisWebSchoolsEnabledrelayprovider": False,
                "enable_integrity_filters": True,
            },
            separators=(",", ":"),
        )
        headers = {
            "content-type": "application/x-www-form-urlencoded",
            "x-ig-app-id": APP_ID,
            "x-asbd-id": "359341",
            "x-csrftoken": csrf,
            "x-ig-www-claim": "0",
            "x-requested-with": "XMLHttpRequest",
            "origin": "https://www.instagram.com",
            "referer": "https://www.instagram.com/",
        }
        body_base = f"variables={quote(variables)}&doc_id="
        for doc_id in PROFILE_DOC_IDS:
            try:
                r = await self.client.post(
                    "https://www.instagram.com/graphql/query",
                    headers=headers,
                    content=body_base + doc_id,
                )
            except Exception:
                continue
            if r.status_code >= 400:
                continue
            try:
                payload = r.json()
            except Exception:
                continue
            user = ((payload.get("data") or {}).get("user")) or {}
            if user.get("username") or user.get("pk") or user.get("id"):
                return user
        return None

    async def enrich_profile(self, user: dict[str, Any]) -> dict[str, Any]:
        uid = str(user.get("id") or user.get("pk") or "")
        if uid and not extract_user_bio(user):
            extra = await self.graphql_profile(uid)
            if extra:
                merge_profile_user(user, extra)
        user["biography"] = compose_profile_bio(user)
        return user

    async def _ig_get(self, url: str, referer: str, mobile: bool = False) -> dict[str, Any] | None:
        try:
            csrf = await self.ensure()
        except Exception:
            return None
        r = await self.client.get(
            url,
            headers={
                "User-Agent": IG_MOBILE_UA if mobile else UA,
                "x-ig-app-id": IG_ANDROID_APP_ID if mobile else APP_ID,
                "x-asbd-id": "359341",
                "x-csrftoken": csrf,
                "x-ig-www-claim": "0",
                "x-requested-with": "XMLHttpRequest",
                "referer": referer,
            },
        )
        if r.status_code >= 400:
            return None
        try:
            data = r.json()
        except Exception:
            return None
        return data if isinstance(data, dict) else None

    async def stories(self, user: dict[str, Any]) -> list[dict[str, Any]]:
        user_id = "".join(ch for ch in str(user.get("id") or user.get("pk") or "") if ch.isdigit())[:20]
        items: list[dict[str, Any]] = []
        seen: set[str] = set()

        def add_node(node: dict[str, Any], title: str) -> None:
            packed = pack_media(node, len(items) + 1)
            if not packed:
                return
            key = str(packed.get("id") or "")
            if key and key in seen:
                return
            if key:
                seen.add(key)
            packed["title"] = title[:18] or "قصة"
            items.append(packed)

        def add_cover(hid: str, title: str, cover: str) -> None:
            if not cover or not host_ok(cover):
                return
            key = f"cover:{hid or title}"
            if key in seen:
                return
            seen.add(key)
            items.append({
                "id": hid or title,
                "index": len(items) + 1,
                "kind": "image",
                "preview": sign_url(cover),
                "file": sign_url(cover),
                "ext": "jpg",
                "title": (title or "هايلايت")[:18],
            })

        if user_id:
            live_urls = (
                (f"https://i.instagram.com/api/v1/feed/user/{quote(user_id)}/reel_media/", True),
                (f"https://i.instagram.com/api/v1/feed/user/{quote(user_id)}/story/", True),
                (f"https://i.instagram.com/api/v1/feed/reels_media/?reel_ids={quote(user_id)}", True),
                (f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids={quote(user_id)}", False),
                (f"https://www.instagram.com/api/v1/feed/user/{quote(user_id)}/story/", False),
            )
            for url, mobile in live_urls:
                data = await self._ig_get(url, "https://www.instagram.com/", mobile=mobile)
                before = len(items)
                for node in collect_reel_nodes(data, user_id):
                    add_node(node, "قصة")
                if len(items) > before:
                    break

            tray_urls = (
                (f"https://i.instagram.com/api/v1/highlights/{quote(user_id)}/highlights_tray/", True),
                (f"https://www.instagram.com/api/v1/highlights/{quote(user_id)}/highlights_tray/", False),
            )
            highlights: list[Any] = []
            for tray_url, mobile in tray_urls:
                tray_data = await self._ig_get(tray_url, "https://www.instagram.com/", mobile=mobile)
                highlights = (tray_data or {}).get("tray") or []
                if highlights:
                    break
            for hl in highlights[:12]:
                if not isinstance(hl, dict):
                    continue
                hid = str(hl.get("id") or "").replace("highlight:", "")
                cover = (
                    ((hl.get("cover_media") or {}).get("cropped_image_version") or {}).get("url")
                    or ((hl.get("cover_media") or {}).get("thumbnail_src"))
                    or ""
                )
                title = str(hl.get("title") or "هايلايت")
                added = 0
                if hid:
                    detail = await self._ig_get(
                        f"https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:{quote(hid)}",
                        "https://www.instagram.com/",
                        mobile=True,
                    ) or await self._ig_get(
                        f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:{quote(hid)}",
                        "https://www.instagram.com/",
                    )
                    for node in collect_reel_nodes(detail, f"highlight:{hid}"):
                        before = len(items)
                        add_node(node, title)
                        if len(items) > before:
                            added += 1
                if added == 0:
                    add_cover(hid, title, cover)

        if not items:
            edges = ((user.get("edge_highlight_reels") or {}).get("edges")) or []
            for edge in edges[:12]:
                node = (edge or {}).get("node") or {}
                cover = ((node.get("cover_media") or {}).get("thumbnail_src")) or ""
                title = str(node.get("title") or "هايلايت")
                add_cover(str(node.get("id") or title), title, cover)
        return items[:24]

    async def media_info(self, media_id: str) -> dict[str, Any] | None:
        mid = "".join(ch for ch in media_id if ch.isdigit())
        if not mid:
            return None
        for url, mobile in (
            (f"https://www.instagram.com/api/v1/media/{mid}/info/", False),
            (f"https://i.instagram.com/api/v1/media/{mid}/info/", True),
        ):
            data = await self._ig_get(url, "https://www.instagram.com/", mobile=mobile)
            items = (data or {}).get("items") or []
            if items and isinstance(items[0], dict):
                return items[0]
        code = media_id_to_shortcode(mid)
        if not code:
            return None
        try:
            return await self.web_info(code)
        except HTTPException:
            return None

    async def resolve_story(self, raw: str) -> dict[str, Any]:
        parsed = parse_story(raw)
        if not parsed:
            raise HTTPException(400, "url")
        if parsed["kind"] == "highlight":
            hid = parsed["id"]
            detail = await self._ig_get(
                f"https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:{quote(hid)}",
                "https://www.instagram.com/",
                mobile=True,
            ) or await self._ig_get(
                f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:{quote(hid)}",
                "https://www.instagram.com/",
            )
            nodes = collect_reel_nodes(detail, f"highlight:{hid}")
            if not nodes:
                raise HTTPException(404, "missing")
            first = nodes[0]
            post = pack_post(first if len(nodes) == 1 else {**first, "carousel_media": nodes, "media_type": 8}, hid)
            post["kind"] = "carousel" if len(nodes) > 1 else post["kind"]
            post["source"] = f"https://www.instagram.com/stories/highlights/{hid}/"
            if not post["media"]:
                raise HTTPException(404, "empty")
            return post
        media_id = parsed.get("id") or ""
        username = parsed.get("username") or ""
        item = await self.media_info(media_id) if media_id else None
        if not item and username:
            user = await self.profile(username)
            stories = await self.stories(user)
            if not stories:
                raise HTTPException(404, "missing")
            owner = {
                "username": user.get("username") or username,
                "name": user.get("full_name") or "",
                "avatar": None,
            }
            avatar = user.get("profile_pic_url_hd") or user.get("profile_pic_url") or ""
            if isinstance(avatar, str) and host_ok(avatar):
                owner["avatar"] = sign_url(avatar)
            return {
                "ok": True,
                "shortcode": media_id or username,
                "kind": "carousel" if len(stories) > 1 else stories[0].get("kind") or "photo",
                "taken_at": None,
                "likes": None,
                "comments": None,
                "caption": "",
                "owner": owner,
                "media": stories,
                "source": f"https://www.instagram.com/stories/{username}/",
            }
        if not item:
            raise HTTPException(404, "missing")
        code = item.get("code") or media_id_to_shortcode(media_id) or media_id
        post = pack_post(item, code)
        post["source"] = f"https://www.instagram.com/stories/{username or item.get('user', {}).get('username') or ''}/{media_id}/"
        if not post["media"]:
            raise HTTPException(404, "empty")
        return post

    async def user_posts(self, user: dict[str, Any], cursor: str | None = None) -> tuple[list[dict[str, Any]], str]:
        posts: list[dict[str, Any]] = []
        next_cursor = ""
        user_id = "".join(ch for ch in str(user.get("id") or user.get("pk") or "") if ch.isdigit())[:20]
        if user_id:
            q = f"count=18"
            if cursor:
                q += f"&max_id={quote(cursor[:300])}"
            data = await self._ig_get(
                f"https://www.instagram.com/api/v1/feed/user/{quote(user_id)}/?{q}",
                f"https://www.instagram.com/{user.get('username') or ''}/",
            )
            for node in (data or {}).get("items") or []:
                if isinstance(node, dict):
                    self.remember_post(node)
                    packed = pack_grid_item(node)
                    if packed:
                        posts.append(packed)
            if (data or {}).get("more_available"):
                next_cursor = str((data or {}).get("next_max_id") or "")
        if not posts:
            edges = ((user.get("edge_owner_to_timeline_media") or {}).get("edges")) or []
            for edge in edges:
                node = (edge or {}).get("node") or edge
                if isinstance(node, dict):
                    self.remember_post(node)
                    packed = pack_grid_item(node)
                    if packed:
                        posts.append(packed)
            page = (user.get("edge_owner_to_timeline_media") or {}).get("page_info") or {}
            if page.get("has_next_page"):
                next_cursor = page.get("end_cursor") or ""
        return posts, next_cursor

    def remember_post(self, node: dict[str, Any]) -> None:
        shortcode = str(node.get("code") or node.get("shortcode") or "")
        if not shortcode:
            return
        try:
            packed = pack_post(node, shortcode)
        except Exception:
            return
        if packed.get("media"):
            self._post_cache[shortcode] = packed
            while len(self._post_cache) > 48:
                self._post_cache.pop(next(iter(self._post_cache)))

    def cached_post(self, shortcode: str) -> dict[str, Any] | None:
        found = self._post_cache.get(shortcode)
        return dict(found) if found else None


ig = InstagramSession()
app = FastAPI(title="Nada", docs_url=None, redoc_url=None)


@app.middleware("http")
async def secure_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers.setdefault(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self'; "
        "font-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; "
        "base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
    )
    return response


def client_ip(request: Request) -> str:
    return (request.client.host if request.client else "0") or "0"


def require_key(request: Request) -> None:
    if not NADA_API_KEY:
        return
    got = request.headers.get("x-nada-key") or ""
    if len(got) != len(NADA_API_KEY) or not hmac.compare_digest(got.encode(), NADA_API_KEY.encode()):
        raise HTTPException(401, "auth")


def hit_rate(ip: str, limit: int = 24, window: float = 60.0) -> None:
    now = time.time()
    if len(_rate) > 4096:
        stale = [k for k, v in list(_rate.items()) if not v or now - v[-1] >= window]
        for key in stale[:2048]:
            _rate.pop(key, None)
    bucket = [t for t in _rate[ip] if now - t < window]
    if len(bucket) >= limit:
        raise HTTPException(429, "rate")
    bucket.append(now)
    _rate[ip] = bucket


def _safe_chr(n: int) -> str:
    if n < 0 or n > 0x10FFFF or 0xD800 <= n <= 0xDFFF:
        return ""
    return chr(n)


def decode_entities(value: str) -> str:
    text = (
        (value or "")
        .replace("&amp;", "&")
        .replace("&quot;", '"')
        .replace("&#039;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#x2022;", "•")
        .replace("&#8226;", "•")
        .replace("&bull;", "•")
    )
    text = re.sub(r"&#(\d+);", lambda m: _safe_chr(int(m.group(1))), text)
    text = re.sub(r"&#x([0-9a-fA-F]+);", lambda m: _safe_chr(int(m.group(1), 16)), text)
    return text


GENERIC_BIO_RE = re.compile(
    r"^(See Instagram photos and videos|Create an account or log in)\b",
    re.I,
)
OG_STATS_RE = re.compile(r"\d[\d.,]*\s*[KMB]?\s*Followers", re.I)
INSTAGRAM_QUOTE_RE = re.compile(r"on Instagram:\s*[\"“](.*)[\"”]\s*$", re.I | re.S)
POSTS_TAIL_RE = re.compile(r"Posts\s*[-–—]\s*(.+)$", re.I | re.S)
LOGIN_SLOGAN = "create an account or log in to instagram"


def unescape_ig(value: str) -> str:
    text = value or ""
    if not text:
        return ""
    try:
        parsed = json.loads(f'"{text}"')
        if isinstance(parsed, str):
            text = parsed
    except Exception:
        text = (
            text.replace("\\n", "\n")
            .replace("\\/", "/")
            .replace('\\"', '"')
            .replace("\\u0026", "&")
        )
        text = re.sub(
            r"\\u([0-9a-fA-F]{4})",
            lambda m: chr(int(m.group(1), 16)),
            text,
        )
    return decode_entities(text).strip()


def is_generic_bio(text: str) -> bool:
    value = (text or "").strip()
    if not value:
        return True
    if GENERIC_BIO_RE.search(value):
        return True
    return LOGIN_SLOGAN in value.lower()


def is_login_wall(html: str) -> bool:
    low = (html or "").lower()
    return LOGIN_SLOGAN in low or (
        "/accounts/login" in low and "profile_pic_url_hd" not in low and '"biography"' not in low
    )


def extract_user_bio(user: dict[str, Any]) -> str:
    raw = user.get("biography")
    if isinstance(raw, dict):
        raw = raw.get("text") or raw.get("sensitive_string_value") or raw.get("raw_text") or ""
    bio = unescape_ig(raw if isinstance(raw, str) else "")
    if bio and not is_generic_bio(bio):
        return bio
    nested = user.get("biography_with_entities")
    if isinstance(nested, dict):
        bio = unescape_ig(str(nested.get("raw_text") or ""))
        if bio and not is_generic_bio(bio):
            return bio
    return ""


def collect_bio_links(user: dict[str, Any]) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    ext = str(user.get("external_url") or "").strip()
    if ext:
        seen.add(ext)
        out.append(ext)
    links = user.get("bio_links") or []
    if isinstance(links, list):
        for link in links:
            if not isinstance(link, dict):
                continue
            url = str(link.get("url") or "").strip()
            title = str(link.get("title") or "").strip()
            if not url or url in seen:
                continue
            seen.add(url)
            if title and title.lower() not in url.lower():
                out.append(f"{title}\n{url}")
            else:
                out.append(url)
    return out


def compose_profile_bio(user: dict[str, Any]) -> str:
    bio = extract_user_bio(user)
    extras = [item for item in collect_bio_links(user) if item and item not in bio]
    if extras:
        return f"{bio}\n{chr(10).join(extras)}".strip() if bio else "\n".join(extras)
    return bio


def extract_og_bio(desc: str) -> str:
    text = decode_entities(desc or "").strip()
    if not text:
        return ""
    quoted = INSTAGRAM_QUOTE_RE.search(text)
    if quoted:
        inner = decode_entities(quoted.group(1)).strip()
        return "" if is_generic_bio(inner) else inner
    tail = POSTS_TAIL_RE.search(text)
    if tail:
        piece = tail.group(1).strip()
        quoted = INSTAGRAM_QUOTE_RE.search(piece)
        if quoted:
            inner = decode_entities(quoted.group(1)).strip()
            return "" if is_generic_bio(inner) else inner
        if is_generic_bio(piece) or re.match(r".+\(@[\w.]+\)\s*$", piece):
            return ""
        return piece
    if OG_STATS_RE.search(text):
        return ""
    return "" if is_generic_bio(text) else text


def extract_html_bio(html: str) -> str:
    for pat in (
        r'"biography":"((?:\\.|[^"\\])*)"',
        r'"raw_text":"((?:\\.|[^"\\])*)"',
    ):
        for match in re.finditer(pat, html or ""):
            bio = unescape_ig(match.group(1))
            if bio and not is_generic_bio(bio):
                return bio
    meta = re.search(r'name="description" content="([^"]*)"', html or "")
    if meta:
        bio = extract_og_bio(meta.group(1))
        if bio:
            return bio
    og = re.search(r'property="og:description" content="([^"]*)"', html or "")
    if og:
        bio = extract_og_bio(og.group(1))
        if bio:
            return bio
    return ""


def extract_html_user_id(html: str) -> str:
    for pat in (
        r'"profile_id":"(\d+)"',
        r'"logging_page_id":"profilePage_(\d+)"',
        r"profilePage_(\d+)",
        r'"user_id"\s*:\s*"(\d+)"',
        r'"pk":"(\d{5,})"',
        r'"id":"(\d{5,})","username"',
        r'"username":"[^"]{1,30}","id":"(\d{5,})"',
        r"instagram://user\?username=[^&\"]+&user_id=(\d+)",
    ):
        match = re.search(pat, html or "")
        if match:
            return match.group(1)
    return ""


def merge_profile_user(user: dict[str, Any], extra: dict[str, Any]) -> None:
    if not extract_user_bio(user):
        bio = extract_user_bio(extra)
        if bio:
            user["biography"] = bio
        if extra.get("biography_with_entities"):
            user["biography_with_entities"] = extra["biography_with_entities"]
    if not user.get("full_name") and extra.get("full_name"):
        user["full_name"] = extra["full_name"]
    if not user.get("username") and extra.get("username"):
        user["username"] = extra["username"]
    hd = extra.get("hd_profile_pic_url_info")
    if isinstance(hd, dict) and hd.get("url") and not user.get("profile_pic_url_hd"):
        user["profile_pic_url_hd"] = hd["url"]
    if extra.get("profile_pic_url") and not user.get("profile_pic_url"):
        user["profile_pic_url"] = extra["profile_pic_url"]
    if extra.get("follower_count") is not None and user.get("follower_count") is None:
        if not ((user.get("edge_followed_by") or {}).get("count") is not None):
            user["follower_count"] = extra["follower_count"]
    if extra.get("following_count") is not None and user.get("following_count") is None:
        if not ((user.get("edge_follow") or {}).get("count") is not None):
            user["following_count"] = extra["following_count"]
    if extra.get("media_count") is not None and user.get("media_count") is None:
        if not ((user.get("edge_owner_to_timeline_media") or {}).get("count") is not None):
            user["media_count"] = extra["media_count"]
    if extra.get("bio_links"):
        user["bio_links"] = extra["bio_links"]
    if extra.get("external_url") and not user.get("external_url"):
        user["external_url"] = extra["external_url"]
    if extra.get("id") and not user.get("id"):
        user["id"] = extra["id"]
    if extra.get("pk") and not user.get("pk"):
        user["pk"] = extra["pk"]


def parse_og_stat(desc: str, label: str) -> int | None:
    m = re.search(rf"([\d.,]+\s*[KMB]?)\s*{label}", desc or "", re.I)
    if not m:
        return None
    raw = m.group(1).replace(",", "").replace(" ", "").upper()
    n = re.match(r"^([\d.]+)([KMB])?$", raw)
    if not n:
        return None
    base = float(n.group(1))
    mul = {"K": 1_000, "M": 1_000_000, "B": 1_000_000_000}.get(n.group(2) or "", 1)
    return int(base * mul)


def parse_username(raw: str) -> str:
    text = (raw or "").strip().lstrip("@")
    if "instagram." in text.lower() or "instagr.am" in text.lower():
        found = USER_URL_RE.search(text)
        if found:
            text = found.group(1)
    text = text.strip().strip("/")
    if text.lower() in RESERVED or not USER_RE.fullmatch(text):
        raise HTTPException(400, "user")
    return text


def pack_profile(user: dict[str, Any]) -> dict[str, Any]:
    avatar = user.get("profile_pic_url_hd") or user.get("profile_pic_url") or ""
    if not avatar:
        hd = user.get("hd_profile_pic_url_info")
        if isinstance(hd, dict):
            avatar = hd.get("url") or ""
    if isinstance(avatar, str):
        avatar = avatar.replace("\\u0026", "&")
    followers = (user.get("edge_followed_by") or {}).get("count")
    if followers is None:
        followers = user.get("follower_count")
    following = (user.get("edge_follow") or {}).get("count")
    if following is None:
        following = user.get("following_count")
    posts = (user.get("edge_owner_to_timeline_media") or {}).get("count")
    if posts is None:
        posts = user.get("media_count")
    return {
        "ok": True,
        "username": user.get("username") or "",
        "name": user.get("full_name") or "",
        "bio": compose_profile_bio(user),
        "private": bool(user.get("is_private")),
        "followers": followers,
        "following": following,
        "post_count": posts,
        "avatar": sign_url(avatar) if avatar and host_ok(avatar) else None,
        "stories": [],
        "posts": [],
        "user_id": str(user.get("id") or user.get("pk") or ""),
        "cursor": "",
    }


def pack_grid_item(node: dict[str, Any]) -> dict[str, Any] | None:
    shortcode = node.get("code") or node.get("shortcode") or ""
    if not shortcode:
        return None
    media_type = node.get("media_type")
    product = (node.get("product_type") or "").lower()
    is_video = bool(node.get("is_video") or media_type == 2 or node.get("video_versions"))
    kind = "photo"
    if product == "clips" or node.get("__typename") == "GraphVideo" and product == "clips":
        kind = "reel"
    elif media_type == 8 or node.get("__typename") == "GraphSidecar" or node.get("carousel_media"):
        kind = "carousel"
    elif is_video:
        kind = "reel" if product in ("clips", "igtv") else "video"
    preview = ""
    image = best_image(node)
    if image:
        preview = image.get("url") or ""
    preview = preview or node.get("display_url") or node.get("thumbnail_src") or node.get("display_uri") or ""
    if not preview:
        cands = ((node.get("image_versions2") or {}).get("candidates")) or []
        if cands:
            preview = cands[0].get("url") or ""
    if preview and not host_ok(preview):
        preview = ""
    return {
        "shortcode": shortcode,
        "kind": kind,
        "preview": sign_url(preview) if preview else None,
    }


def shortcode_from(url: str) -> str | None:
    path = (urlparse(url).path or "")
    parts = [p for p in path.split("/") if p]
    if any(p.lower() in {"share", "stories"} for p in parts):
        return None
    try:
        type_at = next(i for i, p in enumerate(parts) if p.lower() in {"p", "tv", "reel", "reels"})
    except StopIteration:
        return None
    for part in parts[type_at + 1 :]:
        if part.lower() in SKIP_SEGMENTS:
            continue
        if SHORT_RE.fullmatch(part):
            return part
    return None


def parse_shortcode(raw: str) -> str:
    text = (raw or "").strip()
    if SHORT_RE.fullmatch(text):
        return text
    found = shortcode_from(text) or shortcode_from(tidy_ig_url(text))
    if found:
        return found
    raise HTTPException(400, "url")


def host_ok(url: str) -> bool:
    try:
        parsed = urlparse(url)
        if parsed.scheme != "https":
            return False
        host = (parsed.hostname or "").lower()
    except Exception:
        return False
    if not host or host[0].isdigit() or ":" in host:
        return False
    return any(host == p or host.endswith("." + p) for p in ALLOWED_HOST_PARTS)


def is_ig_fetch_url(url: str) -> bool:
    try:
        parsed = urlparse(url)
    except Exception:
        return False
    if parsed.scheme != "https":
        return False
    host = (parsed.hostname or "").lower()
    if not host or host[0].isdigit() or ":" in host:
        return False
    return host in IG_FETCH_HOSTS


def sign_url(url: str) -> str:
    exp = int(time.time()) + TOKEN_TTL
    msg = f"{exp}.{url}".encode()
    sig = hmac.new(SECRET, msg, hashlib.sha256).hexdigest()
    blob = json.dumps({"e": exp, "u": url, "s": sig}, separators=(",", ":")).encode()
    return quote(blob.hex(), safe="")


def unsign_url(token: str) -> str:
    try:
        data = json.loads(bytes.fromhex(token))
        url = data["u"]
        exp = int(data["e"])
        sig = data["s"]
    except Exception as exc:
        raise HTTPException(400, "token") from exc
    if exp < time.time():
        raise HTTPException(410, "expired")
    msg = f"{exp}.{url}".encode()
    expect = hmac.new(SECRET, msg, hashlib.sha256).hexdigest()
    got = str(sig)
    if len(got) != len(expect) or not hmac.compare_digest(got, expect):
        raise HTTPException(400, "token")
    if not host_ok(url):
        raise HTTPException(400, "host")
    return url


def best_image(node: dict[str, Any]) -> dict[str, Any] | None:
    cands = ((node.get("image_versions2") or {}).get("candidates")) or []
    cands = [c for c in cands if c.get("url") and host_ok(c["url"])]
    if not cands and node.get("display_uri") and host_ok(node["display_uri"]):
        return {"url": node["display_uri"], "width": node.get("original_width"), "height": node.get("original_height")}
    if not cands:
        return None
    cands.sort(key=lambda c: int(c.get("width") or 0) * int(c.get("height") or 0), reverse=True)
    return cands[0]


def clean_media_url(raw: str) -> str:
    return decode_entities(raw or "").replace("\\/", "/").replace("\\u0026", "&").strip().strip("\"'")


def videos_from_dash(manifest: str) -> list[dict[str, Any]]:
    if not manifest:
        return []
    xml = decode_entities(manifest)
    out: list[dict[str, Any]] = []
    for match in re.finditer(
        r"<Representation\b([^>]*)>.*?<BaseURL>([^<]+)</BaseURL>",
        xml,
        re.I | re.S,
    ):
        attrs, base = match.group(1), match.group(2)
        mime_m = re.search(r'mimeType="([^"]+)"', attrs, re.I)
        mime = mime_m.group(1) if mime_m else ""
        if mime.lower().startswith("audio"):
            continue
        url = clean_media_url(decode_entities(base))
        if not url or not host_ok(url):
            continue
        width_m = re.search(r'width="(\d+)"', attrs)
        height_m = re.search(r'height="(\d+)"', attrs)
        out.append({
            "url": url,
            "width": int(width_m.group(1)) if width_m else 0,
            "height": int(height_m.group(1)) if height_m else 0,
        })
    return out


def best_video(node: dict[str, Any]) -> dict[str, Any] | None:
    raw_vers = node.get("video_versions")
    vers = []
    for v in (raw_vers or []):
        if not isinstance(v, dict):
            continue
        url = clean_media_url(str(v.get("url") or ""))
        if url and host_ok(url):
            item = dict(v)
            item["url"] = url
            vers.append(item)
    direct = clean_media_url(str(node.get("video_url") or node.get("playback_url") or ""))
    if direct and host_ok(direct):
        vers.append({
            "url": direct,
            "width": node.get("original_width") or ((node.get("dimensions") or {}).get("width")),
            "height": node.get("original_height") or ((node.get("dimensions") or {}).get("height")),
        })
    if not vers:
        vers = videos_from_dash(str(node.get("video_dash_manifest") or ""))
    if not vers:
        return None
    vers.sort(key=lambda v: int(v.get("width") or 0) * int(v.get("height") or 0), reverse=True)
    return vers[0]


def kind_of(item: dict[str, Any]) -> str:
    product = (item.get("product_type") or "").lower()
    media_type = item.get("media_type")
    if product == "clips" or product == "clips_draft":
        return "reel"
    if media_type == 8 or item.get("carousel_media"):
        return "carousel"
    if media_type == 2 or item.get("video_versions"):
        return "video"
    return "photo"


def collect_reel_nodes(data: dict[str, Any] | None, user_id: str = "") -> list[dict[str, Any]]:
    if not isinstance(data, dict):
        return []
    nodes: list[dict[str, Any]] = []
    items = data.get("items")
    if isinstance(items, list):
        nodes.extend(n for n in items if isinstance(n, dict))
    reel = data.get("reel")
    if isinstance(reel, dict):
        nodes.extend(n for n in (reel.get("items") or []) if isinstance(n, dict))
    reels = data.get("reels")
    if isinstance(reels, dict):
        keys = [user_id, str(user_id)]
        if user_id:
            keys.append(f"highlight:{user_id.replace('highlight:', '')}")
        found = False
        for key in keys:
            tray = reels.get(key)
            if isinstance(tray, dict):
                nodes.extend(n for n in (tray.get("items") or []) if isinstance(n, dict))
                found = True
        if not found:
            for tray in reels.values():
                if isinstance(tray, dict):
                    nodes.extend(n for n in (tray.get("items") or []) if isinstance(n, dict))
    media = data.get("reels_media")
    if isinstance(media, list):
        for tray in media:
            if isinstance(tray, dict):
                nodes.extend(n for n in (tray.get("items") or []) if isinstance(n, dict))
    return nodes


def pack_media(node: dict[str, Any], index: int) -> dict[str, Any] | None:
    video = best_video(node)
    image = best_image(node)
    if not video and not image:
        return None
    kind = "video" if video else "image"
    src = video or image or {}
    thumb = image or {}
    return {
        "id": str(node.get("pk") or node.get("id") or index),
        "index": index,
        "kind": kind,
        "width": src.get("width") or node.get("original_width"),
        "height": src.get("height") or node.get("original_height"),
        "preview": sign_url(thumb.get("url") or src.get("url")),
        "file": sign_url(src.get("url")),
        "ext": "mp4" if kind == "video" else "jpg",
    }


def pack_post(item: dict[str, Any], shortcode: str) -> dict[str, Any]:
    user = item.get("user") or {}
    caption = item.get("caption")
    text = caption.get("text") if isinstance(caption, dict) else (caption or "")
    children = item.get("carousel_media") or []
    nodes = children if children else [item]
    media = []
    for i, node in enumerate(nodes, start=1):
        packed = pack_media(node, i)
        if packed:
            media.append(packed)
    avatar = user.get("profile_pic_url") or user.get("profile_pic_url_hd")
    kind = kind_of(item)
    return {
        "ok": True,
        "shortcode": item.get("code") or shortcode,
        "kind": kind,
        "taken_at": item.get("taken_at"),
        "likes": item.get("like_count"),
        "comments": item.get("comment_count"),
        "caption": text or "",
        "owner": {
            "username": user.get("username") or "",
            "name": user.get("full_name") or "",
            "avatar": sign_url(avatar) if avatar and host_ok(avatar) else None,
        },
        "media": media,
        "source": f"https://www.instagram.com/{'reel' if kind == 'reel' else 'p'}/{item.get('code') or shortcode}/",
    }



def parse_story(raw: str) -> dict[str, str] | None:
    match = STORY_RE.search((raw or "").strip())
    if not match:
        return None
    highlight, user_with_id, media_id, user_only = match.groups()
    if highlight:
        return {"kind": "highlight", "username": "", "id": highlight}
    if user_with_id and media_id:
        return {"kind": "story", "username": user_with_id, "id": media_id}
    if user_only and user_only.lower() not in RESERVED:
        return {"kind": "user", "username": user_only, "id": ""}
    return None


def shortcode_to_media_id(code: str) -> str:
    value = 0
    for ch in code or "":
        idx = ALPHABET.find(ch)
        if idx < 0:
            return ""
        value = value * 64 + idx
    return str(value) if value > 0 else ""


def media_id_to_shortcode(media_id: str) -> str:
    digits = "".join(ch for ch in (media_id or "") if ch.isdigit())
    if not digits:
        return ""
    value = int(digits)
    if value <= 0:
        return ""
    out = []
    while value > 0:
        value, rem = divmod(value, 64)
        out.append(ALPHABET[rem])
    return "".join(reversed(out))


def tidy_ig_url(raw: str) -> str:
    text = (raw or "").strip()
    if not text:
        return ""
    found = re.search(
        r"(?:https?://)?(?:www\.)?(?:l\.)?(?:instagram\.com|instagr\.am)/[^\s<>\"'\]\)]+",
        text,
        re.I,
    )
    if not found:
        return text
    url = found.group(0).rstrip(")].,،…>\"'")
    try:
        parsed = urlparse(url)
        host = (parsed.hostname or "").lower()
        if "l.instagram.com" in host:
            from urllib.parse import parse_qs, unquote
            dest = (parse_qs(parsed.query).get("u") or [""])[0]
            if dest:
                return tidy_ig_url(unquote(dest))
        path = re.sub(r"/{2,}", "/", parsed.path or "").rstrip("/")
        low = path.lower()
        if low == "/accounts/login" or low.startswith("/accounts/login/") or low == "/login":
            from urllib.parse import parse_qs, unquote
            nxt = (parse_qs(parsed.query).get("next") or [""])[0]
            if nxt:
                decoded = unquote(nxt)
                if decoded.startswith("http"):
                    nested = decoded
                elif decoded.startswith("/"):
                    nested = f"https://www.instagram.com{decoded}"
                else:
                    nested = f"https://www.instagram.com/{decoded}"
                inner = tidy_ig_url(nested)
                if inner:
                    return inner
        if not path or path == "/":
            return ""
        return f"https://www.instagram.com{path}/"
    except Exception:
        return url.split("?", 1)[0].split("#", 1)[0]


def needs_expand(url: str) -> bool:
    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    path = (parsed.path or "").lower()
    return (
        "l.instagram.com" in host
        or "/share" in path
        or path.startswith("/s/")
        or "/accounts/login" in path
        or path == "/login"
        or path.startswith("/login/")
    )


def looks_like_post(url: str) -> bool:
    return bool(shortcode_from(url) or STORY_RE.search(url))


async def fetch_ig_page(url: str) -> tuple[str, str]:
    current = url
    html = ""
    final = url
    for _ in range(5):
        if not is_ig_fetch_url(current):
            raise HTTPException(400, "url")
        r = await ig.client.get(
            current,
            headers={"Accept": "text/html,application/xhtml+xml"},
            follow_redirects=False,
        )
        loc = r.headers.get("location") or ""
        if r.is_redirect and loc:
            nxt = urljoin(current, loc)
            tidy = tidy_ig_url(nxt) or nxt
            if is_ig_fetch_url(tidy):
                current = tidy
                continue
            if is_ig_fetch_url(nxt):
                current = nxt
                continue
            break
        html = r.text or ""
        final = tidy_ig_url(str(r.url)) or current
        break
    return final, html


async def expand_share(raw: str) -> str:
    start = tidy_ig_url(raw) or ""
    if not start or not is_ig_fetch_url(start):
        raise HTTPException(400, "url")
    if not needs_expand(start) and shortcode_from(start):
        return start
    try:
        head = await ig.client.get(start, follow_redirects=False)
        loc = head.headers.get("location") or ""
        if loc:
            nxt = tidy_ig_url(urljoin(start, loc)) or ""
            if nxt and is_ig_fetch_url(nxt) and shortcode_from(nxt):
                return nxt
        final, html = await fetch_ig_page(start)
        if shortcode_from(final):
            return final
        og = re.search(r'property="og:url" content="([^"]+)"', html)
        if og:
            from_og = tidy_ig_url(og.group(1))
            if from_og and is_ig_fetch_url(from_og) and shortcode_from(from_og):
                return from_og
        for match in re.finditer(
            r"https?://(?:www\.)?instagram\.com/(?:[A-Za-z0-9._]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,32})",
            html,
            re.I,
        ):
            if "/share/" in match.group(0).lower():
                continue
            if match.group(1).lower() in SKIP_SEGMENTS:
                continue
            return f"https://www.instagram.com/reel/{match.group(1)}/"
        return final or start
    except HTTPException:
        raise
    except Exception:
        return start


@app.post("/api/resolve")
async def resolve(payload: ResolveIn, request: Request):
    require_key(request)
    hit_rate(client_ip(request))
    try:
        url = await expand_share(payload.url)
        if parse_story(url):
            return await ig.resolve_story(url)
        shortcode = parse_shortcode(url)
        cached = ig.cached_post(shortcode)
        if cached and cached.get("media"):
            return cached
        item = await ig.web_info(shortcode)
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        raise HTTPException(502, "network") from exc
    except RuntimeError as exc:
        raise HTTPException(502, "session") from exc
    post = pack_post(item, shortcode)
    if not post["media"]:
        raise HTTPException(404, "empty")
    return post


@app.post("/api/profile")
async def profile(payload: ProfileIn, request: Request):
    require_key(request)
    hit_rate(client_ip(request))
    username = parse_username(payload.username)
    try:
        user = await ig.profile(username)
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        raise HTTPException(502, "network") from exc
    except RuntimeError as exc:
        raise HTTPException(502, "session") from exc
    packed = pack_profile(user)
    if not packed.get("username") and not packed.get("avatar"):
        raise HTTPException(404, "missing")
    packed["stories"] = []
    try:
        grid, cursor = await ig.user_posts(user)
        packed["posts"] = grid
        packed["cursor"] = cursor
    except Exception:
        packed["posts"] = []
    return packed


class ProfileMoreIn(BaseModel):
    user_id: str = Field(min_length=1, max_length=20, pattern=r"^\d+$")
    cursor: str = Field(default="", max_length=300)


@app.post("/api/profile/more")
async def profile_more(payload: ProfileMoreIn, request: Request):
    require_key(request)
    hit_rate(client_ip(request))
    try:
        grid, cursor = await ig.user_posts({"id": payload.user_id, "pk": payload.user_id}, payload.cursor or None)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, "network") from exc
    return {"ok": True, "posts": grid, "cursor": cursor}


@app.get("/api/media")
async def media(
    request: Request,
    t: str = Query(min_length=8),
    download: int = 0,
    name: str = "nada",
):
    hit_rate(client_ip(request), limit=40, window=60.0)
    url = unsign_url(t)
    headers = {
        "User-Agent": UA,
        "Referer": "https://www.instagram.com/",
        "Accept": "*/*",
    }
    try:
        upstream = await ig.client.send(
            ig.client.build_request("GET", url, headers=headers),
            stream=True,
            follow_redirects=True,
            timeout=httpx.Timeout(90.0, connect=10.0),
        )
    except httpx.HTTPError as exc:
        raise HTTPException(502, "cdn") from exc
    if upstream.status_code >= 300:
        await upstream.aclose()
        raise HTTPException(502, "cdn")
    try:
        declared = int(upstream.headers.get("content-length") or "0")
    except ValueError:
        declared = 0
    if declared > 100 * 1024 * 1024:
        await upstream.aclose()
        raise HTTPException(413, "size")

    content_type = upstream.headers.get("content-type") or "application/octet-stream"
    if "mp4" in content_type or url.endswith(".mp4"):
        ext = "mp4"
        if "octet" in content_type:
            content_type = "video/mp4"
    elif "jpeg" in content_type or "jpg" in content_type:
        ext = "jpg"
    elif "png" in content_type:
        ext = "png"
    elif "webp" in content_type:
        ext = "webp"
    else:
        ext = "bin"

    safe = re.sub(r"[^A-Za-z0-9._-]+", "_", name).strip("._") or "nada"
    out_headers = {
        "Cache-Control": "private, max-age=300",
        "Content-Type": content_type,
    }
    length = upstream.headers.get("content-length")
    if length:
        out_headers["Content-Length"] = length
    if download:
        out_headers["Content-Disposition"] = f'attachment; filename="{safe}.{ext}"'
    else:
        out_headers["Content-Disposition"] = "inline"

    async def pump():
        sent = 0
        try:
            async for chunk in upstream.aiter_bytes(64 * 1024):
                sent += len(chunk)
                if sent > 100 * 1024 * 1024:
                    break
                yield chunk
        finally:
            await upstream.aclose()

    return StreamingResponse(pump(), headers=out_headers, media_type=content_type)


@app.exception_handler(HTTPException)
async def http_errors(request: Request, exc: HTTPException):
    return JSONResponse({"ok": False, "error": exc.detail}, status_code=exc.status_code)


@app.on_event("shutdown")
async def shutdown():
    await ig.client.aclose()


app.mount("/", StaticFiles(directory=STATIC, html=True), name="static")
