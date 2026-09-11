package com.nada.app

import android.content.Context
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class NadaServer(
    private val context: Context,
    private val instagram: InstagramClient,
    port: Int = PORT,
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                uri == "/api/resolve" && session.method == Method.POST -> handleResolve(session)
                uri == "/api/profile" && session.method == Method.POST -> handleProfile(session)
                uri == "/api/profile/more" && session.method == Method.POST -> handleProfileMore(session)
                uri == "/api/media" && session.method == Method.GET -> handleMedia(session)
                else -> handleStatic(uri)
            }
        } catch (ex: InstagramClient.ApiException) {
            jsonError(ex.status, ex.code)
        } catch (ex: Exception) {
            jsonError(502, "network")
        }
    }

    private fun withSecurity(response: Response): Response {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("X-Frame-Options", "DENY")
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self'; " +
                "font-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; " +
                "base-uri 'self'; form-action 'self'; frame-ancestors 'none'",
        )
        return response
    }

    private fun handleProfileMore(session: IHTTPSession): Response {
        val raw = readBody(session)
        val json = runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse { JSONObject() }
        val result = instagram.morePosts(json.optString("user_id"), json.optString("cursor"))
        return withSecurity(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result.toString()))
    }

    private fun handleProfile(session: IHTTPSession): Response {
        val raw = readBody(session)
        val username = try {
            JSONObject(raw.ifBlank { "{}" }).optString("username")
        } catch (_: Exception) {
            ""
        }
        val result = instagram.profile(username)
        return withSecurity(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result.toString()))
    }

    private fun handleResolve(session: IHTTPSession): Response {
        val raw = readBody(session)
        val url = try {
            JSONObject(raw.ifBlank { "{}" }).optString("url")
        } catch (_: Exception) {
            ""
        }
        val result = try {
            instagram.resolve(url)
        } catch (ex: InstagramClient.ApiException) {
            if (ex.code !in setOf("missing", "empty")) throw ex
            recoverFromPage(url) ?: throw ex
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result.toString())
    }

    private fun recoverFromPage(url: String): JSONObject? {
        val page = runCatching { instagram.pageUrl(url) }.getOrDefault(url)
        val code = IgLinks.shortcodeFrom(page)
        val pages = buildList {
            if (!code.isNullOrBlank()) {
                add("https://www.instagram.com/reel/$code/embed/")
                add("https://www.instagram.com/p/$code/embed/")
                add("https://www.instagram.com/reel/$code/")
            }
            add(page)
            add(url)
        }
        val hit = PageMediaSniffer(context).sniff(pages) ?: return null
        return runCatching { instagram.postFromCdn(hit.url, hit.mime, page) }.getOrNull()
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val posted = map["postData"].orEmpty()
        if (posted.isNotBlank()) return posted
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        if (length > 32_768) throw InstagramClient.ApiException(400, "url")
        val bytes = ByteArray(length)
        var offset = 0
        val input = session.inputStream
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun handleMedia(session: IHTTPSession): Response {
        val token = session.parms["t"].orEmpty()
        val download = session.parms["download"] == "1"
        val name = (session.parms["name"] ?: "nada").replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('.', '_')
            .ifBlank { "nada" }
        val stream = instagram.openMedia(token)
        val body = stream.body ?: run {
            stream.close()
            throw InstagramClient.ApiException(502, "cdn")
        }
        var contentType = stream.contentType
        val ext = when {
            contentType.contains("mp4") || stream.remoteUrl.endsWith(".mp4") -> {
                if (contentType.contains("octet")) contentType = "video/mp4"
                "mp4"
            }
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            contentType.contains("png") -> "png"
            contentType.contains("webp") -> "webp"
            else -> "bin"
        }
        val response = withSecurity(newChunkedResponse(Response.Status.OK, contentType, body.byteStream()))
        response.addHeader("Cache-Control", "private, max-age=300")
        if (download) {
            response.addHeader("Content-Disposition", "attachment; filename=\"$name.$ext\"")
        } else {
            response.addHeader("Content-Disposition", "inline")
        }
        return response
    }

    private fun handleStatic(uri: String): Response {
        val path = when {
            uri == "/" || uri.isBlank() -> "index.html"
            uri.startsWith("/") -> uri.removePrefix("/")
            else -> uri
        }
        return try {
            val input = context.assets.open(path)
            val mime = guessMime(path)
            newChunkedResponse(Response.Status.OK, mime, input)
        } catch (_: Exception) {
            if (!path.contains('.')) {
                val index = context.assets.open("index.html")
                newChunkedResponse(Response.Status.OK, "text/html; charset=utf-8", index)
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        }
    }

    private fun guessMime(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "woff2" -> "font/woff2"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun jsonError(status: Int, code: String): Response {
        val http = Response.Status.lookup(status) ?: Response.Status.INTERNAL_ERROR
        val payload = JSONObject().put("ok", false).put("error", code).toString()
        return withSecurity(newFixedLengthResponse(http, "application/json; charset=utf-8", payload))
    }

    companion object {
        const val PORT = 8765
        const val BASE = "http://127.0.0.1:$PORT/"
    }
}
