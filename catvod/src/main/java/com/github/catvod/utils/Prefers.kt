package com.github.catvod.utils

import android.content.Context
import android.content.SharedPreferences
import com.github.catvod.Init
import com.github.catvod.Init.context
import java.io.File
import kotlin.concurrent.Volatile

open class Prefers {
    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        private const val PREFS_NAME = "settings"

        @Volatile
        private var instance: SharedPreferences? = null

        @JvmStatic
        open fun getPrefers(): SharedPreferences {
            if (instance == null) {
                synchronized(Prefers::class.java) {
                    if (instance == null) {
                        instance =
                            context()!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    }
                }
            }
            return instance!!
        }

        @get:JvmStatic
        val prefsDir: File
            /**
             * Get the shared_prefs directory under Path.tv() for migration purposes.
             */
            get() = File(Path.tv(), "shared_prefs")

        @get:JvmStatic
        val prefsFile: File
            /**
             * Get the preferences XML file for migration purposes.
             */
            get() = File(prefsDir, PREFS_NAME + ".xml")

        @JvmStatic
        open fun getString(key: String?): String? = getString(key, "")

        @JvmStatic
        open fun getString(key: String?, defaultValue: String?): String? {
            try {
                return getPrefers().getString(key, defaultValue)
            } catch (e: Exception) {
                return defaultValue
            }
        }

        @JvmStatic
        open fun getInt(key: String?): Int = getInt(key, 0)

        @JvmStatic
        open fun getInt(key: String?, defaultValue: Int): Int {
            try {
                return getPrefers().getInt(key, defaultValue)
            } catch (e: Exception) {
                return defaultValue
            }
        }

        @JvmStatic
        open fun getLong(key: String?): Long = getLong(key, 0L)

        @JvmStatic
        open fun getLong(key: String?, defaultValue: Long): Long {
            try {
                return getPrefers().getLong(key, defaultValue)
            } catch (e: Exception) {
                return defaultValue
            }
        }

        @JvmStatic
        open fun getFloat(key: String?): Float = getFloat(key, 0f)

        @JvmStatic
        open fun getFloat(key: String?, defaultValue: Float): Float {
            try {
                return getPrefers().getFloat(key, defaultValue)
            } catch (e: Exception) {
                return defaultValue
            }
        }

        @JvmStatic
        open fun getBoolean(key: String?): Boolean = getBoolean(key, false)

        @JvmStatic
        open fun getBoolean(key: String?, defaultValue: Boolean): Boolean {
            try {
                return getPrefers().getBoolean(key, defaultValue)
            } catch (e: Exception) {
                return defaultValue
            }
        }

        @JvmStatic
        open fun put(key: String?, obj: Any?) {
            if (obj == null) return
            if (obj is String) {
                getPrefers().edit().putString(key, obj).apply()
            } else if (obj is Boolean) {
                getPrefers().edit().putBoolean(key, obj).apply()
            } else if (obj is Float) {
                getPrefers().edit().putFloat(key, obj).apply()
            } else if (obj is Int) {
                getPrefers().edit().putInt(key, obj).apply()
            } else if (obj is Long) {
                getPrefers().edit().putLong(key, obj).apply()
            } else if (obj is Number) {
                val `val`: Number = obj
                if (`val`.toString().contains(".")) {
                    put(key, `val`.toFloat())
                } else {
                    val number: Long = `val`.toLong()
                    if (number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) {
                        put(key, number.toInt())
                    } else {
                        put(key, number)
                    }
                }
            }
        }

        @JvmStatic
        open fun remove(key: String?) {
            getPrefers().edit().remove(key).apply()
        }

        @JvmStatic
        open fun clear() {
            getPrefers().edit().clear().apply()
        }
    }
}
