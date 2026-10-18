package com.wuming.android.tv.cloud

import com.wuming.android.tv.bean.Site
import java.util.Locale

enum class CloudStorageProvider(
    val id: String,
    val displayName: String,
    val managementUrl: String,
    val androidPackage: String,
    vararg markers: String
) {

    ALIPAN("alipan", "Aliyun Drive", "https://www.alipan.com/drive/file/all", "com.alicloud.databox",
            "alipan", "aliyun", "ali_pan", "阿里云盘", "阿里網盤", "阿里网盘"),
    QUARK("quark", "Quark Drive", "https://pan.quark.cn/list#/list/all", "com.quark.browser",
            "quark", "夸克"),
    UC("uc", "UC Drive", "https://drive.uc.cn/", "com.UCMobile",
            "ucpan", "uc_pan", "drive.uc.cn", "uc云盘", "uc雲盤", "uc网盘", "uc網盤"),
    BAIDU("baidu", "Baidu Netdisk", "https://pan.baidu.com/disk/main#/index?category=all", "com.baidu.netdisk",
            "baidu", "百度云盘", "百度雲盤", "百度网盘", "百度網盤"),
    PAN123("123pan", "123 Netdisk", "https://yun.123pan.cn/", "com.mfcloudcalculate.networkdisk",
            "123pan", "123_pan", "123云盘", "123雲盤", "123网盘", "123網盤"),
    TIANYI("tianyi", "Tianyi Cloud", "https://cloud.189.cn/web/main", "com.cn21.ecloud",
            "tianyi", "cloud189", "cloud.189.cn", "天翼云盘", "天翼雲盤", "189云盘", "189雲盤"),
    DRIVE_115("115", "115", "https://115.com/?mode=wangpan", "com.ylmf.androidclient",
            "115.com", "pan115", "cloud115", "115网盘", "115網盤");

    val markers: List<String> = listOf(*markers)

    val androidLaunchUrl: String?
        get() {
            if (this == QUARK) {
                return "https://www.myquark.cn/?qk_tech=flutter&qk_biz=cloud_disk" +
                        "&qk_module=/clouddrive/main" +
                        "&qk_params=%7B%22statParams%22%3A%7B%22entry%22%3A%22desktop%22%7D" +
                        "%2C%22flutter_view_mode%22%3A%7B%22immerse%22%3Atrue%7D%7D"
            }
            if (this == UC) {
                return "https://www.uc.cn/?uc_flutter_route=/clouddrive/main" +
                        "&disable_rotate=1" +
                        "&clouddrive_params=%7B%22entry%22%3A%22usercentercard%22" +
                        "%2C%22additionProps%22%3A%7B%22tab%22%3A%22file%22%7D%7D"
            }
            return null
        }

    val androidLaunchActivity: String?
        get() = if (this == UC) "com.UCMobile.main.UCMobile" else null

    companion object {
        @JvmStatic
        fun fromId(id: String): CloudStorageProvider {
            for (provider in entries) {
                if (provider.id == id) return provider
            }
            return ALIPAN
        }
    }

    fun matches(site: Site?): Boolean {
        if (site == null || site.type != 3) return false
        val searchable = (site.key + "\n" + site.name + "\n" + site.api
                + "\n" + site.ext + "\n" + site.jar).lowercase(Locale.ROOT)
        for (marker in markers) {
            if (searchable.contains(marker.lowercase(Locale.ROOT))) return true
        }
        return false
    }
}
