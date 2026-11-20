// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core.update

import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object UpdateTrust {
    const val APP_ID = "com.walkmanstudio.nativeplayer"
    const val REPOSITORY = "Melbournejr/walkman-studio-native"
    const val LATEST = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val CERTIFICATE = "2354b7de5b85a17540a1b7e43b0c66e49295ea353c9f17c33d54e9f42338dff2"
    const val MAX_APK_BYTES = 128L * 1024 * 1024
    fun releaseUrl(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && u.host == "github.com" && u.port == -1 && u.userInfo == null &&
            u.rawQuery == null && u.rawFragment == null &&
            Regex("/${Regex.escape(REPOSITORY)}/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(u.rawPath)
    }.getOrDefault(false)
    fun requestUrl(value: String, redirect: Boolean = false): Boolean = runCatching {
        if (value == LATEST || releaseUrl(value)) return@runCatching true
        val u = URI(value)
        redirect && u.scheme == "https" && u.port == -1 && u.userInfo == null && u.fragment == null &&
            u.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    }.getOrDefault(false)
}

data class UpdateArtifact(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val bytes: Long,
    val sha256: String,
    val url: String,
    val notes: String = "",
) {
    init {
        require(versionCode > 0 && versionName.isNotBlank() && versionName.length <= 100)
        require(minSdk >= 26 && bytes in 1..UpdateTrust.MAX_APK_BYTES)
        require(Regex("[0-9a-f]{64}").matches(sha256))
        require(UpdateTrust.releaseUrl(url) && URI(url).path.substringAfterLast('/').endsWith(".apk"))
        require(notes.length <= 2000)
    }
    val fileName: String get() = "walkman-update-$versionCode.apk"
}

data class ApkIdentity(val packageName: String, val versionCode: Long, val minSdk: Int, val certificates: Set<String>)

object UpdateValidation {
    fun identity(apk: ApkIdentity, expected: UpdateArtifact, installedVersion: Long, installedCertificates: Set<String>, sdk: Int) {
        require(apk.packageName == UpdateTrust.APP_ID) { "Gói cài đặt không đúng ứng dụng." }
        require(apk.versionCode == expected.versionCode && apk.versionCode > installedVersion) { "Từ chối phiên bản cũ hoặc sai phiên bản." }
        require(apk.minSdk <= sdk && apk.minSdk == expected.minSdk) { "Phiên bản Android không tương thích." }
        require(installedCertificates == setOf(UpdateTrust.CERTIFICATE) && apk.certificates == installedCertificates) {
            "Chứng chỉ ký không khớp bản đang cài."
        }
    }
}

object UpdateTransfer {
    /** Bounded streaming; cancellation is checked even when a source keeps returning bytes. */
    suspend fun copy(input: InputStream, output: OutputStream, expected: UpdateArtifact, progress: suspend (Long) -> Unit = {}): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(read.toLong() <= expected.bytes - total) { "Kích thước gói cập nhật vượt thông tin công bố." }
            output.write(buffer, 0, read); digest.update(buffer, 0, read); total += read
            progress(total)
        }
        require(total == expected.bytes) { "Gói cập nhật tải chưa đủ." }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) { "SHA-256 gói cập nhật không khớp." }
        output.flush()
        return total
    }
}
