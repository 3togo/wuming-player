package com.wuming.android.tv.bean

import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Element
import org.simpleframework.xml.ElementList
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text

@Root(name = "tv", strict = false)
class Tv {

    @field:Attribute(name = "date", required = false)
    private var date: String? = null

    @field:ElementList(entry = "channel", required = false, inline = true)
    private var channel: List<Channel>? = null

    @field:ElementList(entry = "programme", required = false, inline = true)
    private var programme: List<Programme>? = null

    fun getDate(): String = BeanUtils.emptyIfNull(date)

    fun getChannel(): List<Channel> = BeanUtils.emptyListIfNull(channel)

    fun getProgramme(): List<Programme> = BeanUtils.emptyListIfNull(programme)

    @Root(name = "channel")
    class Channel {

        @field:Attribute(name = "id", required = false)
        private var id: String? = null

        @field:Element(name = "icon", required = false)
        private var icon: Icon? = null

        @field:ElementList(entry = "display-name", required = false, inline = true)
        private var displayName: List<DisplayName>? = null

        fun getId(): String = BeanUtils.emptyIfNull(id)

        private fun getIcon(): Icon = icon ?: Icon()

        fun getDisplayName(): List<DisplayName> = BeanUtils.emptyListIfNull(displayName, true)

        fun getSrc(): String = getIcon().getSrc()

        fun hasSrc(): Boolean = getIcon().getSrc().isNotEmpty()
    }

    @Root(name = "programme")
    class Programme {

        @field:Attribute(name = "start", required = false)
        private var start: String? = null

        @field:Attribute(name = "stop", required = false)
        private var stop: String? = null

        @field:Attribute(name = "channel", required = false)
        private var channel: String? = null

        @field:Element(name = "title", required = false)
        private var title: String? = null

        fun getStart(): String = BeanUtils.emptyIfNull(start)

        fun getStop(): String = BeanUtils.emptyIfNull(stop)

        fun getChannel(): String = BeanUtils.emptyIfNull(channel)

        fun getTitle(): String = BeanUtils.emptyIfNull(title)
    }

    @Root(name = "icon")
    class Icon {

        @field:Attribute(name = "src")
        private var src: String? = null

        fun getSrc(): String = BeanUtils.emptyIfNull(src)
    }

    @Root(name = "display-name")
    class DisplayName {

        @field:Text
        private var text: String? = null

        fun getText(): String = BeanUtils.emptyIfNull(text)
    }
}
