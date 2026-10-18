package com.wuming.tv.core.library

/** Sessions never become recordable again after a privacy change or history deletion. */
class ViewingPrivacyPolicy {
    data class Session internal constructor(internal val generation: Long, internal val private: Boolean)
    private var generation = 0L
    var privateViewing = false
        set(value) { if (field != value) { field = value; invalidateSessions() } }
    fun beginSession(): Session = Session(generation, privateViewing)
    fun mayRecord(session: Session): Boolean = !privateViewing && !session.private && session.generation == generation
    fun invalidateSessions() { generation++ }
    companion object {
        fun forgetProgress(entry: VideoLibraryEntry): VideoLibraryEntry = entry.copy(
            positionMillis = 0, durationMillis = 0, lineFlag = "", episodeId = "", episodeName = "",
        )
    }
}
