// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.update

import com.walkmanstudio.nativeplayer.BuildConfig
import com.walkmanstudio.nativeplayer.core.update.UpdateArtifact
import com.walkmanstudio.nativeplayer.core.update.UpdateTrust
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

class UpdateBody(val stream: InputStream, val length: Long = -1, private val dispose: () -> Unit = {}) : Closeable {
    override fun close() { try { stream.close() } finally { dispose() } }
}
fun interface UpdateTransport { fun open(url: String): UpdateBody }

/** No tokens, music metadata or device identifiers are sent. Redirects stay on GitHub infrastructure. */
class GithubUpdateTransport : UpdateTransport {
    override fun open(url: String): UpdateBody {
        require(UpdateTrust.requestUrl(url)) { "Địa chỉ cập nhật không được phép." }
        var target = url
        repeat(6) { hop ->
            require(UpdateTrust.requestUrl(target, redirect = hop > 0)) { "Địa chỉ chuyển hướng không được phép." }
            val connection = URI(target).toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000; connection.readTimeout = 20_000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "WalkmanStudioNative/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Chuyển hướng thiếu địa chỉ.")
                    target = URI(target).resolve(location).toString()
                    connection.disconnect()
                } else {
                    if (status != 200) throw IOException(if (status == 403 || status == 429) "GitHub đang giới hạn lượt kiểm tra. Hãy thử lại sau." else "Máy chủ cập nhật trả lỗi $status.")
                    return UpdateBody(connection.inputStream, connection.contentLengthLong) { connection.disconnect() }
                }
            } catch (error: Exception) { connection.disconnect(); throw error }
        }
        throw IOException("Quá nhiều lần chuyển hướng cập nhật.")
    }
}

class GithubUpdateCatalog(private val transport: UpdateTransport) {
    private fun json(url: String, limit: Int): JSONObject = transport.open(url).use { body ->
        require(body.length <= limit) { "Thông tin cập nhật quá lớn." }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = body.stream.read(buffer); if (n < 0) break
            require(n <= limit - output.size()) { "Thông tin cập nhật quá lớn." }
            output.write(buffer, 0, n)
        }
        JSONObject(output.toString(Charsets.UTF_8.name()))
    }
    fun latest(): UpdateArtifact? {
        val release = json(UpdateTrust.LATEST, 512 * 1024)
        if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) return null
        val assets = release.getJSONArray("assets")
        val manifests = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == "update.json" }
        if (manifests.isEmpty()) return null // Older releases did not contain an OTA manifest.
        require(manifests.size == 1)
        val manifestUrl = manifests.single().getString("browser_download_url")
        require(UpdateTrust.releaseUrl(manifestUrl) && URI(manifestUrl).path.endsWith("/update.json"))
        val candidate = decode(json(manifestUrl, 64 * 1024))
        val apkAssets = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("browser_download_url") == candidate.url }
        require(apkAssets.size == 1 && apkAssets.single().getLong("size") == candidate.bytes)
        val digest = apkAssets.single().optString("digest")
        require(digest.isBlank() || digest == "null" || digest == "sha256:${candidate.sha256}")
        return candidate
    }
    companion object {
        fun encode(candidate: UpdateArtifact): String = JSONObject().apply {
            put("schema", 1); put("versionCode", candidate.versionCode); put("versionName", candidate.versionName)
            put("minSdk", candidate.minSdk); put("bytes", candidate.bytes); put("sha256", candidate.sha256)
            put("url", candidate.url); put("notes", candidate.notes); put("certificateSha256", UpdateTrust.CERTIFICATE)
        }.toString()
        fun decode(json: JSONObject): UpdateArtifact {
            require(json.getInt("schema") == 1 && json.getString("certificateSha256") == UpdateTrust.CERTIFICATE)
            return UpdateArtifact(json.getLong("versionCode"), json.getString("versionName"), json.getInt("minSdk"),
                json.getLong("bytes"), json.getString("sha256"), json.getString("url"), json.optString("notes").take(2000))
        }
    }
}
