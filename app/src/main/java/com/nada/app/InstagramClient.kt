package com.nada.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Public-reel/post media is resolved through ListnrService first; when
 * listnr cannot find the video, quicksave.my is the fallback — the same
 * fallback Reel Checker already uses (1.71). Profile metadata is a separate
 * feature and still uses web_profile_info, with public profile HTML only as
 * a metadata fallback. No media URL is ever extracted from profile/feed/
 * story responses.
 */
class InstagramClient {
    private val secret = "nada-dew-glass-2026".toByteArray(StandardCharsets.UTF_8)
    private val cookieStore = mutableMapOf<String, Cookie>()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { cookieStore["${it.domain}|${it.name}"] = it }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore.values.filter { it.matches(url) }
        })
        .build()
    private val redirectClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    private val downloadClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
    private val listnr = ListnrService()
    private val quickSave = QuickSaveFallback()

    @Volatile private var csrf: String = ""

    fun resolve(rawUrl: String, diag: DownloadDiagnostics? = null): JSONObject {
        val url = expandIfNeeded(rawUrl)
        val shortcode = parseShortcode(url)
        val video = fetchMediaItem(shortcode, diag)
        if (!hostOk(video.url)) {
            diag?.note("resolved url host not allowed")
            diag?.cdnDetail = "host"
            throw ApiException(400, "host")
        }
        val thumb = video.thumb.takeIf { it.isNotBlank() && hostOk(it) }
        val media = JSONObject()
            .put("id", "1")
            .put("index", 1)
            .put("kind", "video")
            .put("quality", "original")
            .put("downloadUrl", video.url)
            .put("preview", thumb?.let { signUrl(it) } ?: JSONObject.NULL)
            .put("file", signUrl(video.url))
            .put("ext", "mp4")
        val kind = if (url.contains("/reel", ignoreCase = true)) "reel" else "video"
        return JSONObject()
            .put("ok", true)
            .put("shortcode", shortcode)
            .put("kind", kind)
            .put("taken_at", JSONObject.NULL)
            .put("likes", JSONObject.NULL)
            .put("comments", JSONObject.NULL)
            .put("caption", "")
            .put("owner", JSONObject()
                .put("username", "instagram")
                .put("name", "")
                .put("avatar", JSONObject.NULL))
            .put("media", JSONArray().put(media))
            .put("source", url)
    }

    /**
     * Listnr first; quicksave only when listnr cannot find the video.
     * A fallback failure never hides the listnr cause: the listnr exception
     * is rethrown and both attempts are recorded in the diagnostic.
     */
    private fun fetchMediaItem(shortcode: String, diag: DownloadDiagnostics?): ListnrService.Video {
        val postUrl = "https://www.instagram.com/reel/$shortcode/"
        try {
            return listnr.resolve(postUrl, diag)
        } catch (listnrEx: ApiException) {
            diag?.note("listnr failed (${listnrEx.code}), trying quicksave")
            try {
                val video = quickSave.resolve(postUrl, diag)
                diag?.viaQuickSave = true
                return video
            } catch (_: ApiException) {
                diag?.note("quicksave failed too, keeping listnr cause")
                throw listnrEx
            }
        }
    }

    fun prepareSave(rawUrl: String, diag: DownloadDiagnostics? = null): SaveBatch {
        val post = try {
            resolve(rawUrl, diag)
        } catch (videoError: ApiException) {
            try {
                return prepareImageSave(rawUrl, diag)
            } catch (_: Exception) {
                throw videoError
            }
        }
        val media = post.optJSONArray("media") ?: JSONArray()
        val code = post.optString("shortcode").ifBlank { "post" }
        val files = mutableListOf<SaveFile>()
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            val token = item.optString("file")
            if (token.isBlank()) continue
            val remote = unsignUrl(token)
            val ext = item.optString("ext").ifBlank { "mp4" }
            val mime = item.optString("mime").ifBlank { "video/mp4" }
            files += SaveFile(remote, "instagram_${safeFilePart(code)}_${i + 1}.$ext", mime)
        }
        if (files.isEmpty()) return prepareImageSave(rawUrl, diag)
        val kind = if (files.any { it.mime.startsWith("image/") }) "photo" else post.optString("kind")
        return SaveBatch("instagram", kind, files)
    }

    private fun prepareImageSave(rawUrl: String, diag: DownloadDiagnostics?): SaveBatch {
        val shortcode = parseShortcode(rawUrl)
        val image = quickSave.resolveImage("https://www.instagram.com/p/$shortcode/", diag)
        if (!hostOk(image.url)) throw ApiException(400, "host")
        diag?.viaQuickSave = true
        return SaveBatch(
            "instagram",
            "photo",
            listOf(SaveFile(image.url, "instagram_${safeFilePart(shortcode)}_1.${image.extension}", image.mime)),
        )
    }

    fun openMedia(token: String): MediaStream = pullFile(unsignUrl(token))

    /**
     * Downloads the CDN URL exactly as returned, including its signed query.
     * A transport failure or a 5xx is retried once with a fresh request, and
     * every outcome names its real cause (host/status/classified io error)
     * instead of dying as a generic network failure.
     */
    fun pullFile(remoteUrl: String, diag: DownloadDiagnostics? = null): MediaStream {
        if (!hostOk(remoteUrl)) {
            diag?.cdnDetail = "host"
            diag?.note("cdn host not allowed")
            throw ApiException(400, "host")
        }
        val host = runCatching { remoteUrl.toHttpUrlOrNull()?.host }.getOrNull().orEmpty().ifBlank { "?" }
        if (diag != null) diag.cdnHost = host
        val request = Request.Builder()
            .url(remoteUrl)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .build()
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                downloadClient.newCall(request).execute()
            } catch (ex: java.io.IOException) {
                val kind = DownloadDiagnostics.classify(ex)
                diag?.note("cdn $kind (attempt $attempt): $host")
                if (attempt < MAX_CDN_ATTEMPTS) continue
                diag?.cdnDetail = "$kind ×$attempt"
                throw ApiException(502, "cdn")
            }
            if (response.isSuccessful) {
                diag?.note("cdn ok HTTP ${response.code} (attempt $attempt)")
                if (diag != null) diag.cdnDetail = "HTTP ${response.code}"
                return MediaStream(
                    response,
                    response.header("content-type") ?: "application/octet-stream",
                    remoteUrl,
                )
            }
            val code = response.code
            response.close()
            diag?.note("cdn HTTP $code (attempt $attempt): $host")
            if (diag != null) diag.cdnDetail = "HTTP $code"
            // Only a transient 5xx is worth one retry; 4xx will not change.
            if (attempt < MAX_CDN_ATTEMPTS && code >= 500) continue
            throw ApiException(if (code == 429) 429 else 502, if (code == 429) "rate" else "cdn")
        }
    }

    fun warmSession() {
        runCatching { ensureCsrf() }
    }

    fun pageUrl(raw: String): String = expandIfNeeded(raw)

    /** Share links are expanded using redirects only; no page media is scraped. */
    private fun expandIfNeeded(raw: String): String {
        var current = IgLinks.fromText(raw).ifBlank { raw.trim() }
        if (!IgLinks.isFetchableIg(current)) throw ApiException(400, "url")
        repeat(5) {
            IgLinks.shortcodeFrom(current)?.let { return current }
            if (!IgLinks.needsExpand(current)) return current
            val next = redirectClient.newCall(
                Request.Builder().url(current).header("User-Agent", UA).get().build(),
            ).execute().use { response ->
                if (!response.isRedirect) return@use ""
                val location = response.header("Location").orEmpty()
                current.toHttpUrlOrNull()?.resolve(location)?.toString().orEmpty()
            }
            if (next.isBlank()) return current
            current = IgLinks.fromText(next).ifBlank { next }
            if (!IgLinks.isFetchableIg(current)) throw ApiException(400, "url")
        }
        return current
    }

    private fun parseShortcode(raw: String): String {
        val text = raw.trim()
        if (SHORT_RE.matches(text)) return text
        IgLinks.shortcodeFrom(text)?.let { return it }
        throw ApiException(400, "url")
    }

    // Separate profile feature: metadata only, never a media-resolution source.
    fun profile(raw: String): JSONObject {
        val username = parseUsername(raw)
        val sources = listOf(
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username",
            "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username",
        )
        var user: JSONObject? = null
        for (url in sources) {
            val found = igGet(url, "https://www.instagram.com/$username/", mobile = url.contains("i.instagram.com"))
                ?.optJSONObject("data")?.optJSONObject("user")
            if (found != null && found.optString("username").isNotBlank()) {
                user = found
                break
            }
        }
        val resolved = user ?: profileUserFromHtml(username)
        if (user != null && extractUserBio(resolved).isBlank()) {
            runCatching { profileUserFromHtml(username) }.getOrNull()?.let { mergeProfileUser(resolved, it) }
        }
        resolved.put("biography", composeProfileBio(resolved))
        return attachPosts(packProfile(resolved), resolved)
    }

    fun morePosts(userId: String, cursor: String): JSONObject {
        val id = userId.filter { it.isDigit() }.take(20)
        if (id.isBlank()) throw ApiException(400, "user")
        val pair = fetchUserPosts(JSONObject().put("id", id).put("pk", id), cursor.take(300).ifBlank { null })
        return JSONObject().put("ok", true).put("posts", pair.first).put("cursor", pair.second)
    }

    /**
     * The Listnr contract covers reels/posts, not Instagram stories. Keep the
     * existing UI contract but never resolve story media from another server.
     */
    fun fetchStoriesOnce(userId: String, sessionCookie: String): JSONArray = JSONArray()

    private fun ensureCsrf(): String {
        if (csrf.isNotBlank()) {
            rememberCsrfCookie(csrf)
            return csrf
        }
        val request = Request.Builder()
            .url("https://www.instagram.com/")
            .header("User-Agent", UA)
            .header("Accept-Language", "ar,en-US;q=0.8,en;q=0.6")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(502, "session")
            csrf = cookieStore.values.firstOrNull { it.name == "csrftoken" }?.value.orEmpty()
            if (csrf.isBlank()) {
                val body = response.body?.string().orEmpty()
                csrf = Regex("\"csrf_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            }
        }
        if (csrf.isBlank()) throw ApiException(502, "session")
        rememberCsrfCookie(csrf)
        return csrf
    }

    private fun rememberCsrfCookie(token: String) {
        if (token.isBlank()) return
        cookieStore["instagram.com|csrftoken"] = Cookie.Builder()
            .name("csrftoken").value(token).domain("instagram.com").path("/").build()
    }

    private fun igGet(url: String, referer: String, mobile: Boolean = false): JSONObject? {
        val token = runCatching { ensureCsrf() }.getOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (mobile) IG_MOBILE_UA else UA)
            .header("x-ig-app-id", if (mobile) IG_ANDROID_APP_ID else APP_ID)
            .header("x-csrftoken", token)
            .header("x-ig-www-claim", "0")
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", referer)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
        }
    }

    private fun parseUsername(raw: String): String {
        var text = raw.trim().trimStart('@')
        USER_PAGE_RE.find(text)?.let { text = it.groupValues[1] }
        text = text.trim().trim('/')
        if (text.lowercase(Locale.US) in RESERVED || !USER_RE.matches(text)) throw ApiException(400, "user")
        return text
    }

    private fun packProfile(user: JSONObject): JSONObject {
        var avatar = user.optString("profile_pic_url_hd").ifBlank { user.optString("profile_pic_url") }
        if (avatar.isBlank()) avatar = user.optJSONObject("hd_profile_pic_url_info")?.optString("url").orEmpty()
        avatar = avatar.replace("\\u0026", "&")
        val followers = user.optJSONObject("edge_followed_by")?.opt("count") ?: user.opt("follower_count")
        val following = user.optJSONObject("edge_follow")?.opt("count") ?: user.opt("following_count")
        val posts = user.optJSONObject("edge_owner_to_timeline_media")?.opt("count") ?: user.opt("media_count")
        return JSONObject()
            .put("ok", true)
            .put("username", user.optString("username"))
            .put("name", user.optString("full_name"))
            .put("bio", composeProfileBio(user))
            .put("private", user.optBoolean("is_private"))
            .put("followers", followers)
            .put("following", following)
            .put("post_count", posts)
            .put("avatar", if (avatar.isNotBlank() && hostOk(avatar)) signUrl(avatar) else JSONObject.NULL)
            .put("stories", JSONArray())
            .put("posts", JSONArray())
            .put("user_id", user.optString("id").ifBlank { user.optString("pk") })
            .put("cursor", "")
    }

    private fun attachPosts(profile: JSONObject, user: JSONObject): JSONObject {
        return try {
            val pair = fetchUserPosts(user, null)
            profile.put("posts", pair.first).put("cursor", pair.second)
        } catch (_: Exception) {
            profile
        }
    }

    private fun fetchUserPosts(user: JSONObject, cursor: String?): Pair<JSONArray, String> {
        val posts = JSONArray()
        var next = ""
        val userId = user.optString("id").ifBlank { user.optString("pk") }.filter { it.isDigit() }.take(20)
        if (userId.isNotBlank()) {
            val q = buildString {
                append("count=18")
                if (!cursor.isNullOrBlank()) append("&max_id=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
            val data = igGet("https://www.instagram.com/api/v1/feed/user/$userId/?$q", "https://www.instagram.com/")
            val items = data?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) packGridItem(items.optJSONObject(i) ?: continue)?.let { posts.put(it) }
            if (data?.optBoolean("more_available") == true) next = data.optString("next_max_id")
        }
        if (posts.length() == 0) {
            val edges = user.optJSONObject("edge_owner_to_timeline_media")?.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until edges.length()) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: edges.optJSONObject(i) ?: continue
                packGridItem(node)?.let { posts.put(it) }
            }
            val page = user.optJSONObject("edge_owner_to_timeline_media")?.optJSONObject("page_info")
            if (page?.optBoolean("has_next_page") == true) next = page.optString("end_cursor")
        }
        return posts to next
    }

    /** Grid thumbnails are metadata; opening any item still calls Listnr. */
    private fun packGridItem(node: JSONObject): JSONObject? {
        val shortcode = node.optString("code").ifBlank { node.optString("shortcode") }
        if (shortcode.isBlank()) return null
        val mediaType = node.optInt("media_type", -1)
        val product = node.optString("product_type").lowercase(Locale.US)
        val isVideo = node.optBoolean("is_video") || mediaType == 2
        val kind = when {
            product == "clips" -> "reel"
            mediaType == 8 || node.optJSONArray("carousel_media") != null -> "carousel"
            isVideo -> "video"
            else -> "photo"
        }
        val candidates = node.optJSONObject("image_versions2")?.optJSONArray("candidates")
        var preview = ""
        if (candidates != null) {
            for (i in 0 until candidates.length()) {
                val url = candidates.optJSONObject(i)?.optString("url").orEmpty()
                if (url.isNotBlank() && hostOk(url)) { preview = url; break }
            }
        }
        if (preview.isBlank()) preview = node.optString("display_url").ifBlank { node.optString("thumbnail_src") }
        return JSONObject()
            .put("shortcode", shortcode)
            .put("kind", kind)
            .put("preview", if (preview.isNotBlank() && hostOk(preview)) signUrl(preview) else JSONObject.NULL)
    }

    private fun profileUserFromHtml(username: String): JSONObject {
        val request = Request.Builder()
            .url("https://www.instagram.com/$username/")
            .header("User-Agent", UA)
            .header("x-ig-app-id", APP_ID)
            .header("referer", "https://www.instagram.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw ApiException(429, "rate")
            if (!response.isSuccessful) throw ApiException(404, "missing")
            val raw = response.body?.string().orEmpty()
            val html = decodeEntities(raw)
            val pic = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val title = Regex("""property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val desc = Regex("""property="og:description" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val hd = Regex(""""profile_pic_url_hd":"((?:\\.|[^"\\])*)"""").find(raw)?.groupValues?.get(1)
                ?.let { unescapeJson(it) }.orEmpty()
            val userId = Regex(""""profile_id":"(\d+)"""").find(raw)?.groupValues?.get(1).orEmpty()
            if (pic.isBlank() && hd.isBlank() && title.isBlank()) throw ApiException(404, "missing")
            val name = decodeEntities(title).replace(Regex("""\s*[•·].*$"""), "")
                .replace(Regex("""\s*\(@?[^)]*\)\s*"""), "")
                .replace("Instagram photos and videos", "", ignoreCase = true).trim().ifBlank { username }
            val stats = listOf("Followers", "Following", "Posts").map { label ->
                Regex("""([\d.,]+\s*[KMB]?)\s*$label""", RegexOption.IGNORE_CASE)
                    .find(decodeEntities(desc))?.groupValues?.get(1)?.let { parseCompactNumber(it) }
            }
            val bio = Regex(""""biography":"((?:\\.|[^"\\])*)"""").find(raw)?.groupValues?.get(1)
                ?.let { unescapeJson(it) }.orEmpty()
            return JSONObject()
                .put("username", username)
                .put("full_name", name)
                .put("biography", bio)
                .put("profile_pic_url_hd", hd.ifBlank { pic })
                .put("follower_count", stats[0] ?: JSONObject.NULL)
                .put("following_count", stats[1] ?: JSONObject.NULL)
                .put("media_count", stats[2] ?: JSONObject.NULL)
                .put("id", userId)
                .put("pk", userId)
        }
    }

    private fun mergeProfileUser(user: JSONObject, extra: JSONObject) {
        if (extractUserBio(user).isBlank()) {
            val bio = extractUserBio(extra)
            if (bio.isNotBlank()) user.put("biography", bio)
        }
        for (key in listOf("full_name", "username", "profile_pic_url_hd", "profile_pic_url", "id", "pk")) {
            if (user.optString(key).isBlank() && extra.optString(key).isNotBlank()) user.put(key, extra.optString(key))
        }
        extra.optJSONArray("bio_links")?.let { user.put("bio_links", it) }
    }

    private fun extractUserBio(user: JSONObject): String {
        val value = user.opt("biography")
        return when (value) {
            is String -> unescapeJson(value)
            is JSONObject -> unescapeJson(value.optString("text").ifBlank { value.optString("raw_text") })
            else -> ""
        }
    }

    private fun composeProfileBio(user: JSONObject): String {
        val bio = extractUserBio(user)
        val links = mutableListOf<String>()
        user.optString("external_url").trim().takeIf { it.isNotBlank() }?.let { links += it }
        val nodes = user.optJSONArray("bio_links") ?: JSONArray()
        for (i in 0 until nodes.length()) {
            val url = nodes.optJSONObject(i)?.optString("url").orEmpty().trim()
            if (url.isNotBlank() && url !in links) links += url
        }
        val extras = links.filter { !bio.contains(it) }
        return if (extras.isEmpty()) bio else if (bio.isBlank()) extras.joinToString("\n") else bio + "\n" + extras.joinToString("\n")
    }

    private fun parseCompactNumber(raw: String): Long? {
        val clean = raw.replace(",", "").replace(" ", "").uppercase(Locale.US)
        val match = Regex("""^([\d.]+)([KMB])?$""").find(clean) ?: return null
        val n = match.groupValues[1].toDoubleOrNull() ?: return null
        val mul = when (match.groupValues[2]) { "K" -> 1e3; "M" -> 1e6; "B" -> 1e9; else -> 1.0 }
        return (n * mul).toLong()
    }

    private fun unescapeJson(value: String): String {
        val out = value.replace("\\n", "\n").replace("\\/", "/").replace("\\\"", "\"").replace("\\u0026", "&")
        return decodeEntities(Regex("""\\u([0-9a-fA-F]{4})""").replace(out) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value
        }).trim()
    }

    private fun decodeEntities(value: String): String = value
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun hostOk(url: String): Boolean {
        return try {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            val host = parsed.host.lowercase(Locale.US)
            if (host.isEmpty() || host[0].isDigit() || ":" in host) return false
            ALLOWED.any { host == it || host.endsWith(".$it") }
        } catch (_: Exception) {
            false
        }
    }

    private fun signUrl(url: String): String {
        val exp = System.currentTimeMillis() / 1000 + TOKEN_TTL
        val sig = hmac("$exp.$url")
        val blob = JSONObject().put("e", exp).put("u", url).put("s", sig).toString()
            .toByteArray(StandardCharsets.UTF_8)
        return URLEncoder.encode(blob.toHex(), "UTF-8")
    }

    private fun unsignUrl(token: String): String {
        val data = try {
            JSONObject(String(token.hexToBytes(), StandardCharsets.UTF_8))
        } catch (_: Exception) {
            throw ApiException(400, "token")
        }
        val url = data.optString("u")
        val exp = data.optLong("e")
        val sig = data.optString("s")
        if (exp < System.currentTimeMillis() / 1000) throw ApiException(410, "expired")
        if (!MessageDigest.isEqual(hmac("$exp.$url").toByteArray(), sig.toByteArray())) throw ApiException(400, "token")
        if (!hostOk(url)) throw ApiException(400, "host")
        return url
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun safeFilePart(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(36).ifBlank { "nada" }

    class SaveBatch(val username: String, val kind: String, val files: List<SaveFile>)
    class SaveFile(val url: String, val name: String, val mime: String)
    class MediaStream(val response: okhttp3.Response, val contentType: String, val remoteUrl: String) {
        val body get() = response.body
        fun close() = response.close()
    }
    class ApiException(val status: Int, val code: String) : Exception(code)

    companion object {
        private const val UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        private const val APP_ID = "936619743392459"
        private const val IG_ANDROID_APP_ID = "567067343352427"
        private const val IG_MOBILE_UA =
            "Instagram 192.168.2.5.74 Android (31/12; 420dpi; 1080x2130; Xiaomi; M2101K6G; alioth; qcom; en_US; 376229147)"
        private const val TOKEN_TTL = 45 * 60L
        private val SHORT_RE = Regex("^[A-Za-z0-9_-]{5,32}$")
        private val USER_RE = Regex("^[A-Za-z0-9._]{1,30}$")
        private val USER_PAGE_RE = Regex(
            "(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/([A-Za-z0-9._]+)/?",
            RegexOption.IGNORE_CASE,
        )
        private val RESERVED = setOf("p", "reel", "reels", "tv", "stories", "explore", "accounts", "about", "legal", "privacy", "terms", "lite", "share", "s")
        private const val MAX_CDN_ATTEMPTS = 2
        private val ALLOWED = listOf("cdninstagram.com", "fbcdn.net", "cdninstagram.net", "fbsbx.com")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray {
    val clean = URLDecoder.decode(this, "UTF-8")
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
