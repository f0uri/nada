package com.nada.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The primary reel/post resolver. It has no fallback of its own —
 * InstagramClient tries quicksave only after this throws (1.71). */
class ListnrService(
    private val client: OkHttpClient = defaultClient(),
) {
    data class Video(val url: String, val thumb: String)

    /**
     * Resolves through listnr only. A transient transport failure (timeout,
     * connect, TLS, read) is retried once after a short backoff — one slow
     * handshake must not kill every save. HTTP status errors are surfaced
     * immediately with their real code, never as a generic network error.
     */
    fun resolve(postUrl: String, diag: DownloadDiagnostics? = null): Video {
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
        var attempt = 0
        while (true) {
            attempt++
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        // The body often names the real cause ("No video found
                        // in response"); keep it — the notification must show
                        // listnr's real status, not the mapped 502 (1.70).
                        val message = runCatching {
                            JSONObject(response.body?.string().orEmpty())
                                .optString("message").trim()
                        }.getOrDefault("").take(80)
                        diag?.note("listnr HTTP ${response.code} (attempt $attempt)")
                        diag?.listnrDetail = "HTTP ${response.code}" +
                            if (message.isNotBlank()) ": $message" else ""
                        throw InstagramClient.ApiException(
                            if (response.code == 429) 429 else 502,
                            if (response.code == 429) "rate" else "upstream",
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val json = try {
                        JSONObject(body)
                    } catch (_: Exception) {
                        diag?.note("listnr bad json (attempt $attempt)")
                        diag?.listnrDetail = "bad json"
                        throw InstagramClient.ApiException(502, "upstream")
                    }
                    // optString does not coerce arbitrary objects/arrays into usable URLs.
                    val url = json.opt("url").let { if (it is String) it.trim() else "" }
                    if (url.isBlank()) {
                        diag?.note("listnr empty url (attempt $attempt)")
                        diag?.listnrDetail = "empty url"
                        throw InstagramClient.ApiException(404, "empty")
                    }
                    val data = json.optJSONObject("data")
                    val thumb = data?.opt("thumb").let { if (it is String) it.trim() else "" }
                    diag?.note("listnr ok (attempt $attempt)")
                    diag?.listnrDetail = "HTTP ${response.code}"
                    return Video(url, thumb)
                }
            } catch (ex: InstagramClient.ApiException) {
                // Status errors already carry their real code and detail.
                throw ex
            } catch (ex: IOException) {
                // Connect/read/write failures and the 20-second call timeout.
                val kind = DownloadDiagnostics.classify(ex)
                diag?.note("listnr $kind (attempt $attempt)")
                if (attempt < MAX_ATTEMPTS) {
                    runCatching { Thread.sleep(RETRY_BACKOFF_MS) }
                    continue
                }
                diag?.listnrDetail = "$kind ×$attempt"
                throw InstagramClient.ApiException(502, "network")
            } catch (ex: Exception) {
                diag?.note("listnr error (attempt $attempt): ${ex.javaClass.simpleName}")
                diag?.listnrDetail = "error"
                throw InstagramClient.ApiException(502, "upstream")
            }
        }
    }

    companion object {
        const val ENDPOINT = "https://bff.listnr.tech/backend/user/getInfoYT"
        const val MAX_ATTEMPTS = 2
        const val RETRY_BACKOFF_MS = 700L
        const val CALL_TIMEOUT_SECONDS = 20L
        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
