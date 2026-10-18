package com.wuming.android.tv.utils

import android.util.Log
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.bean.EpisodeNameLog
import com.wuming.android.tv.db.AppDatabase
import kotlin.math.abs

object EpisodeDisplayHelper {

    private const val TAG = "EpisodeNameLog"
    private const val MAX_DISPLAY_LENGTH = 40
    private const val SAMPLE_RATE = 10

    @JvmStatic
    fun buildDisplayText(vodName: CharSequence, episodeDisplayName: String, totalEpisodes: Int): String {
        val fullText = if (totalEpisodes > 1) {
            "$vodName : $episodeDisplayName (共${totalEpisodes}集)"
        } else {
            "$vodName : $episodeDisplayName"
        }
        return if (fullText.length > MAX_DISPLAY_LENGTH) {
            fullText.substring(0, MAX_DISPLAY_LENGTH - 1) + "…"
        } else {
            fullText
        }
    }

    @JvmStatic
    fun logIfNeeded(vodName: String, episodeName: String, flagName: String, totalEpisodes: Int, displayName: String) {
        val displayText = if (totalEpisodes > 1) {
            "$vodName : $displayName (共${totalEpisodes}集)"
        } else {
            "$vodName : $displayName"
        }
        val isLong = displayText.length > MAX_DISPLAY_LENGTH
        if (isLong) {
            Log.w(TAG, "Long episode name ($displayText.length chars): vod=$vodName episode=$episodeName display=$displayName")
        }
        val shouldSample = !isLong && abs(displayText.hashCode()) % SAMPLE_RATE == 0
        if (isLong || shouldSample) {
            Task.execute {
                try {
                    val log = EpisodeNameLog(
                        vodName = vodName,
                        episodeName = episodeName,
                        flagName = flagName,
                        totalEpisodes = totalEpisodes,
                        displayName = displayName,
                        displayLength = displayText.length,
                        isLong = isLong,
                    )
                    AppDatabase.get().getEpisodeNameLogDao().insert(log)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to log episode name", e)
                }
            }
        }
    }
}
