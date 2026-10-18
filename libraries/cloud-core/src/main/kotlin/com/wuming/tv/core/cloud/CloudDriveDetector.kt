package com.wuming.tv.core.cloud

import java.util.Locale

/** Provider-neutral recognition for cloud-drive lines embedded in CatVod results. */
object CloudDriveDetector {
    const val ACCOUNT_REQUIRED_PREFIX = "Cloud account required:"

    @JvmStatic
    fun detect(flag: String?, address: String?, lineName: String?): CloudDriveProviderKind {
        val value = (safe(flag) + "\n" + safe(address) + "\n" + safe(lineName)).lowercase(Locale.ROOT)
        if (value.contains("pan.quark.cn") || value.contains("夸克") || value.contains("夸父")) {
            return CloudDriveProviderKind.QUARK
        }
        if (value.contains("drive.uc.cn") || value.contains("优汐") || value.contains("uc盘")
            || value.contains("uc原") || value.contains("uc智")) {
            return CloudDriveProviderKind.UC
        }
        if (value.contains("alipan.com") || value.contains("aliyundrive.com")
            || value.contains("阿里") || value.contains("阿狸")) {
            return CloudDriveProviderKind.ALI
        }
        if (value.contains("pan.baidu.com") || value.contains("百度网盘")
            || value.contains("百度云") || value.contains("bd原") || value.contains("bd预")) {
            return CloudDriveProviderKind.BAIDU
        }
        if (value.contains("123pan") || value.contains("123云盘") || value.contains("123cloud")) {
            return CloudDriveProviderKind.PAN123
        }
        if (value.contains("cloud189") || value.contains("天翼") || value.contains("189.cn")) {
            return CloudDriveProviderKind.TIANYI
        }
        if (value.contains("webdav")) {
            return CloudDriveProviderKind.WEBDAV
        }
        if (value.contains("115pan") || value.contains("115网盘") || value.contains("115.com")) {
            return CloudDriveProviderKind.PAN115
        }
        if (value.contains("pan.xunlei") || value.contains("迅雷") || value.contains("xunlei")) {
            return CloudDriveProviderKind.XUNLEI
        }
        if (value.contains("pikpak") || value.contains("mypikpak")) {
            return CloudDriveProviderKind.PIKPAK
        }
        return CloudDriveProviderKind.NONE
    }

    @JvmStatic
    fun displayName(provider: CloudDriveProviderKind): String {
        return when (provider) {
            CloudDriveProviderKind.QUARK -> "Quark"
            CloudDriveProviderKind.UC -> "UC"
            CloudDriveProviderKind.ALI -> "Aliyun Drive"
            CloudDriveProviderKind.BAIDU -> "Baidu Netdisk"
            CloudDriveProviderKind.PAN123 -> "123 Pan"
            CloudDriveProviderKind.TIANYI -> "Tianyi Cloud"
            CloudDriveProviderKind.WEBDAV -> "WebDAV"
            CloudDriveProviderKind.PAN115 -> "115 Netdisk"
            CloudDriveProviderKind.XUNLEI -> "Xunlei Cloud"
            CloudDriveProviderKind.PIKPAK -> "PikPak"
            else -> "cloud drive"
        }
    }

    @JvmStatic
    fun accountRequiredMessage(provider: CloudDriveProviderKind): String {
        return if (hasOpenDriveProvider(provider)) {
            "$ACCOUNT_REQUIRED_PREFIX ${displayName(provider)}. Sign in under Settings > Cloud Storage."
        } else {
            "$ACCOUNT_REQUIRED_PREFIX ${displayName(provider)}. This provider is not yet supported."
        }
    }

    @JvmStatic
    fun hasOpenDriveProvider(provider: CloudDriveProviderKind): Boolean {
        return provider in SUPPORTED_PROVIDERS
    }

    @JvmStatic
    fun isAccountRequiredMessage(value: String?): Boolean {
        return value != null && value.startsWith(ACCOUNT_REQUIRED_PREFIX)
    }

    private val SUPPORTED_PROVIDERS = setOf(
        CloudDriveProviderKind.QUARK,
        CloudDriveProviderKind.UC,
        CloudDriveProviderKind.ALI,
        CloudDriveProviderKind.BAIDU,
        CloudDriveProviderKind.PAN123,
        CloudDriveProviderKind.TIANYI,
        CloudDriveProviderKind.WEBDAV,
    )

    private fun safe(value: String?): String = value ?: ""
}
