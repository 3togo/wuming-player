package com.wuming.android.tv.player.danmaku

import com.wuming.android.tv.bean.DanmakuData
import com.wuming.android.tv.utils.AppLog
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.danmaku.parser.android.AndroidFileSource
import master.flame.danmaku.danmaku.util.DanmakuUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Matcher
import java.util.regex.Pattern

open class Parser : BaseDanmakuParser() {
    companion object {
        private const val TAG = "Parser"
        private val XML = Pattern.compile("p=\"([^\"]+)\"[^>]*>([^<]+)<")
        private val TXT = Pattern.compile("\\[(.*?)\\](.*)")
    }

    override fun parse(): Danmakus {
        if (mDataSource == null) return Danmakus(IDanmakus.ST_BY_TIME)
        var index = 0
        var pattern: Pattern? = null
        val result = Danmakus(IDanmakus.ST_BY_TIME)
        val source = mDataSource as AndroidFileSource
        try {
            BufferedReader(InputStreamReader(source.data())).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (Thread.currentThread().isInterrupted) return result
                    if (pattern == null) pattern = if (line.startsWith("<")) XML else TXT
                    val matcher = pattern!!.matcher(line)
                    while (matcher.find() && matcher.groupCount() == 2) {
                        val item = createDanmakuItem(matcher, index++)
                        if (item != null) synchronized(result.obtainSynchronizer()) { result.addItem(item) }
                    }
                }
            }
            return result
        } catch (e: Exception) {
            AppLog.e(TAG, e)
            return result
        } finally {
            source.release()
        }
    }

    private fun createDanmakuItem(
        matcher: Matcher,
        index: Int,
    ): BaseDanmaku? {
        try {
            val data = DanmakuData(matcher, mDispDensity)
            var type = data.getType()
            if (type == 2 || type == 3) type = BaseDanmaku.TYPE_SCROLL_RL
            val item = mContext.mDanmakuFactory.createDanmaku(type, mContext)
            if (item == null || item.type == BaseDanmaku.TYPE_SPECIAL) return null
            DanmakuUtils.fillText(item, data.getText())
            item.textShadowColor = data.getShadow()
            item.textColor = data.getColor()
            item.flags = mContext.mGlobalFlagValues
            item.textSize = data.getSize()
            item.time = data.getTime()
            item.setTimer(mTimer)
            item.index = index
            return item
        } catch (_: Exception) {
            return null
        }
    }
}
