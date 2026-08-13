package com.nada.app

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
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

    private fun parseShortcode(raw: String): String {
        val text = raw.trim()
        if (SHORT_RE.matches(text)) return text
        val match = URL_RE.find(text) ?: throw ApiException(400, "url")
        return match.groupValues[1]
    }

    private fun hostOk(url: String): Boolean {
        return try {
            val host = HttpUrl.parse(url)?.host?.lowercase(Locale.US) ?: return false
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
