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
            // AWT already scales its native popup. Applying the app's enlarged, bold
            // Swing font here made tray rows oversized and disproportionately wide.
            val nativeMenu = PopupMenu()
            val menuFont = Font(Font.DIALOG, Font.PLAIN, nativeMenu.font?.size ?: 13)
            fun item(label: String, action: () -> Unit) = MenuItem(label).apply {
                font = menuFont
                addActionListener { SwingUtilities.invokeLater { action() } }
            }
            val menu = nativeMenu.apply {
                font = menuFont
                add(item("Open 無名", show))
                add(item("Playback monitor", monitor))
                addSeparator()
                add(item("Quit 無名", exit))
            }
            val icon = TrayIcon(DesktopAppIcon.image(32), "無名", menu).apply {
                isImageAutoSize = true
                addActionListener { SwingUtilities.invokeLater { show() } }
            }
            val tray = SystemTray.getSystemTray()
            tray.add(icon)
            return AutoCloseable { tray.remove(icon) }
        }
    }
}
