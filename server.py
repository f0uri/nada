"""Nada — a calm Instagram media saver."""

from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import re
import time
from collections import defaultdict
from pathlib import Path
from typing import Any
from urllib.parse import quote, urlparse

import httpx
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parent
STATIC = ROOT / "static"
SECRET = b"nada-dew-glass-2026"
TOKEN_TTL = 45 * 60
DOC_ID = "27128499623469141"
APP_ID = "936619743392459"

UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
)

URL_RE = re.compile(
    r"(?:https?://)?(?:www\.)?(?:instagram\.com|instagr\.am)"
    r"/(?:[^/?#]+/)?(?:p|tv|reel|reels)/([A-Za-z0-9_-]+)",
    re.I,
)
SHORT_RE = re.compile(r"^[A-Za-z0-9_-]{5,20}$")

ALLOWED_HOST_PARTS = (
    "cdninstagram.com",
    "fbcdn.net",
    "instagram.com",
    "fbsbx.com",
    "cdninstagram.net",
)

_rate: dict[str, list[float]] = defaultdict(list)


class ResolveIn(BaseModel):
    url: str = Field(min_length=4, max_length=500)


class ProfileIn(BaseModel):
    username: str = Field(min_length=1, max_length=80)


USER_RE = re.compile(r"^[A-Za-z0-9._]{1,30}$")
USER_URL_RE = re.compile(
    r"(?:https?://)?(?:www\.)?(?:instagram\.com|instagr\.am)/([A-Za-z0-9._]+)/?",
    re.I,
)
RESERVED = {
    "p", "reel", "reels", "tv", "stories", "explore", "accounts", "about",
    "legal", "developer", "directory", "privacy", "terms", "lite",
}


