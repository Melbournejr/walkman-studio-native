// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.walkmanstudio.nativeplayer.core.update.ApkIdentity
import com.walkmanstudio.nativeplayer.core.update.UpdateArtifact
import com.walkmanstudio.nativeplayer.core.update.UpdateValidation
import java.io.File
import java.security.MessageDigest

fun interface ArchiveIdentityReader { fun read(file: File): ApkIdentity }

class AndroidArchiveReader(private val context: Context) : ArchiveIdentityReader {
    @Suppress("DEPRECATION") // API26/27 have no SigningInfo; typed flags start at API33.
    override fun read(file: File): ApkIdentity {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else context.packageManager.getPackageArchiveInfo(file.path, flags)
        requireNotNull(info) { "Chưa đọc được chữ ký gói cài đặt." }
        return identity(info)
    }
    @Suppress("DEPRECATION")
    fun installed(): ApkIdentity {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else context.packageManager.getPackageInfo(context.packageName, flags)
        return identity(info)
    }
    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) requireNotNull(info.signingInfo).apkContentsSigners else info.signatures
        val certificates = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        return ApkIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            requireNotNull(info.applicationInfo).minSdkVersion, certificates)
    }
}

class VerifiedUpdateFiles(
    private val folder: File,
    private val archive: ArchiveIdentityReader,
    private val installed: () -> ApkIdentity,
    private val sdk: Int,
) {
    fun target(candidate: UpdateArtifact): File = File(folder, candidate.fileName)
    fun verify(file: File, candidate: UpdateArtifact) {
        require(file.canonicalFile.parentFile == folder.canonicalFile && file.isFile && file.length() == candidate.bytes)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val buffer=ByteArray(64*1024); while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        require(digest.digest().joinToString("") { "%02x".format(it) } == candidate.sha256) { "SHA-256 gói cập nhật không khớp." }
        val current = installed()
        UpdateValidation.identity(archive.read(file), candidate, current.versionCode, current.certificates, sdk)
    }
}
