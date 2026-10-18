package com.wuming.android.tv.api.config

import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.source.RecordAdmission
import com.wuming.tv.core.source.SectionAdmission
import com.wuming.tv.core.source.TvBoxRecordFields

/**
 * Turns one section of a TVBox config into the list the app uses, and says what happened to the
 * records that did not make it.
 *
 * The keep/reject/dedup rules themselves live in `source-core` so the desktop client applies the
 * same ones; this object only names the fields of the app's own beans.
 */
object ConfigAdmission {

    @JvmField
    val SITES: RecordAdmission<Site> = RecordAdmission.of(
        { site: Site -> site.key },
        { site: Site -> TvBoxRecordFields.siteRejection(site.key, site.api) },
    )

    @JvmField
    val LIVES: RecordAdmission<Live> = RecordAdmission.of(
        { live: Live -> live.name },
        { live: Live -> TvBoxRecordFields.liveRejection(live.url) },
    )

    /**
     * Logs the whole section, kept records included. Refusals are raised to a warning because a
     * release build's info logging is off, and a config that quietly lost half its sources is not
     * something to find out about by counting menu entries.
     */
    @JvmStatic
    fun log(tag: String, admission: SectionAdmission<*>) {
        if (admission.noteworthy) AppLog.w(tag, admission.summary()) else AppLog.i(tag, admission.summary())
    }
}
