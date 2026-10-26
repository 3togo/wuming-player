package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.cast.DesktopCastService
import com.wuming.tv.desktop.player.ExternalPlayerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.swing.*

/** LAN services are opt-in and remain owned by the window while navigating between screens. */
class CastingPanel(private val player: ExternalPlayerAdapter?) : JPanel(BorderLayout(12, 12)), AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "tvbox-cast-ui") }
    private val devices = JComboBox<DesktopCastService.Target>()
    private val mediaLocation = JTextField(34)
    private val status = JLabel("Choose a video URL or file, or enable receiving from your phone.")
    private val receiveStatus = JLabel("Receiving is off")
    private val receive = JButton("Enable receiving")
    private val buttons = mutableListOf<JButton>()
    private var preparedVideo: com.wuming.tv.core.catalog.CatalogPlayback? = null
    private var preparedTitle = "TVBox video"
    private var onPreparedSent: (() -> Unit)? = null
    private var receiving = false
    private var closed = false
    private val service = DesktopCastService { found ->
        SwingUtilities.invokeLater {
            if (!closed) {
                val selected = (devices.selectedItem as? DesktopCastService.Target)?.id
                devices.model = DefaultComboBoxModel(found.toTypedArray())
                found.firstOrNull { it.id == selected }?.let { devices.selectedItem = it }
                devices.toolTipText = "${found.size} receivers found"
            }
        }
    }
    private val mpv = player?.executable?.takeIf { it.fileName.toString().equals("mpv", true) }
        ?: System.getenv("PATH").orEmpty().split(":").filter(String::isNotBlank)
            .map { Path.of(it, "mpv") }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    init {
        border = BorderFactory.createEmptyBorder(22, 22, 22, 22)
        val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        fun row(vararg components: java.awt.Component) = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            components.forEach(::add)
            alignmentX = LEFT_ALIGNMENT
        }
        content.add(row(JLabel("Video casting · Android / DLNA").apply { font = font.deriveFont(24f) }))
        content.add(row(JLabel("Connect both devices to the same local network.")))
        content.add(row(JLabel("Send a video")))
        val browse = JButton("Choose file…").apply {
            addActionListener {
                val chooser = JFileChooser()
                if (chooser.showOpenDialog(this@CastingPanel) == JFileChooser.APPROVE_OPTION) mediaLocation.text = chooser.selectedFile.absolutePath
            }
        }
        mediaLocation.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(event: javax.swing.event.DocumentEvent) = changed()
            override fun removeUpdate(event: javax.swing.event.DocumentEvent) = changed()
            override fun changedUpdate(event: javax.swing.event.DocumentEvent) = changed()
            private fun changed() {
                if (mediaLocation.text.trim() != preparedVideo?.url) {
                    preparedVideo = null
                    preparedTitle = "TVBox video"
                    onPreparedSent = null
                }
            }
        })
        content.add(row(mediaLocation, browse))
        devices.prototypeDisplayValue = DesktopCastService.Target("", "Choose a nearby DLNA receiver                 ", null)
        content.add(row(devices, button("Find receivers") {
            service.discover()
            "Searching the LAN… If none appear, enable DLNA receiver mode on the phone and search again."
        }))
        content.add(row(preparedButton("Send video", prepare = {
            val target = devices.selectedItem as? DesktopCastService.Target
            val value = mediaLocation.text.trim()
            val video = preparedVideo?.takeIf { it.url == value }
            val title = preparedTitle
            val sent = onPreparedSent
            val work: () -> String = {
                service.send(target, value, video?.headers.orEmpty(), title, video != null, video?.format.orEmpty())
                if (sent != null) SwingUtilities.invokeLater { if (!closed) sent() }
                "Video sent to ${target?.name}." + (service.compatibilityQualityLabel?.let { " TV compatibility playback: $it." } ?: "")
            }
            work
        }), button("Play") { service.control("Play"); "Play requested." },
            button("Pause") { service.control("Pause"); "Pause requested." },
            button("Stop / disconnect") { service.disconnect(); "Outgoing cast disconnected." },
            preparedButton("Open locally", prepare = {
                val value = mediaLocation.text
                val work: () -> String = { player?.play(value)?.message ?: "Install mpv or VLC to play locally." }
                work
            })))
        content.add(row(JLabel("The phone needs a DLNA receiver app to receive videos from this desktop.")))
        content.add(Box.createVerticalStrut(20))
        content.add(row(JLabel("Receive from Android")))
        buttons += receive
        receive.addActionListener {
            val enable = !receiving
            runWork {
                if (enable) service.receive(requireNotNull(mpv) { "Install mpv and restart to enable receiving." }) else service.stopReceiving()
                SwingUtilities.invokeLater {
                    receiving = enable
                    receive.text = if (enable) "Stop receiving" else "Enable receiving"
                    receiveStatus.text = if (enable) "Visible as TVBox Desktop" else "Receiving is off"
                }
                if (enable) "On your phone, open the video's DLNA cast menu and choose TVBox Desktop." else "Receiving stopped and player closed."
            }
        }
        content.add(row(receive, receiveStatus))
        content.add(row(JLabel("While enabled, devices on this LAN can start and control video playback.")))
        content.add(row(JLabel("Use an app's DLNA video-cast option. Screen mirroring and Chromecast are not supported.")))
        if (mpv == null) receiveStatus.text = "Install mpv and restart to enable receiving"
        add(JScrollPane(content).apply { border = null }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        updateButtons(true)
    }

    fun prepareVideo(playback: com.wuming.tv.core.catalog.CatalogPlayback, title: String, onSent: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        if (closed) return
        mediaLocation.text = playback.url
        preparedVideo = playback.copy(headers = playback.headers.toMap())
        preparedTitle = title
        onPreparedSent = onSent
        status.text = "Ready to cast $title. Choose a receiver and select Send video."
        runWork { service.discover(); "Choose a receiver and select Send video: $title" }
    }

    private fun button(label: String, action: () -> String): JButton = preparedButton(label, prepare = { action })
    private fun preparedButton(label: String, prepare: () -> (() -> String)): JButton = JButton(label).apply {
        buttons += this
        addActionListener { val work = prepare(); runWork(work) }
    }
    private fun updateButtons(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled }
        receive.isEnabled = enabled && (mpv != null || receiving)
    }
    private fun runWork(work: () -> String) {
        updateButtons(false)
        status.text = "Working…"
        worker.execute {
            val result = runCatching(work).getOrElse { error -> error.cause?.message ?: error.message ?: "Casting failed." }
            SwingUtilities.invokeLater { if (!closed) { status.text = result; updateButtons(true) } }
        }
    }
    override fun close() {
        closed = true
        updateButtons(false)
        worker.execute { service.close() }
        worker.shutdown()
    }
}
