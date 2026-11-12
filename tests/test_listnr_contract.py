"""Offline contract tests for the listnr-only resolver (no live Instagram calls)."""
import json
import re
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

import server

ROOT = Path(__file__).resolve().parents[1]
VIDEO = "https://scontent-ord5-2.cdninstagram.com/o1/v/t2/f2/m86/example.mp4?_nc_cat=106&vs=keep-me"
THUMB = "https://scontent-ord5-1.cdninstagram.com/example.jpg?_nc_cat=101"


@pytest.fixture
def client(monkeypatch):
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        assert str(request.url) == server.LISTNR_ENDPOINT
        assert request.headers["accept"] == "application/json"
        assert request.headers["content-type"] == "application/json"
        assert request.headers["origin"] == "https://listnr.ai"
        assert request.headers["referer"] == "https://listnr.ai/"
        assert "Chrome/139.0.0.0" in request.headers["user-agent"]
        assert json.loads(request.content) == {
            "url": "https://www.instagram.com/reel/DeuaqiVAi_6/",
            "platform": "instagram",
            "type": "video",
        }
        return httpx.Response(200, json={"url": VIDEO, "data": {"thumb": THUMB}})

    monkeypatch.setattr(server.ig, "client", httpx.AsyncClient(transport=httpx.MockTransport(handler)))
    with TestClient(server.app) as c:
        yield c, calls


def test_resolve_maps_one_original_video_and_preserves_query(client):
    c, calls = client
    r = c.post("/api/resolve", json={"url": "https://www.instagram.com/reel/DeuaqiVAi_6/"})
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["shortcode"] == "DeuaqiVAi_6"
    assert len(body["media"]) == 1
    media = body["media"][0]
    assert media["kind"] == "video"
    assert media["quality"] == "original"
    assert media["downloadUrl"] == VIDEO
    assert media["ext"] == "mp4"
    assert server.unsign_url(media["file"]) == VIDEO
    assert server.unsign_url(media["preview"]) == THUMB
    assert len(calls) == 1


@pytest.mark.parametrize("payload", [{}, {"url": ""}, {"url": None}, {"url": []}, {"data": {"thumb": THUMB}}])
def test_missing_url_is_existing_error_not_crash(monkeypatch, payload):
    monkeypatch.setattr(
        server.ig, "client",
        httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, json=payload))),
    )
    with TestClient(server.app) as c:
        r = c.post("/api/resolve", json={"url": "https://www.instagram.com/reel/DeuaqiVAi_6/"})
    assert r.status_code == 404
    assert r.json() == {"ok": False, "error": "empty"}


def test_non_200_is_existing_error(monkeypatch):
    monkeypatch.setattr(
        server.ig, "client",
        httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(503, text="down"))),
    )
    with TestClient(server.app) as c:
        r = c.post("/api/resolve", json={"url": "https://www.instagram.com/reel/DeuaqiVAi_6/"})
    assert r.status_code == 502
    assert r.json()["error"] == "upstream"


def test_malformed_json_is_existing_error(monkeypatch):
    monkeypatch.setattr(
        server.ig, "client",
        httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, text="not json"))),
    )
    with TestClient(server.app) as c:
        r = c.post("/api/resolve", json={"url": "https://www.instagram.com/reel/DeuaqiVAi_6/"})
    assert r.status_code == 502
    assert r.json()["error"] == "upstream"


def test_timeout_is_existing_error(monkeypatch):
    def timeout(request):
        raise httpx.ReadTimeout("timeout", request=request)

    monkeypatch.setattr(server.ig, "client", httpx.AsyncClient(transport=httpx.MockTransport(timeout)))
    with TestClient(server.app) as c:
        r = c.post("/api/resolve", json={"url": "https://www.instagram.com/reel/DeuaqiVAi_6/"})
    assert r.status_code == 502
    assert r.json()["error"] == "network"


def test_no_old_resolve_references_remain():
    # Split literals so this guard itself does not reintroduce the forbidden strings.
    forbidden = (
        "graphql/" + "query", "api/" + "graphql", "/" + "embed/", "/api/v1/" + "media/",
        "27128499623469" + "141", "27130156389949" + "648", "10015901848480" + "474", "og:" + "video",
    )
    hits = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or ".git" in path.parts or "__pycache__" in path.parts or path.name == "nada.jks":
            continue
        try:
            text = path.read_text(errors="ignore")
        except OSError:
            continue
        for token in forbidden:
            if token in text:
                hits.append(f"{path.relative_to(ROOT)}: {token}")
    assert hits == []


def test_version_manifest_matches_app_and_stable_asset():
    manifest = json.loads((ROOT / "version.json").read_text())
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    # Derive from Gradle so a release bump does not need a test edit.
    name = re.search(r'versionName = "([^"]+)"', gradle).group(1)
    code = int(re.search(r"versionCode = (\d+)", gradle).group(1))
    assert manifest["version"] == name
    assert manifest["versionCode"] == code
    assert f'versionName = "{name}"' in gradle
    assert f"versionCode = {code}" in gradle
    assert manifest["apkUrl"] == "https://github.com/f0uri/nada/releases/latest/download/nada-debug.apk"
    assert manifest["changelog"]
