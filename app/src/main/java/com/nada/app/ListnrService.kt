package com.nada.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The only reel/post resolver. There is deliberately no fallback source. */
class ListnrService(
    private val client: OkHttpClient = defaultClient(),
) {
    data class Video(val url: String, val thumb: String)

    fun resolve(postUrl: String): Video {
        val payload = JSONObject()
            .put("url", postUrl)
            .put("platform", "instagram")
            .put("type", "video")
            .toString()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("accept", "application/json")
            .header("content-type", "application/json")
            .header("origin", "https://listnr.ai")
            .header("referer", "https://listnr.ai/")
            .header("user-agent", UA)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw InstagramClient.ApiException(
                        if (response.code == 429) 429 else 502,
                        if (response.code == 429) "rate" else "upstream",
                    )
                }
                val body = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(body)
                } catch (_: Exception) {
                    throw InstagramClient.ApiException(502, "upstream")
                }
                // optString does not coerce arbitrary objects/arrays into usable URLs.
                val url = json.opt("url").let { if (it is String) it.trim() else "" }
                if (url.isBlank()) throw InstagramClient.ApiException(404, "empty")
                val data = json.optJSONObject("data")
                val thumb = data?.opt("thumb").let { if (it is String) it.trim() else "" }
                return Video(url, thumb)
            }
        } catch (ex: InstagramClient.ApiException) {
            throw ex
        } catch (_: IOException) {
            // Includes connect/read/write failures and the 15-second call timeout.
            throw InstagramClient.ApiException(502, "network")
        } catch (_: Exception) {
            throw InstagramClient.ApiException(502, "upstream")
        }
    }

    companion object {
        const val ENDPOINT = "https://bff.listnr.tech/backend/user/getInfoYT"
        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
