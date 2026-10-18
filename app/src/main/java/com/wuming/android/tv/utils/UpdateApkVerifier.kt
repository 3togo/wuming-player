package com.wuming.android.tv.utils

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import com.wuming.android.tv.App
import com.wuming.tv.core.base.Digests
import com.wuming.tv.core.design.update.UpdateArtifact
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Arrays

/** Verifies downloaded bytes and installed signing identity before invoking the installer. */
object UpdateApkVerifier {
    @JvmStatic
    @Throws(Exception::class)
    @Suppress("DEPRECATION")
    fun verify(
        file: File,
        artifact: UpdateArtifact,
    ) {
        if (FileInputStream(file).use { Digests.sha256Hex(it) } != artifact.sha256) {
            throw IOException("APK checksum mismatch")
        }
        val manager = App.get().packageManager
        val incoming = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        val installed = manager.getPackageInfo(App.get().packageName, PackageManager.GET_SIGNATURES)
        if (incoming == null || artifact.packageName != incoming.packageName || incoming.versionCode != artifact.code ||
            incoming.versionCode <= installed.versionCode
        ) {
            throw IOException("APK package or version mismatch")
        }
        val signatures = incoming.signatures
        if (signatures == null || signatures.size != 1 || !Arrays.equals(signatures, installed.signatures) ||
            Digests.sha256Hex(signatures[0].toByteArray()) != artifact.certificateSha256
        ) {
            throw IOException("APK signing certificate mismatch")
        }
    }
}
