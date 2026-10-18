package com.wuming.android.tv.utils

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import java.io.Serializable
import java.util.HashMap

/**
 * Utility class for Bundle and Intent extras retrieval.
 * Uses the type-safe APIs on API 33+ and falls back to the deprecated APIs on older versions.
 */
object BundleUtil {
    /**
     * Get a Parcelable from a Bundle using the type-safe API on API 33+.
     */
    @Suppress("DEPRECATION")
    @JvmStatic
    fun <T : Parcelable> getParcelable(bundle: Bundle?, key: String, clazz: Class<T>): T? {
        if (bundle == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable(key, clazz)
        } else {
            bundle.getParcelable(key)
        }
    }

    /**
     * Get a Parcelable from an Intent using the type-safe API on API 33+.
     */
    @Suppress("DEPRECATION")
    @JvmStatic
    fun <T : Parcelable> getParcelableExtra(intent: Intent?, key: String, clazz: Class<T>): T? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, clazz)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    /**
     * Get a Serializable from a Bundle using the type-safe API on API 33+.
     */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    @JvmStatic
    fun <T : Serializable> getSerializable(bundle: Bundle?, key: String, clazz: Class<T>): T? {
        if (bundle == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getSerializable(key, clazz)
        } else {
            bundle.getSerializable(key) as T?
        }
    }

    /**
     * Get a HashMap from a Bundle's Serializable extra.
     * Convenience method for the common pattern of storing HashMap as Serializable.
     */
    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    @JvmStatic
    fun getHashMap(bundle: Bundle?, key: String): HashMap<String, String>? {
        if (bundle == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val serializable = bundle.getSerializable(key, HashMap::class.java)
            serializable as? HashMap<String, String>
        } else {
            bundle.getSerializable(key) as? HashMap<String, String>
        }
    }
}
