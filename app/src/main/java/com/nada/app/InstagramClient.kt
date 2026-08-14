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
import java.math.BigInteger
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
    private val mediaClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val ephemeralClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    @Volatile
    private var csrf: String = ""

    fun profile(raw: String): JSONObject {
        val username = parseUsername(raw)
        val sources = listOf(
            "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username",
            "https://i.instagram.com/api/v1/users/web_profile_info/?username=$username",
        )
        var user: JSONObject? = null
        for (url in sources) {
            val data = igGet(url, "https://www.instagram.com/$username/", mobile = url.contains("i.instagram.com"))
            val found = data?.optJSONObject("data")?.optJSONObject("user")
            if (found != null && found.optString("username").isNotBlank()) {
                user = found
                break
            }
        }
        val resolved = user ?: profileUserFromHtml(username)
        enrichUser(resolved)
        val packed = packProfile(resolved)
        return attachPosts(attachStories(packed, resolved), resolved)
    }

    fun resolve(rawUrl: String): JSONObject {
        parseStory(rawUrl)?.let { return resolveStory(it) }
        val shortcode = parseShortcode(rawUrl)
        val item = webInfo(shortcode)
        val post = packPost(item, shortcode)
        if (post.getJSONArray("media").length() == 0) {
            throw ApiException(404, "empty")
        }
        return post
    }

    fun prepareSave(rawUrl: String): SaveBatch {
        val post = resolve(rawUrl)
        val media = post.optJSONArray("media") ?: JSONArray()
        val username = post.optJSONObject("owner")?.optString("username").orEmpty().ifBlank { "instagram" }
        val code = post.optString("shortcode").ifBlank { "post" }
        val files = mutableListOf<SaveFile>()
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            val token = item.optString("file")
            if (token.isBlank()) continue
            val remote = unsignUrl(token)
            val ext = item.optString("ext").ifBlank {
                if (item.optString("kind") == "video") "mp4" else "jpg"
            }
            val index = item.optInt("index", i + 1)
            val name = "${safeFilePart(username)}_${safeFilePart(code)}_$index.$ext"
            val mime = if (ext == "mp4") "video/mp4" else "image/jpeg"
            files += SaveFile(remote, name, mime)
        }
        if (files.isEmpty()) throw ApiException(404, "empty")
        return SaveBatch(username, post.optString("kind"), files)
    }

    fun openMedia(token: String): MediaStream {
        val remote = unsignUrl(token)
        val request = Request.Builder()
            .url(remote)
            .header("User-Agent", UA)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept", "*/*")
            .build()
        val response = mediaClient.newCall(request).execute()
        if (!response.isSuccessful || response.isRedirect) {
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
        var avatar = user.optString("profile_pic_url_hd").ifBlank { user.optString("profile_pic_url") }
        if (avatar.isBlank()) {
            avatar = user.optJSONObject("hd_profile_pic_url_info")?.optString("url").orEmpty()
        }
        avatar = avatar.replace("\\u0026", "&")
        val followers = user.optJSONObject("edge_followed_by")?.opt("count") ?: user.opt("follower_count")
        val following = user.optJSONObject("edge_follow")?.opt("count") ?: user.opt("following_count")
        val posts = user.optJSONObject("edge_owner_to_timeline_media")?.opt("count") ?: user.opt("media_count")
        return JSONObject()
            .put("ok", true)
            .put("username", user.optString("username"))
            .put("name", user.optString("full_name"))
            .put("bio", composeProfileBio(user).ifBlank { extractUserBio(user) })
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

    private fun igGet(
        url: String,
        referer: String,
        mobile: Boolean = false,
        sessionCookie: String? = null,
    ): JSONObject? {
        val token = runCatching { ensureCsrf() }.getOrNull() ?: return null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (mobile) IG_MOBILE_UA else UA)
            .header("x-ig-app-id", if (mobile) IG_ANDROID_APP_ID else APP_ID)
            .header("x-asbd-id", "359341")
            .header("x-csrftoken", token)
            .header("x-ig-www-claim", "0")
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", referer)
        if (!sessionCookie.isNullOrBlank()) {
            request.header("Cookie", "csrftoken=$token; $sessionCookie")
        }
        val http = if (sessionCookie.isNullOrBlank()) client else ephemeralClient
        return http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            runCatching { JSONObject(response.body?.string().orEmpty().ifBlank { "{}" }) }.getOrNull()
        }
    }

    fun fetchStoriesOnce(userId: String, sessionCookie: String): JSONArray {
        val cleanId = userId.filter { it.isDigit() }.take(20)
        if (cleanId.isBlank() || sessionCookie.isBlank()) return JSONArray()
        return try {
            fetchStories(JSONObject().put("id", cleanId).put("pk", cleanId), sessionCookie)
        } catch (_: Exception) {
            JSONArray()
        }
    }

    fun morePosts(userId: String, cursor: String): JSONObject {
        val user = JSONObject().put("id", userId).put("pk", userId)
        val pair = fetchUserPosts(user, cursor.ifBlank { null })
        return JSONObject().put("ok", true).put("posts", pair.first).put("cursor", pair.second)
    }

    private fun attachStories(profile: JSONObject, user: JSONObject): JSONObject {
        return try {
            profile.put("stories", fetchStories(user))
            profile
        } catch (_: Exception) {
            profile
        }
    }

    private fun fetchStories(user: JSONObject, sessionCookie: String? = null): JSONArray {
        val stories = JSONArray()
        val seen = mutableSetOf<String>()
        val userId = user.optString("id").ifBlank { user.optString("pk") }

        fun addNode(node: JSONObject, title: String) {
            val packed = packMedia(node, stories.length() + 1) ?: return
            val key = packed.optString("id")
            if (key.isNotBlank() && !seen.add(key)) return
            packed.put("title", title.take(18).ifBlank { "قصة" })
            stories.put(packed)
        }

        fun addCover(hid: String, title: String, cover: String) {
            if (cover.isBlank() || !hostOk(cover)) return
            val key = "cover:${hid.ifBlank { title }}"
            if (!seen.add(key)) return
            stories.put(
                JSONObject()
                    .put("id", hid.ifBlank { title })
                    .put("index", stories.length() + 1)
                    .put("kind", "image")
                    .put("preview", signUrl(cover))
                    .put("file", signUrl(cover))
                    .put("ext", "jpg")
                    .put("title", title.take(18).ifBlank { "هايلايت" }),
            )
        }

        if (userId.isNotBlank()) {
            val liveUrls = listOf(
                "https://i.instagram.com/api/v1/feed/user/$userId/reel_media/",
                "https://i.instagram.com/api/v1/feed/user/$userId/story/",
                "https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=$userId",
                "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$userId",
                "https://www.instagram.com/api/v1/feed/user/$userId/story/",
            )
            for (url in liveUrls) {
                val before = stories.length()
                collectReelNodes(
                    igGet(
                        url,
                        "https://www.instagram.com/",
                        mobile = url.contains("i.instagram.com"),
                        sessionCookie = sessionCookie,
                    ),
                    userId,
                ).forEach { addNode(it, "قصة") }
                if (stories.length() > before) break
            }

            var highlights = JSONArray()
            for (trayUrl in listOf(
                "https://i.instagram.com/api/v1/highlights/$userId/highlights_tray/",
                "https://www.instagram.com/api/v1/highlights/$userId/highlights_tray/",
            )) {
                highlights = igGet(
                    trayUrl,
                    "https://www.instagram.com/",
                    mobile = trayUrl.contains("i.instagram.com"),
                    sessionCookie = sessionCookie,
                )?.optJSONArray("tray") ?: JSONArray()
                if (highlights.length() > 0) break
            }
            for (i in 0 until minOf(highlights.length(), 12)) {
                val hl = highlights.optJSONObject(i) ?: continue
                val hid = hl.optString("id").removePrefix("highlight:")
                val title = hl.optString("title").ifBlank { "هايلايت" }
                val cover = hl.optJSONObject("cover_media")
                    ?.optJSONObject("cropped_image_version")
                    ?.optString("url")
                    .orEmpty()
                    .ifBlank { hl.optJSONObject("cover_media")?.optString("thumbnail_src").orEmpty() }
                var added = 0
                if (hid.isNotBlank()) {
                    val detail = igGet(
                        "https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:$hid",
                        "https://www.instagram.com/",
                        mobile = true,
                    ) ?: igGet(
                        "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:$hid",
                        "https://www.instagram.com/",
                    )
                    collectReelNodes(detail, "highlight:$hid").forEach {
                        val before = stories.length()
                        addNode(it, title)
                        if (stories.length() > before) added += 1
                    }
                }
                if (added == 0) addCover(hid, title, cover)
            }
        }

        if (stories.length() == 0) {
            val edges = user.optJSONObject("edge_highlight_reels")?.optJSONArray("edges") ?: JSONArray()
            for (i in 0 until minOf(edges.length(), 12)) {
                val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
                val cover = node.optJSONObject("cover_media")?.optString("thumbnail_src").orEmpty()
                addCover(node.optString("id"), node.optString("title").ifBlank { "هايلايت" }, cover)
            }
        }
        return stories
    }

    private fun collectReelNodes(data: JSONObject?, userId: String): List<JSONObject> {
        if (data == null) return emptyList()
        val nodes = mutableListOf<JSONObject>()
        val items = data.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { nodes += it }
        }
        data.optJSONObject("reel")?.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { nodes += it }
        }
        val reels = data.optJSONObject("reels")
        if (reels != null) {
            val keys = listOf(userId, "highlight:${userId.removePrefix("highlight:")}")
            var found = false
            for (key in keys) {
                val tray = reels.optJSONObject(key) ?: continue
                val arr = tray.optJSONArray("items") ?: continue
                found = true
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { nodes += it }
            }
            if (!found) {
                val names = reels.names()
                if (names != null) {
                    for (i in 0 until names.length()) {
                        val arr = reels.optJSONObject(names.optString(i))?.optJSONArray("items") ?: continue
                        for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { nodes += it }
                    }
                }
            }
        }
        val media = data.optJSONArray("reels_media")
        if (media != null) {
            for (i in 0 until media.length()) {
                val arr = media.optJSONObject(i)?.optJSONArray("items") ?: continue
                for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { nodes += it }
            }
        }
        return nodes
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
            val rawHtml = response.body?.string().orEmpty()
            val html = decodeEntities(rawHtml)
            if (isLoginWall(html) || isLoginWall(rawHtml)) throw ApiException(404, "blocked")
            val pic = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.replace("&amp;", "&").orEmpty()
            val title = Regex("""property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val desc = Regex("""property="og:description" content="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val hd = Regex(""""profile_pic_url_hd":"((?:\\.|[^"\\])*)"""").find(rawHtml)?.groupValues?.get(1)
                ?.let { unescapeJson(it) }.orEmpty()
            val userId = extractHtmlUserId(rawHtml).ifBlank { extractHtmlUserId(html) }
            if (pic.isBlank() && hd.isBlank() && title.isBlank()) throw ApiException(404, "missing")
            val name = cleanProfileName(title, username)
            val stats = parseOgStats(desc)
            val bio = extractHtmlBio(rawHtml).ifBlank { extractHtmlBio(html) }.ifBlank { cleanOgBio(desc) }
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

    private fun enrichUser(user: JSONObject) {
        val userId = user.optString("id").ifBlank { user.optString("pk") }
        if (userId.isNotBlank() && extractUserBio(user).isBlank()) {
            graphqlProfile(userId)?.let { mergeProfileUser(user, it) }
        }
        user.put("biography", composeProfileBio(user))
    }

    private fun graphqlProfile(userId: String): JSONObject? {
        val token = runCatching { ensureCsrf() }.getOrNull() ?: return null
        val variables = JSONObject()
            .put("id", userId)
            .put("render_surface", "PROFILE")
            .put("__relay_internal__pv__PolarisCannesGuardianExperienceEnabledrelayprovider", true)
            .put("__relay_internal__pv__PolarisCASB976ProfileEnabledrelayprovider", false)
            .put("__relay_internal__pv__PolarisRepostsConsumptionEnabledrelayprovider", false)
            .put("__relay_internal__pv__PolarisWebSchoolsEnabledrelayprovider", false)
            .put("enable_integrity_filters", true)
            .toString()
        val profileDocIds = listOf("27937681195819736", "26672929172408668")
        for (docId in profileDocIds) {
            val body = FormBody.Builder()
                .add("variables", variables)
                .add("doc_id", docId)
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
                .header("referer", "https://www.instagram.com/")
                .post(body)
                .build()
            val user = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                runCatching {
                    JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
                        .optJSONObject("data")
                        ?.optJSONObject("user")
                }.getOrNull()
            }
            if (user != null && (user.optString("username").isNotBlank() || user.optString("pk").isNotBlank() || user.optString("id").isNotBlank())) {
                return user
            }
        }
        return null
    }

    private fun mergeProfileUser(user: JSONObject, extra: JSONObject) {
        if (extractUserBio(user).isBlank()) {
            val bio = extractUserBio(extra)
            if (bio.isNotBlank()) user.put("biography", bio)
            extra.optJSONObject("biography_with_entities")?.let { user.put("biography_with_entities", it) }
        }
        if (user.optString("full_name").isBlank() && extra.optString("full_name").isNotBlank()) {
            user.put("full_name", extra.optString("full_name"))
        }
        if (user.optString("username").isBlank() && extra.optString("username").isNotBlank()) {
            user.put("username", extra.optString("username"))
        }
        val hd = extra.optJSONObject("hd_profile_pic_url_info")?.optString("url").orEmpty()
        if (user.optString("profile_pic_url_hd").isBlank() && hd.isNotBlank()) {
            user.put("profile_pic_url_hd", hd)
        }
        if (user.optString("profile_pic_url").isBlank() && extra.optString("profile_pic_url").isNotBlank()) {
            user.put("profile_pic_url", extra.optString("profile_pic_url"))
        }
        if (!user.has("follower_count") || user.isNull("follower_count")) {
            extra.opt("follower_count")?.let { user.put("follower_count", it) }
        }
        if (!user.has("following_count") || user.isNull("following_count")) {
            extra.opt("following_count")?.let { user.put("following_count", it) }
        }
        if (!user.has("media_count") || user.isNull("media_count")) {
            extra.opt("media_count")?.let { user.put("media_count", it) }
        }
        extra.optJSONArray("bio_links")?.let { user.put("bio_links", it) }
        if (user.optString("external_url").isBlank() && extra.optString("external_url").isNotBlank()) {
            user.put("external_url", extra.optString("external_url"))
        }
        if (user.optString("id").isBlank() && extra.optString("id").isNotBlank()) {
            user.put("id", extra.optString("id"))
        }
        if (user.optString("pk").isBlank() && extra.optString("pk").isNotBlank()) {
            user.put("pk", extra.optString("pk"))
        }
    }

    private fun extractUserBio(user: JSONObject): String {
        val directVal = user.opt("biography")
        val direct = when (directVal) {
            is String -> unescapeJson(directVal)
            is JSONObject -> unescapeJson(
                directVal.optString("text").ifBlank {
                    directVal.optString("sensitive_string_value").ifBlank { directVal.optString("raw_text") }
                }
            )
            else -> ""
        }
        if (direct.isNotBlank() && !isGenericBio(direct)) return direct
        val nested = unescapeJson(user.optJSONObject("biography_with_entities")?.optString("raw_text").orEmpty())
        if (nested.isNotBlank() && !isGenericBio(nested)) return nested
        return ""
    }

    private fun collectBioLinks(user: JSONObject): List<String> {
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val ext = user.optString("external_url").trim()
        if (ext.isNotBlank()) {
            seen += ext
            out += ext
        }
        val links = user.optJSONArray("bio_links") ?: JSONArray()
        for (i in 0 until links.length()) {
            val link = links.optJSONObject(i) ?: continue
            val url = link.optString("url").trim()
            val title = link.optString("title").trim()
            if (url.isBlank() || url in seen) continue
            seen += url
            out += if (title.isNotBlank() && !url.contains(title, ignoreCase = true)) "$title\n$url" else url
        }
        return out
    }

    private fun composeProfileBio(user: JSONObject): String {
        val bio = extractUserBio(user)
        val extras = collectBioLinks(user).filter { it.isNotBlank() && !bio.contains(it) }
        if (extras.isEmpty()) return bio
        return if (bio.isBlank()) extras.joinToString("\n") else bio + "\n" + extras.joinToString("\n")
    }

    private fun isLoginWall(html: String): Boolean {
        val low = html.lowercase()
        return low.contains("create an account or log in to instagram")
            || (low.contains("/accounts/login") && !low.contains("profile_pic_url_hd"))
            || (low.contains("og:title\" content=\"instagram\"") && !low.contains("\"biography\""))
    }

    private fun extractHtmlBio(html: String): String {
        val patterns = listOf(
            Regex(""""biography":"((?:\\.|[^"\\])*)""""),
            Regex(""""raw_text":"((?:\\.|[^"\\])*)""""),
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(html)) {
                val bio = unescapeJson(match.groupValues[1])
                if (bio.isNotBlank() && !isGenericBio(bio)) return bio
            }
        }
        val meta = Regex("""name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        val fromMeta = cleanOgBio(meta)
        if (fromMeta.isNotBlank()) return fromMeta
        val og = Regex("""property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
        return cleanOgBio(og)
    }

    private fun extractHtmlUserId(html: String): String {
        val patterns = listOf(
            Regex(""""profile_id":"(\d+)""""),
            Regex(""""logging_page_id":"profilePage_(\d+)""""),
            Regex("""profilePage_(\d+)"""),
            Regex(""""user_id"\s*:\s*"(\d+)""""),
            Regex(""""pk":"(\d{5,})""""),
            Regex(""""id":"(\d{5,})","username""""),
            Regex(""""username":"[^"]{1,30}","id":"(\d{5,})""""),
            Regex("""instagram://user\?username=[^&"]+&user_id=(\d+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(html) ?: continue
            if (match.groupValues[1].isNotBlank()) return match.groupValues[1]
        }
        return ""
    }

    private fun isGenericBio(text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return true
        val low = value.lowercase(Locale.US)
        return low.startsWith("see instagram photos and videos")
            || low.startsWith("create an account or log in")
            || low.contains("create an account or log in to instagram")
    }

    private fun cleanOgBio(desc: String): String {
        val text = decodeEntities(desc).trim()
        if (text.isBlank()) return ""
        val quoted = Regex("""on Instagram:\s*["“](.*)["”]\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }.orEmpty()
        if (quoted.isNotBlank()) return if (isGenericBio(quoted)) "" else quoted
        val tail = Regex("""Posts\s*[-–—]\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (tail.isNotBlank()) {
            val inner = Regex("""on Instagram:\s*["“](.*)["”]\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(tail)?.groupValues?.get(1)?.let { decodeEntities(it).trim() }.orEmpty()
            if (inner.isNotBlank()) return if (isGenericBio(inner)) "" else inner
            if (isGenericBio(tail) || Regex(""".+\(@[\w.]+\)\s*$""").matches(tail)) return ""
            return tail
        }
        if (Regex("""\d[\d.,]*\s*[KMB]?\s*Followers""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return ""
        return if (isGenericBio(text)) "" else text
    }

    private fun unescapeJson(value: String): String {
        var out = value
            .replace("\\n", "\n")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")
        out = Regex("""\\u([0-9a-fA-F]{4})""").replace(out) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: mr.value
        }
        return decodeEntities(out).trim()
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

    private fun parseStory(raw: String): Triple<String, String, String>? {
        val match = STORY_RE.find(raw.trim()) ?: return null
        val highlight = match.groupValues[1]
        val userWithId = match.groupValues[2]
        val mediaId = match.groupValues[3]
        val userOnly = match.groupValues[4]
        return when {
            highlight.isNotBlank() -> Triple("highlight", "", highlight)
            userWithId.isNotBlank() && mediaId.isNotBlank() -> Triple("story", userWithId, mediaId)
            userOnly.isNotBlank() && userOnly.lowercase(Locale.US) !in RESERVED -> Triple("user", userOnly, "")
            else -> null
        }
    }

    private fun mediaIdToShortcode(mediaId: String): String {
        var value = mediaId.filter { it.isDigit() }.toBigIntegerOrNull() ?: return ""
        if (value <= BigInteger.ZERO) return ""
        val sixtyFour = BigInteger.valueOf(64)
        val out = StringBuilder()
        while (value > BigInteger.ZERO) {
            val parts = value.divideAndRemainder(sixtyFour)
            out.append(ALPHABET[parts[1].toInt()])
            value = parts[0]
        }
        return out.reverse().toString()
    }

    private fun mediaInfo(mediaId: String): JSONObject? {
        val mid = mediaId.filter { it.isDigit() }
        if (mid.isBlank()) return null
        for (url in listOf(
            "https://www.instagram.com/api/v1/media/$mid/info/",
            "https://i.instagram.com/api/v1/media/$mid/info/",
        )) {
            val items = igGet(url, "https://www.instagram.com/", mobile = url.contains("i.instagram.com"))
                ?.optJSONArray("items")
            val first = items?.optJSONObject(0)
            if (first != null) return first
        }
        val code = mediaIdToShortcode(mid)
        if (code.isBlank()) return null
        return runCatching { webInfo(code) }.getOrNull()
    }

    private fun resolveStory(parsed: Triple<String, String, String>): JSONObject {
        val (kind, username, id) = parsed
        if (kind == "highlight") {
            val detail = igGet(
                "https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:$id",
                "https://www.instagram.com/",
                mobile = true,
            ) ?: igGet(
                "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=highlight:$id",
                "https://www.instagram.com/",
            )
            val nodes = collectReelNodes(detail, "highlight:$id")
            if (nodes.isEmpty()) throw ApiException(404, "missing")
            val first = nodes.first()
            if (nodes.size == 1) {
                val post = packPost(first, id)
                post.put("source", "https://www.instagram.com/stories/highlights/$id/")
                if (post.getJSONArray("media").length() == 0) throw ApiException(404, "empty")
                return post
            }
            val carousel = JSONObject(first.toString()).put("carousel_media", JSONArray().apply { nodes.forEach { put(it) } }).put("media_type", 8)
            val post = packPost(carousel, id)
            post.put("source", "https://www.instagram.com/stories/highlights/$id/")
            if (post.getJSONArray("media").length() == 0) throw ApiException(404, "empty")
            return post
        }
        val item = if (id.isNotBlank()) mediaInfo(id) else null
        if (item != null) {
            val code = item.optString("code").ifBlank { mediaIdToShortcode(id) }.ifBlank { id }
            val post = packPost(item, code)
            val owner = item.optJSONObject("user")?.optString("username").orEmpty()
            post.put("source", "https://www.instagram.com/stories/${username.ifBlank { owner }}/$id/")
            if (post.getJSONArray("media").length() == 0) throw ApiException(404, "empty")
            return post
        }
        if (username.isBlank()) throw ApiException(404, "missing")
        val packed = profile(username)
        val stories = packed.optJSONArray("stories") ?: JSONArray()
        if (stories.length() == 0) throw ApiException(404, "missing")
        return JSONObject()
            .put("ok", true)
            .put("shortcode", id.ifBlank { username })
            .put("kind", if (stories.length() > 1) "carousel" else stories.optJSONObject(0)?.optString("kind") ?: "photo")
            .put("taken_at", JSONObject.NULL)
            .put("likes", JSONObject.NULL)
            .put("comments", JSONObject.NULL)
            .put("caption", "")
            .put("owner", JSONObject()
                .put("username", packed.optString("username"))
                .put("name", packed.optString("name"))
                .put("avatar", packed.opt("avatar") ?: JSONObject.NULL))
            .put("media", stories)
            .put("source", "https://www.instagram.com/stories/$username/")
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

    private fun safeFilePart(raw: String): String {
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(36).ifBlank { "nada" }
    }

    class SaveBatch(val username: String, val kind: String, val files: List<SaveFile>)

    class SaveFile(val url: String, val name: String, val mime: String)

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
        private const val IG_ANDROID_APP_ID = "567067343352427"
        private const val IG_MOBILE_UA =
            "Instagram 192.168.2.5.74 Android (31/12; 420dpi; 1080x2130; Xiaomi; M2101K6G; alioth; qcom; en_US; 376229147)"
        private const val TOKEN_TTL = 45 * 60L
        private val URL_RE = Regex(
            "(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/(?:[^/?#]+/)?(?:p|tv|reel|reels)/([A-Za-z0-9_-]+)",
            RegexOption.IGNORE_CASE,
        )
        private val STORY_RE = Regex(
            "(?:https?://)?(?:www\\.)?(?:instagram\\.com|instagr\\.am)/stories/(?:highlights/(\\d+)|([A-Za-z0-9._]+)/(\\d+)|([A-Za-z0-9._]+)/?)",
            RegexOption.IGNORE_CASE,
        )
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
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
