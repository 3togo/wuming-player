package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.server.DesktopLanRelay
import java.awt.BorderLayout
import javax.swing.*

/** User-controlled service lifecycle; pairing codes are displayed only on demand. */
class LanRelayPreferencesPanel(private val relay: DesktopLanRelay, exit: () -> Unit, monitor: () -> Unit) : JPanel(BorderLayout()), AutoCloseable {
    private val serviceLock = Any()
    private var closed = false
    private var update: SwingWorker<Unit, Unit>? = null
    private val enabled = JCheckBox("Enable playback assistance", relay.settings.enabled)
    private val status = JLabel("Disabled")
    private val pair = JButton("Pair a device")
    private val forget = JButton("Forget paired devices")
    init {
        border = BorderFactory.createEmptyBorder(20, 20, 20, 20)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("Use this desktop to fetch live HLS streams for your paired devices."))
            add(Box.createVerticalStrut(12)); add(enabled); add(status)
            add(JLabel("Keep this computer awake. Closing the window keeps 無名 running when a tray is available."))
            add(JPanel().apply {
                add(pair); add(forget)
                add(JButton("Playback assistance monitor").apply { addActionListener { monitor() } })
                add(JButton("Exit 無名 and stop assistance").apply { addActionListener { exit() } })
            })
        }, BorderLayout.NORTH)
        pair.addActionListener {
            JOptionPane.showMessageDialog(this, "Pairing code: ${relay.pairingCode()}\nValid for two minutes and one device.\nOn the device: Settings → Desktop relay → Find desktop.", "Pair a device", JOptionPane.INFORMATION_MESSAGE)
            refresh()
        }
        forget.addActionListener { relay.forgetDevices(); refresh() }
        enabled.addActionListener { updateService(enabled.isSelected) }
        if (relay.settings.enabled) updateService(true) else refresh()
    }
    private fun refresh() {
        status.text = if (relay.running) "${relay.address} · ${relay.pairedDevices} paired device(s)" else "Disabled"
        pair.isEnabled = relay.running; forget.isEnabled = relay.pairedDevices > 0
    }
    private fun updateService(start: Boolean) {
        if (closed) return
        enabled.isEnabled = false; status.text = "Updating…"
        update = object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() { synchronized(serviceLock) { if (!closed) { if (start) relay.enable() else relay.disable() } } }
            override fun done() {
                if (closed) return
                update = null
                try { get() } catch (_: Exception) {
                    JOptionPane.showMessageDialog(this@LanRelayPreferencesPanel, "Could not start the relay. Check your LAN connection and whether another desktop relay is already running.")
                }
                enabled.isSelected = relay.running; enabled.isEnabled = true; refresh()
            }
        }.also { it.execute() }
    }
    override fun close() {
        synchronized(serviceLock) { closed = true; relay.close() }
        update?.cancel(true); update = null
    }
}
