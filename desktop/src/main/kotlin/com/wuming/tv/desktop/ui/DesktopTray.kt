package com.wuming.tv.desktop.ui

import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import javax.swing.SwingUtilities

/** Application-owned icon: hiding a window never removes it. Only explicit exit does. */
internal class DesktopTray(
    private val show: () -> Unit,
    private val monitor: () -> Unit,
    private val exit: () -> Unit,
    private val hide: () -> Unit,
    private val minimize: () -> Unit,
    private val port: Port = AwtPort,
) : AutoCloseable {
    interface Port {
        fun install(show: () -> Unit, monitor: () -> Unit, exit: () -> Unit): AutoCloseable?
    }
    private var registration: AutoCloseable? = null
    private var closed = false
    fun install(): Boolean {
        if (closed) return false
        if (registration == null) registration = runCatching {
            port.install({ if (!closed) show() }, { if (!closed) monitor() }, { if (!closed) exit() })
        }.getOrNull()
        return registration != null
    }
    fun keepInBackground(relayRunning: Boolean): Boolean {
        if (closed) return false
        if (install()) { hide(); return true }
        if (relayRunning) { minimize(); return true }
        return false
    }
    override fun close() {
        closed = true
        registration?.close(); registration = null
    }
    private object AwtPort : Port {
        override fun install(show: () -> Unit, monitor: () -> Unit, exit: () -> Unit): AutoCloseable? {
            if (!SystemTray.isSupported()) return null
            val menuFont = javax.swing.UIManager.getFont("MenuItem.font")
                ?.deriveFont(Font.BOLD, DesktopTvTheme.px(18).toFloat())
                ?: Font("SansSerif", Font.BOLD, 18)
            fun item(label: String, action: () -> Unit) = MenuItem(label).apply {
                font = menuFont
                addActionListener { SwingUtilities.invokeLater { action() } }
            }
            val menu = PopupMenu().apply {
                font = menuFont
                add(item("Show 無名", show))
                add(item("Playback assistance monitor", monitor))
                addSeparator()
                add(item("Exit 無名 and stop assistance", exit))
            }
            val icon = TrayIcon(DesktopAppIcon.image(32), "無名 · Playback assistance", menu).apply {
                isImageAutoSize = true
                addActionListener { SwingUtilities.invokeLater { show() } }
            }
            val tray = SystemTray.getSystemTray()
            tray.add(icon)
            return AutoCloseable { tray.remove(icon) }
        }
    }
}
