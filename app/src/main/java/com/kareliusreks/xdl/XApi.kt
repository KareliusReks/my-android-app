package com.kareliusreks.xdl

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VideoVariant(
    val url: String,
    val bitrate: Int,
    val width: Int,
    val height: Int,
) {
    val label: String
        get() {
            val res = if (height > 0) "${height}p" else "MP4"
            val size = if (width > 0 && height > 0) " ($width×$height)" else ""
            val kbps = if (bitrate > 0) ", ${bitrate / 1000} кбит/с" else ""
            return "$res$size$kbps"
        }
}

data class TweetVideos(
    val tweetId: String,
    val text: String,
    val author: String,
    /** Каждое видео поста — список его вариантов качества, от лучшего к худшему */
    val videos: List<List<VideoVariant>>,
)

object XApi {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val idRegex = Regex("""/status(?:es)?/(\d+)""")
    private val resRegex = Regex("""/(\d{2,5})x(\d{2,5})/""")

    /** Достаёт ID поста из ссылки x.com / twitter.com / fxtwitter и т.п. */
    fun extractId(input: String): String? {
        idRegex.find(input)?.let { return it.groupValues[1] }
        val trimmed = input.trim()
        return if (trimmed.matches(Regex("""\d{5,25}"""))) trimmed else null
    }

    /** Основной путь — syndication API X, запасной — api.fxtwitter.com */
    fun fetch(id: String, token: String?): TweetVideos {
        val errors = mutableListOf<String>()
        try {
            val r = fetchSyndication(id, token ?: "a")
            if (r.videos.isNotEmpty()) return r
            errors += "в посте нет видео (syndication)"
        } catch (e: Exception) {
            errors += "syndication: ${e.message}"
        }
        try {
            val r = fetchFx(id)
            if (r.videos.isNotEmpty()) return r
            errors += "в посте нет видео (fxtwitter)"
        } catch (e: Exception) {
            errors += "fxtwitter: ${e.message}"
        }
        throw IllegalStateException(errors.joinToString("; "))
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchSyndication(id: String, token: String): TweetVideos {
        val body = get("https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=ru&token=$token")
        if (body.isBlank() || body.trim() == "{}") throw IllegalStateException("пустой ответ (пост удалён, приватный или 18+)")
        val json = JSONObject(body)
        val videos = mutableListOf<List<VideoVariant>>()
        collectSyndicationMedia(json, videos)
        if (videos.isEmpty()) json.optJSONObject("quoted_tweet")?.let { collectSyndicationMedia(it, videos) }
        return TweetVideos(
            tweetId = id,
            text = json.optString("text"),
            author = json.optJSONObject("user")?.optString("screen_name").orEmpty(),
            videos = videos,
        )
    }

    private fun collectSyndicationMedia(json: JSONObject, out: MutableList<List<VideoVariant>>) {
        val media = json.optJSONArray("mediaDetails") ?: return
        for (i in 0 until media.length()) {
            val m = media.optJSONObject(i) ?: continue
            val variants = m.optJSONObject("video_info")?.optJSONArray("variants") ?: continue
            val list = parseVariants(variants)
            if (list.isNotEmpty()) out += list
        }
    }

    private fun parseVariants(arr: JSONArray): List<VideoVariant> {
        val list = mutableListOf<VideoVariant>()
        for (j in 0 until arr.length()) {
            val v = arr.optJSONObject(j) ?: continue
            val type = v.optString("content_type", v.optString("type"))
            val url = v.optString("url", v.optString("src"))
            if (url.isEmpty() || !type.contains("mp4")) continue
            val res = resRegex.find(url)
            list += VideoVariant(
                url = url,
                bitrate = v.optInt("bitrate", 0),
                width = res?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                height = res?.groupValues?.get(2)?.toIntOrNull() ?: 0,
            )
        }
        return list.distinctBy { it.url }
            .sortedWith(compareByDescending<VideoVariant> { it.height }.thenByDescending { it.bitrate })
    }

    private fun fetchFx(id: String): TweetVideos {
        val json = JSONObject(get("https://api.fxtwitter.com/status/$id"))
        val tweet = json.optJSONObject("tweet") ?: throw IllegalStateException(json.optString("message", "нет данных"))
        val videos = mutableListOf<List<VideoVariant>>()
        val arr = tweet.optJSONObject("media")?.optJSONArray("videos")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i) ?: continue
                val fromVariants = v.optJSONArray("variants")?.let { parseVariants(it) }.orEmpty()
                if (fromVariants.isNotEmpty()) {
                    videos += fromVariants
                } else {
                    val url = v.optString("url")
                    if (url.isNotEmpty()) {
                        videos += listOf(VideoVariant(url, 0, v.optInt("width"), v.optInt("height")))
                    }
                }
            }
        }
        return TweetVideos(
            tweetId = id,
            text = tweet.optString("text"),
            author = tweet.optJSONObject("author")?.optString("screen_name").orEmpty(),
            videos = videos,
        )
    }
}
