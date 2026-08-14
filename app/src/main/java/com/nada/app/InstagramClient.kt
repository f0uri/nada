package com.nada.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
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

class InstagramClient {
    private val secret = "nada-dew-glass-2026".toByteArray(StandardCharsets.UTF_8)
    private val cookieStore = mutableMapOf<String, Cookie>()
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { cookieStore[it.name] = it }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore.values.toList()
        })
        .build()

    @Volatile
    private var csrf: String = ""

    fun profile(raw: String): JSONObject {
        val username = parseUsername(raw)
        val token = ensureCsrf()
        val request = Request.Builder()
            .url("https://www.instagram.com/api/v1/users/web_profile_info/?username=$username")
            .header("User-Agent", UA)
            .header("x-ig-app-id", APP_ID)
            .header("x-asbd-id", "359341")
            .header("x-csrftoken", token)
            .header("x-ig-www-claim", "0")
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", "https://www.instagram.com/$username/")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw ApiException(429, "rate")
            if (response.isSuccessful) {
                val user = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                    .optJSONObject("data")
                    ?.optJSONObject("user")
                if (user != null && user.optString("username").isNotBlank()) {
                    return attachPosts(attachStories(packProfile(user), user), user)
                }
            }
        }
        return profileFromHtml(username)
    }

    fun resolve(rawUrl: String): JSONObject {
        val shortcode = parseShortcode(rawUrl)
        val item = webInfo(shortcode)
        val post = packPost(item, shortcode)
        if (post.getJSONArray("media").length() == 0) {
            throw ApiException(404, "empty")
        }
        return post
    }

    fun openMedia(token: String): MediaStream {
        val remote = unsignUrl(token)
        val request = Request.Builder()
            .url(remote)
            .header("User-Agent", UA)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw ApiException(502, "cdn")
        }
        val contentType = response.header("content-type") ?: "application/octet-stream"
        return MediaStream(response, contentType, remote)
    }

    private fun ensureCsrf(): String {
        if (csrf.isNotBlank()) return csrf
        val request = Request.Builder()
            .url("https://www.instagram.com/")
            .header("User-Agent", UA)
            .header("Accept-Language", "ar,en-US;q=0.8,en;q=0.6")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(502, "session")
            val fromCookie = cookieStore["csrftoken"]?.value.orEmpty()
            val body = response.body?.string().orEmpty()
            csrf = fromCookie.ifBlank {
                Regex("\"csrf_token\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            }
        }
        if (csrf.isBlank()) throw ApiException(502, "session")
        return csrf
    }

    private fun webInfo(shortcode: String): JSONObject {
        val token = ensureCsrf()
        val variables = JSONObject()
            .put("shortcode", shortcode)
            .put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
            .toString()
        val body = FormBody.Builder()
            .add("variables", variables)
            .add("doc_id", DOC_ID)
            .build()
        val request = Request.Builder()
            .url("https://www.instagram.com/graphql/query")
            .header("User-Agent", UA)
            .header("x-ig-app-id", APP_ID)
            .header("x-asbd-id", "359341")
            .header("x-csrftoken", token)
            .header("x-ig-www-claim", "0")
            .header("x-requested-with", "XMLHttpRequest")
            .header("origin", "https://www.instagram.com")
            .header("referer", "https://www.instagram.com/p/$shortcode/")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw ApiException(429, "rate")
            if (!response.isSuccessful) throw ApiException(502, "upstream")
            val payload = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            val items = payload.optJSONObject("data")
                ?.optJSONObject("xdt_api__v1__media__shortcode__web_info")
                ?.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) throw ApiException(404, "missing")
            return items.getJSONObject(0)
        }
    }

    private fun packPost(item: JSONObject, shortcode: String): JSONObject {
        val user = item.optJSONObject("user") ?: JSONObject()
        val captionObj = item.opt("caption")
        val caption = when (captionObj) {
            is JSONObject -> captionObj.optString("text")
            is String -> captionObj
            else -> ""
        }
        val children = item.optJSONArray("carousel_media")
        val nodes = if (children != null && children.length() > 0) children else JSONArray().put(item)
        val media = JSONArray()
        for (i in 0 until nodes.length()) {
            packMedia(nodes.getJSONObject(i), i + 1)?.let { media.put(it) }
        }
        val avatar = user.optString("profile_pic_url").ifBlank { user.optString("profile_pic_url_hd") }
        val kind = kindOf(item)
        val code = item.optString("code").ifBlank { shortcode }
        val owner = JSONObject()
            .put("username", user.optString("username"))
            .put("name", user.optString("full_name"))
            .put("avatar", if (avatar.isNotBlank() && hostOk(avatar)) signUrl(avatar) else JSONObject.NULL)
        return JSONObject()
            .put("ok", true)
            .put("shortcode", code)
            .put("kind", kind)
            .put("taken_at", item.opt("taken_at"))
            .put("likes", item.opt("like_count"))
            .put("comments", item.opt("comment_count"))
            .put("caption", caption)
            .put("owner", owner)
            .put("media", media)
            .put("source", "https://www.instagram.com/${if (kind == "reel") "reel" else "p"}/$code/")
    }

    private fun packMedia(node: JSONObject, index: Int): JSONObject? {
        val video = bestVideo(node)
        val image = bestImage(node)
        if (video == null && image == null) return null
        val kind = if (video != null) "video" else "image"
        val src = video ?: image!!
        val thumb = image ?: src
        return JSONObject()
            .put("id", node.optString("pk").ifBlank { node.optString("id").ifBlank { index.toString() } })
            .put("index", index)
            .put("kind", kind)
            .put("width", src.opt("width") ?: node.opt("original_width"))
            .put("height", src.opt("height") ?: node.opt("original_height"))
            .put("preview", signUrl(thumb.optString("url")))
            .put("file", signUrl(src.optString("url")))
            .put("ext", if (kind == "video") "mp4" else "jpg")
    }

    private fun bestImage(node: JSONObject): JSONObject? {
        val cands = node.optJSONObject("image_versions2")?.optJSONArray("candidates") ?: JSONArray()
        val usable = mutableListOf<JSONObject>()
        for (i in 0 until cands.length()) {
            val c = cands.getJSONObject(i)
            if (c.optString("url").isNotBlank() && hostOk(c.optString("url"))) usable += c
        }
        val display = node.optString("display_uri")
        if (usable.isEmpty() && display.isNotBlank() && hostOk(display)) {
            return JSONObject()
                .put("url", display)
                .put("width", node.opt("original_width"))
                .put("height", node.opt("original_height"))
        }
        return usable.maxByOrNull { it.optInt("width") * it.optInt("height") }
    }

    private fun bestVideo(node: JSONObject): JSONObject? {
        val vers = node.optJSONArray("video_versions") ?: JSONArray()
        val usable = mutableListOf<JSONObject>()
        for (i in 0 until vers.length()) {
            val v = vers.getJSONObject(i)
            if (v.optString("url").isNotBlank() && hostOk(v.optString("url"))) usable += v
        }
        return usable.maxByOrNull { it.optInt("width") * it.optInt("height") }
    }

    private fun kindOf(item: JSONObject): String {
        val product = item.optString("product_type").lowercase(Locale.US)
        val mediaType = item.optInt("media_type", -1)
        return when {
            product == "clips" || product == "clips_draft" -> "reel"
            mediaType == 8 || (item.optJSONArray("carousel_media")?.length() ?: 0) > 0 -> "carousel"
            mediaType == 2 || (item.optJSONArray("video_versions")?.length() ?: 0) > 0 -> "video"
            else -> "photo"
        }
    }

    private fun parseUsername(raw: String): String {
        var text = raw.trim().trimStart('@')
        val fromUrl = USER_PAGE_RE.find(text)
        if (fromUrl != null) text = fromUrl.groupValues[1]
        text = text.trim().trim('/')
        if (text.lowercase(Locale.US) in RESERVED || !USER_RE.matches(text)) {
            throw ApiException(400, "user")
        }
        return text
    }

    private fun packProfile(user: JSONObject): JSONObject {
        val avatar = user.optString("profile_pic_url_hd").ifBlank { user.optString("profile_pic_url") }
            .replace("\\u0026", "&")
        val followers = user.optJSONObject("edge_followed_by")?.opt("count") ?: user.opt("follower_count")
        val following = user.optJSONObject("edge_follow")?.opt("count") ?: user.opt("following_count")
        val posts = user.optJSONObject("edge_owner_to_timeline_media")?.opt("count") ?: user.opt("media_count")
        return JSONObject()
            .put("ok", true)
            .put("username", user.optString("username"))
            .put("name", user.optString("full_name"))
            .put("bio", user.optString("biography"))
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

    private fun igGet(url: String, referer: String): JSONObject? {
        val token = runCatching { ensureCsrf() }.getOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("x-ig-app-id", APP_ID)
            .header("x-asbd-id", "359341")
            .header("x-csrftoken", token)
            .header("x-ig-www-claim", "0")
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", referer)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            runCatching { JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }) }.getOrNull()
        }
    }

    private fun attachStories(profile: JSONObject, user: JSONObject): JSONObject {
        val stories = JSONArray()
        val userId = user.optString("id").ifBlank { user.optString("pk") }
        if (userId.isNotBlank()) {
            val live = igGet(
                "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$userId",
                "https://www.instagram.com/",
            )
            val liveItems = live?.optJSONObject("reels")?.optJSONObject(userId)?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until liveItems.length()) {
                packMedia(liveItems.getJSONObject(i), stories.length() + 1)?.let {
                    it.put("title", "قصة")
                    stories.put(it)
                }
            }
            val highlights = igGet(
                "https://www.instagram.com/api/v1/highlights/$userId/highlights_tray/",
                "https://www.instagram.com/",
            )?.optJSONArray("tray") ?: JSONArray()
            for (i in 0 until minOf(highlights.length(), 12)) {
                val hl = highlights.optJSONObject(i) ?: continue
                val hid = hl.optString("id").removePrefix("highlight:")
                val title = hl.optString("title").ifBlank { "هايلايت" }.take(18)
                if (hid.isBlank()) continue
                val detail = igGet(
                    "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:$hid",
                    "https://www.instagram.com/",
                )
                val items = detail?.optJSONObject("reels")
                    ?.optJSONObject("highlight:$hid")
                    ?.optJSONArray("items") ?: JSONArray()
                for (j in 0 until items.length()) {
                    packMedia(items.getJSONObject(j), stories.length() + 1)?.let {
                        it.put("title", title)
                        stories.put(it)
                    }
                }
            }
        }
        if (stories.length() == 0) {
            val edges = user.optJSONObject("edge_highlight_reels")?.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until minOf(edges.length(), 12)) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                val cover = node.optJSONObject("cover_media")?.optString("thumbnail_src").orEmpty()
                val title = node.optString("title").ifBlank { "هايلايت" }.take(18)
                if (cover.isNotBlank() && hostOk(cover)) {
                    stories.put(
                        JSONObject()
                            .put("id", node.optString("id").ifBlank { title })
                            .put("kind", "image")
                            .put("preview", signUrl(cover))
                            .put("file", signUrl(cover))
                            .put("ext", "jpg")
                            .put("title", title),
                    )
                }
            }
        }
        profile.put("stories", stories)
        return profile
    }

    fun morePosts(userId: String, cursor: String): JSONObject {
        val user = JSONObject().put("id", userId).put("pk", userId)
        val pair = fetchUserPosts(user, cursor.ifBlank { null })
        return JSONObject().put("ok", true).put("posts", pair.first).put("cursor", pair.second)
    }

    private fun attachPosts(profile: JSONObject, user: JSONObject): JSONObject {
        return try {
            val pair = fetchUserPosts(user, null)
            profile.put("posts", pair.first)
            profile.put("cursor", pair.second)
            profile
        } catch (_: Exception) {
            profile
        }
    }

    private fun fetchUserPosts(user: JSONObject, cursor: String?): Pair<JSONArray, String> {
        val posts = JSONArray()
        var next = ""
        val userId = user.optString("id").ifBlank { user.optString("pk") }
        if (userId.isNotBlank()) {
            val q = buildString {
                append("count=18")
                if (!cursor.isNullOrBlank()) append("&max_id=").append(cursor)
            }
            val data = igGet(
                "https://www.instagram.com/api/v1/feed/user/$userId/?$q",
                "https://www.instagram.com/",
            )
            val items = data?.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                packGridItem(items.getJSONObject(i))?.let { posts.put(it) }
            }
            if (data?.optBoolean("more_available") == true) {
                next = data.optString("next_max_id")
            }
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

    private fun packGridItem(node: JSONObject): JSONObject? {
        val shortcode = node.optString("code").ifBlank { node.optString("shortcode") }
        if (shortcode.isBlank()) return null
        val mediaType = node.optInt("media_type", -1)
        val product = node.optString("product_type").lowercase(Locale.US)
        val isVideo = node.optBoolean("is_video") || mediaType == 2 || (node.optJSONArray("video_versions")?.length() ?: 0) > 0
        val kind = when {
            product == "clips" -> "reel"
            mediaType == 8 || node.optString("__typename") == "GraphSidecar" || node.optJSONArray("carousel_media") != null -> "carousel"
            isVideo && product in setOf("clips", "igtv") -> "reel"
            isVideo -> "video"
            else -> "photo"
        }
        var preview = bestImage(node)?.optString("url").orEmpty()
        if (preview.isBlank()) {
            preview = node.optString("display_url").ifBlank {
                node.optString("thumbnail_src").ifBlank { node.optString("display_uri") }
            }
        }
        if (preview.isBlank()) {
            preview = node.optJSONObject("image_versions2")
                ?.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optString("url")
                .orEmpty()
        }
        return JSONObject()
            .put("shortcode", shortcode)
            .put("kind", kind)
            .put("preview", if (preview.isNotBlank() && hostOk(preview)) signUrl(preview) else JSONObject.NULL)
    }

    private fun profileFromHtml(username: String): JSONObject {
        val request = Request.Builder()
            .url("https://www.instagram.com/$username/")
            .header("User-Agent", UA)
            .header("x-ig-app-id", APP_ID)
            .header("referer", "https://www.instagram.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 429) throw ApiException(429, "rate")
            if (!response.isSuccessful) throw ApiException(404, "missing")
            val html = decodeEntities(response.body?.string().orEmpty())
            val pic = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.replace("&amp;", "&").orEmpty()
            val title = Regex("""property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val desc = Regex("""property="og:description" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val hd = Regex(""""profile_pic_url_hd":"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.replace("\\u0026", "&")?.replace("\\/", "/").orEmpty()
            val bioRaw = Regex(""""biography":"((?:\\.|[^"\\])*)"""").find(html)?.groupValues?.get(1)
                ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\/", "/").orEmpty()
            val userId = Regex(""""profile_id":"(\d+)"""").find(html)?.groupValues?.get(1)
                ?: Regex(""""id":"(\d{5,})"""").find(html)?.groupValues?.get(1).orEmpty()
            if (pic.isBlank() && hd.isBlank() && title.isBlank()) throw ApiException(404, "missing")
            val name = cleanProfileName(title, username)
            val stats = parseOgStats(desc)
            val bio = if (bioRaw.isNotBlank()) bioRaw else ""
            val user = JSONObject()
                .put("username", username)
                .put("full_name", name)
                .put("biography", bio)
                .put("profile_pic_url_hd", hd.ifBlank { pic })
                .put("follower_count", stats[0])
                .put("following_count", stats[1])
                .put("media_count", stats[2])
                .put("id", userId)
                .put("pk", userId)
            return attachPosts(attachStories(packProfile(user), user), user)
        }
    }

    private fun cleanProfileName(title: String, username: String): String {
        var name = decodeEntities(title)
        name = name.replace(Regex("""\s*[•·].*$"""), "")
        name = name.replace(Regex("""\s*\(@?[^)]*\)\s*"""), "")
        name = name.replace("Instagram photos and videos", "", ignoreCase = true).trim()
        return name.ifBlank { username }
    }

    private fun parseOgStats(desc: String): List<Any?> {
        val text = decodeEntities(desc)
        fun grab(label: String): Any? {
            val m = Regex("""([\d.,]+\s*[KMB]?)\s*$label""", RegexOption.IGNORE_CASE).find(text)
                ?: return null
            return parseCompactNumber(m.groupValues[1])
        }
        return listOf(grab("Followers"), grab("Following"), grab("Posts"))
    }

    private fun parseCompactNumber(raw: String): Long? {
        val clean = raw.replace(",", "").replace(" ", "").uppercase(Locale.US)
        val m = Regex("""^([\d.]+)([KMB])?$""").find(clean) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        val mul = when (m.groupValues[2]) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            "B" -> 1_000_000_000.0
            else -> 1.0
        }
        return (n * mul).toLong()
    }

    private fun decodeEntities(value: String): String {
        var out = value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#x2022;", "•")
            .replace("&#8226;", "•")
            .replace("&bull;", "•")
        out = Regex("""&#(\d+);""").replace(out) { mr ->
            mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: mr.value
        }
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: mr.value
        }
        return out
    }

    private fun parseShortcode(raw: String): String {
        val text = raw.trim()
        if (SHORT_RE.matches(text)) return text
        val match = URL_RE.find(text) ?: throw ApiException(400, "url")
        return match.groupValues[1]
    }

    private fun hostOk(url: String): Boolean {
        return try {
            val host = url.toHttpUrlOrNull()?.host?.lowercase(Locale.US) ?: return false
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
        val expect = hmac("$exp.$url")
        if (!MessageDigest.isEqual(expect.toByteArray(), sig.toByteArray())) throw ApiException(400, "token")
        if (!hostOk(url)) throw ApiException(400, "host")
        return url
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)).toHex().take(28)
    }

    class MediaStream(
        val response: okhttp3.Response,
        val contentType: String,
        val remoteUrl: String,
    ) {
        val body get() = response.body
        fun close() = response.close()
    }

    class ApiException(val status: Int, val code: String) : Exception(code)

    companion object {
        private const val UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1"
        private const val DOC_ID = "27128499623469141"
        private const val APP_ID = "936619743392459"
        private const val TOKEN_TTL = 45 * 60L
        private val URL_RE = Regex(
            "(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/(?:[^/?#]+/)?(?:p|tv|reel|reels)/([A-Za-z0-9_-]+)",
            RegexOption.IGNORE_CASE,
        )
        private val SHORT_RE = Regex("^[A-Za-z0-9_-]{5,20}$")
        private val USER_RE = Regex("^[A-Za-z0-9._]{1,30}$")
        private val USER_PAGE_RE = Regex(
            "(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/([A-Za-z0-9._]+)/?",
            RegexOption.IGNORE_CASE,
        )
        private val RESERVED = setOf(
            "p", "reel", "reels", "tv", "stories", "explore", "accounts",
            "about", "legal", "privacy", "terms", "lite",
        )
        private val ALLOWED = listOf(
            "cdninstagram.com",
            "fbcdn.net",
            "instagram.com",
            "fbsbx.com",
            "cdninstagram.net",
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexToBytes(): ByteArray {
    val clean = URLDecoder.decode(this, "UTF-8")
    require(clean.length % 2 == 0)
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
