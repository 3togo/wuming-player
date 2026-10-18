package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.server.RelayMonitorSnapshot
import com.wuming.tv.desktop.server.RelaySessionSnapshot
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.Locale
import javax.swing.*
import javax.swing.table.AbstractTableModel

/** Independent modeless window. Its refresh timer belongs to its visible panel. */
internal class PlaybackAssistanceMonitor(snapshot: () -> RelayMonitorSnapshot, onClosed: () -> Unit) : AutoCloseable {
    private val panel = PlaybackAssistanceMonitorPanel(snapshot)
    private val frame = JFrame("無名 · Playback assistance monitor").apply {
        iconImages = DesktopAppIcon.windowImages()
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        contentPane = panel
        minimumSize = Dimension(680, 300)
        setSize(880, 380)
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) { panel.close(); onClosed() }
        })
    }
    fun show() { panel.refresh(); frame.isVisible = true; frame.extendedState = JFrame.NORMAL; frame.toFront() }
    override fun close() { panel.close(); frame.dispose() }
}

internal class PlaybackAssistanceMonitorPanel(private val snapshot: () -> RelayMonitorSnapshot) : JPanel(BorderLayout(8, 12)), AutoCloseable {
    private val state = JLabel()
    private val totals = JLabel()
    private var rows = emptyList<RelaySessionSnapshot>()
    private var speeds = emptyMap<Long, Long>()
    private var previous = emptyMap<Long, Long>()
    private var sampledAt = 0L
    private var closed = false
    private val model = object : AbstractTableModel() {
        private val columns = listOf("Device", "Transfer status", "Delivered", "Speed", "Requests", "Errors", "Last activity")
        override fun getRowCount() = rows.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any {
            val session = rows[row]
            return when (column) {
                0 -> session.device
                1 -> when { session.activeRequests > 0 -> "Transferring"; session.requests == 0L -> "Ready"; else -> "Waiting" }
                2 -> bytes(session.deliveredBytes)
                3 -> bytes(speeds[session.number] ?: 0) + "/s"
                4 -> session.requests
                5 -> session.failures
                else -> if (session.activeRequests > 0) "Now" else "${session.idleMillis / 1000}s ago"
            }
        }
    }
    private val timer = Timer(1000) { refresh() }
    init {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); add(state); add(totals) }, BorderLayout.NORTH)
        add(JScrollPane(JTable(model).apply {
            name = "assistanceSessions"
            accessibleContext.accessibleName = "Playback assistance sessions"
            fillsViewportHeight = true
            rowHeight = 28
        }), BorderLayout.CENTER)
        add(JLabel("Desktop transfers only; Android playback and buffering are not measured here."), BorderLayout.SOUTH)
        refresh()
    }
    override fun addNotify() { super.addNotify(); if (!closed) { refresh(); timer.start() } }
    override fun removeNotify() { timer.stop(); previous = emptyMap(); sampledAt = 0; super.removeNotify() }
    fun refresh() {
        if (closed) return
        val value = snapshot()
        val now = System.nanoTime()
        val elapsed = now - sampledAt
        speeds = value.sessions.associate { session ->
            val prior = previous[session.number]
            session.number to if (sampledAt != 0L && elapsed > 0 && prior != null)
                ((session.deliveredBytes - prior).coerceAtLeast(0) * 1_000_000_000.0 / elapsed).toLong() else 0L
        }
        previous = value.sessions.associate { it.number to it.deliveredBytes }
        sampledAt = now; rows = value.sessions
        state.text = "${if (value.enabled) "Assistance enabled" else "Assistance disabled"} · ${value.pairedDevices} paired device(s) · ${rows.size} session(s) · ${value.preparing} preparing"
        totals.text = "Since app launch: ${bytes(value.deliveredBytes)} delivered · ${value.failures} error(s)"
        model.fireTableDataChanged()
    }
    override fun close() { closed = true; timer.stop(); rows = emptyList(); previous = emptyMap(); speeds = emptyMap() }
    private fun bytes(value: Long): String = when {
        value >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MiB", value / 1_048_576.0)
        value >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", value / 1024.0)
        else -> "$value B"
    }
}
