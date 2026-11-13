package com.nada.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * quicksave.my fallback — the same fallback Reel Checker already uses.
 * Used ONLY when listnr cannot find the video (e.g. HTTP 400 "No video
 * found in response"). Listnr stays first; quicksave never replaces it.
 * Contract (mirrors Reel Checker's QuickSaveService): POST
 * https://quicksave.my/api/info {"url": <instagram reel url>}, 30 s
 * timeout, keep type==video && url!=null, pick the highest height.
 */
class QuickSaveFallback(
    private val client: OkHttpClient = defaultClient(),
) {
    fun resolve(postUrl: String, diag: DownloadDiagnostics? = null): ListnrService.Video {
        val payload = JSONObject().put("url", postUrl).toString()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("accept", "*/*")
            .header("content-type", "application/json")
            .header("origin", "https://quicksave.my")
            .header("referer", "https://quicksave.my/")
            .header("user-agent", UA)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    diag?.note("quicksave HTTP ${response.code}")
                    throw when (response.code) {
                        404 -> InstagramClient.ApiException(404, "missing")
                        429, 403 -> InstagramClient.ApiException(429, "rate")
                        else -> InstagramClient.ApiException(502, "upstream")
                    }
                }
                val json = try {
                    JSONObject(response.body?.string().orEmpty())
                } catch (_: Exception) {
                    diag?.note("quicksave bad json")
                    throw InstagramClient.ApiException(502, "upstream")
                }
                val formats = json.optJSONArray("formats") ?: run {
                    diag?.note("quicksave missing formats")
                    throw InstagramClient.ApiException(404, "empty")
                }
                var bestUrl = ""
                var bestHeight = -1
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    if (!"video".equals(f.optString("type").trim(), ignoreCase = true)) continue
                    val u = f.optString("url").trim()
                    if (u.isBlank()) continue
                    val h = when (val hv = f.opt("height")) {
                        is Number -> hv.toInt()
                        is String -> hv.toIntOrNull() ?: 0
                        else -> 0
                    }
                    if (h > bestHeight) {
                        bestHeight = h
                        bestUrl = u
                    }
                }
                if (bestUrl.isBlank()) {
                    diag?.note("quicksave no video format")
                    throw InstagramClient.ApiException(404, "empty")
                }
                val thumb = json.optString("thumbnail").trim().replace("&amp;", "&")
                diag?.note("quicksave ok height=$bestHeight")
                return ListnrService.Video(bestUrl, thumb)
            }
        } catch (ex: InstagramClient.ApiException) {
            throw ex
        } catch (ex: IOException) {
            val kind = DownloadDiagnostics.classify(ex)
            diag?.note("quicksave $kind")
            throw InstagramClient.ApiException(502, "network")
        } catch (ex: Exception) {
            diag?.note("quicksave error: ${ex.javaClass.simpleName}")
            throw InstagramClient.ApiException(502, "upstream")
        }
    }

    companion object {
        const val ENDPOINT = "https://quicksave.my/api/info"
        private const val UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
