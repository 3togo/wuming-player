package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.server.DesktopLanRelay
import com.wuming.tv.desktop.server.DesktopLanRelaySettings
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import javax.swing.SwingUtilities
import javax.swing.SwingWorker

class LanRelayPreferencesPanelTest {
    @Test fun exitWhileStartupIsQueuedCannotLeaveRelayRunning() {
        val directory = Files.createTempDirectory("relay-preferences-lifecycle-test")
        val relay = DesktopLanRelay(DesktopLanRelaySettings(directory.resolve("settings")).apply { enabled = true })
        try {
            SwingUtilities.invokeAndWait {
                val panel = LanRelayPreferencesPanel(relay, {}, {})
                val worker = panel.javaClass.getDeclaredField("update").apply { isAccessible = true }.get(panel) as SwingWorker<*, *>
                panel.close()
                assertTrue(worker.isDone)
                assertFalse(relay.running)
            }
            // Flush the worker's queued EDT completion after shutdown.
            SwingUtilities.invokeAndWait { assertFalse(relay.running) }
        } finally {
            relay.close()
            Files.walk(directory).sorted(Comparator.reverseOrder()).use { it.forEach(Files::delete) }
        }
    }
}