class InstagramSession:
    def __init__(self) -> None:
        self._lock = asyncio.Lock()
        self._csrf = ""
        self._csrf_at = 0.0
        self.client = httpx.AsyncClient(
            timeout=httpx.Timeout(25.0, connect=10.0),
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
            self._csrf = csrf
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
        if not items:
            raise HTTPException(404, "missing")
        return items[0]

    async def profile(self, username: str) -> dict[str, Any]:
        csrf = await self.ensure()
        headers = {
            "x-ig-app-id": APP_ID,
            "x-asbd-id": "359341",
            "x-csrftoken": csrf,
            "x-ig-www-claim": "0",
            "x-requested-with": "XMLHttpRequest",
            "referer": f"https://www.instagram.com/{username}/",
        }
        r = await self.client.get(
            f"https://www.instagram.com/api/v1/users/web_profile_info/?username={quote(username)}",
            headers=headers,
        )
        if r.status_code == 429:
            raise HTTPException(429, "rate")
        if r.status_code == 200:
            try:
                user = ((r.json().get("data") or {}).get("user")) or {}
            except Exception:
                user = {}
            if user.get("username"):
                return user
        page = await self.client.get(
            f"https://www.instagram.com/{username}/",
            headers={"referer": "https://www.instagram.com/"},
        )
        if page.status_code == 429:
            raise HTTPException(429, "rate")
        if page.status_code >= 400:
            raise HTTPException(404, "missing")
        html = page.text
        pic = re.search(r'property="og:image" content="([^"]+)"', html)
        title = re.search(r'property="og:title" content="([^"]+)"', html)
        desc = re.search(r'property="og:description" content="([^"]+)"', html)
        bio = re.search(r'"biography":"((?:\\.|[^"\\])*)"', html)
        hd = re.search(r'"profile_pic_url_hd":"([^"]+)"', html)
        if not pic and not hd and not title:
            raise HTTPException(404, "missing")
        name = ""
        if title:
            name = title.group(1).split("(@")[0].split("•")[0].strip()
        return {
            "username": username,
            "full_name": name,
            "biography": (bio.group(1).encode("utf-8").decode("unicode_escape") if bio else "")
            or (desc.group(1) if desc else ""),
            "profile_pic_url_hd": (hd.group(1).encode("utf-8").decode("unicode_escape") if hd else "")
            or (pic.group(1) if pic else ""),
        }

    async def _ig_get(self, url: str, referer: str) -> dict[str, Any] | None:
        csrf = await self.ensure()
        r = await self.client.get(
            url,
            headers={
                "x-ig-app-id": APP_ID,
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
        user_id = str(user.get("id") or user.get("pk") or "")
        items: list[dict[str, Any]] = []
        if user_id:
            live = await self._ig_get(
                f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids={quote(user_id)}",
                "https://www.instagram.com/",
            )
            reels = (live or {}).get("reels") or {}
            tray = reels.get(user_id) or reels.get(str(user_id)) or {}
            for node in tray.get("items") or []:
                packed = pack_media(node, len(items) + 1)
                if packed:
                    packed["title"] = "قصة"
                    items.append(packed)
            tray_data = await self._ig_get(
                f"https://www.instagram.com/api/v1/highlights/{quote(user_id)}/highlights_tray/",
                "https://www.instagram.com/",
            )
            highlights = (tray_data or {}).get("tray") or []
            for hl in highlights[:12]:
                hid = str(hl.get("id") or "").replace("highlight:", "")
                cover = (
                    ((hl.get("cover_media") or {}).get("cropped_image_version") or {}).get("url")
                    or ((hl.get("cover_media") or {}).get("thumbnail_src"))
                )
                title = ((hl.get("title") or "هايلايت")[:18])
                if hid:
                    detail = await self._ig_get(
                        f"https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:{quote(hid)}",
                        "https://www.instagram.com/",
                    )
                    reel = ((detail or {}).get("reels") or {}).get(f"highlight:{hid}") or {}
                    for node in reel.get("items") or []:
                        packed = pack_media(node, len(items) + 1)
                        if packed:
                            packed["title"] = title
                            items.append(packed)
                elif cover and host_ok(cover):
                    items.append({
                        "id": hid or title,
                        "index": len(items) + 1,
                        "kind": "image",
                        "preview": sign_url(cover),
                        "file": sign_url(cover),
                        "ext": "jpg",
                        "title": title,
                    })
        edges = ((user.get("edge_highlight_reels") or {}).get("edges")) or []
        if not items:
            for edge in edges[:12]:
                node = (edge or {}).get("node") or {}
                cover = ((node.get("cover_media") or {}).get("thumbnail_src")) or ""
                title = (node.get("title") or "هايلايت")[:18]
                if cover and host_ok(cover):
                    items.append({
                        "id": str(node.get("id") or title),
                        "index": len(items) + 1,
                        "kind": "image",
                        "preview": sign_url(cover),
                        "file": sign_url(cover),
                        "ext": "jpg",
                        "title": title,
                    })
        return items[:24]


ig = InstagramSession()
app = FastAPI(title="Nada", docs_url=None, redoc_url=None)


def client_ip(request: Request) -> str:
    return (request.client.host if request.client else "0") or "0"


def hit_rate(ip: str, limit: int = 24, window: float = 60.0) -> None:
    now = time.time()
    bucket = [t for t in _rate[ip] if now - t < window]
    if len(bucket) >= limit:
        raise HTTPException(429, "rate")
    bucket.append(now)
    _rate[ip] = bucket


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
        "bio": user.get("biography") or "",
        "private": bool(user.get("is_private")),
        "followers": followers,
        "following": following,
        "posts": posts,
        "avatar": sign_url(avatar) if avatar and host_ok(avatar) else None,
        "stories": [],
    }


def parse_shortcode(raw: str) -> str:
    text = (raw or "").strip()
    if SHORT_RE.fullmatch(text):
        return text
    m = URL_RE.search(text)
    if m:
        return m.group(1)
    raise HTTPException(400, "url")


def host_ok(url: str) -> bool:
    try:
        host = (urlparse(url).hostname or "").lower()
    except Exception:
        return False
    return any(host == p or host.endswith("." + p) for p in ALLOWED_HOST_PARTS)


def sign_url(url: str) -> str:
    exp = int(time.time()) + TOKEN_TTL
    msg = f"{exp}.{url}".encode()
    sig = hmac.new(SECRET, msg, hashlib.sha256).hexdigest()[:28]
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
    expect = hmac.new(SECRET, msg, hashlib.sha256).hexdigest()[:28]
    if not hmac.compare_digest(expect, sig):
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


def best_video(node: dict[str, Any]) -> dict[str, Any] | None:
    vers = [v for v in (node.get("video_versions") or []) if v.get("url") and host_ok(v["url"])]
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


@app.post("/api/resolve")
async def resolve(payload: ResolveIn, request: Request):
    hit_rate(client_ip(request))
    shortcode = parse_shortcode(payload.url)
    try:
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
    return packed


@app.get("/api/media")
async def media(
    t: str = Query(min_length=8),
    download: int = 0,
    name: str = "nada",
):
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
        )
    except httpx.HTTPError as exc:
        raise HTTPException(502, "cdn") from exc
    if upstream.status_code >= 400:
        await upstream.aclose()
        raise HTTPException(502, "cdn")

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
        try:
            async for chunk in upstream.aiter_bytes(64 * 1024):
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
