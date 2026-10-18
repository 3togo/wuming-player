package com.wuming.tv.desktop.ui

import com.wuming.tv.desktop.server.RelayMonitorSnapshot
import com.wuming.tv.desktop.server.RelaySessionSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.awt.Container
import javax.swing.*

class PlaybackAssistanceMonitorTest {
    @Test fun liveSnapshotsUpdateRowsAndClosingStopsTimerAndDropsSessionData() {
        SwingUtilities.invokeAndWait {
            var reads = 0
            var value = RelayMonitorSnapshot(true, 1, 1, 2048, 1,
                listOf(RelaySessionSnapshot(1, "192.168.1.50", 1, 3, 2048, 1, 0)))
            val panel = PlaybackAssistanceMonitorPanel { reads++; value }
            fun descendants(parent: Container): List<java.awt.Component> = parent.components.flatMap {
                listOf(it) + if (it is Container) descendants(it) else emptyList()
            }
            val table = descendants(panel).filterIsInstance<JTable>().single()
            val timer = panel.javaClass.getDeclaredField("timer").apply { isAccessible = true }.get(panel) as Timer
            assertEquals(1, table.rowCount)
            assertEquals("Transferring", table.getValueAt(0, 1))
            assertEquals("2.0 KiB", table.getValueAt(0, 2))
            assertFalse(table.isCellEditable(0, 0))
            panel.addNotify(); assertTrue(timer.isRunning)
            value = value.copy(preparing = 0, sessions = value.sessions.map { it.copy(activeRequests = 0, idleMillis = 3000) })
            panel.refresh()
            assertEquals("Waiting", table.getValueAt(0, 1))
            assertEquals("3s ago", table.getValueAt(0, 6))
            value = value.copy(enabled = false, sessions = emptyList())
            panel.refresh(); assertEquals(0, table.rowCount)
            assertTrue(descendants(panel).filterIsInstance<JLabel>().any { it.text.startsWith("Assistance disabled") })
            panel.removeNotify(); assertFalse(timer.isRunning)
            panel.close()
            val before = reads
            panel.refresh(); panel.addNotify()
            assertEquals(before, reads)
            assertFalse(timer.isRunning)
            assertEquals(0, table.rowCount)
            panel.removeNotify()
        }
    }
}
