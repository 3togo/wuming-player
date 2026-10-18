package com.wuming.tv.desktop.ui

import org.junit.Assert.*
import org.junit.Test

class DesktopTrayTest {
    private class Port : DesktopTray.Port {
        var installs = 0
        var removals = 0
        lateinit var show: () -> Unit
        lateinit var monitor: () -> Unit
        lateinit var exit: () -> Unit
        override fun install(show: () -> Unit, monitor: () -> Unit, exit: () -> Unit): AutoCloseable {
            installs++; this.show = show; this.monitor = monitor; this.exit = exit
            return AutoCloseable { removals++ }
        }
    }
    @Test fun closingAndReopeningMainWindowKeepsOneIconAndIndependentMonitor() {
        val port = Port()
        var visible = true
        var monitoring = false
        var exits = 0
        val tray = DesktopTray({ visible = true }, { monitoring = true }, { exits++ },
            { visible = false }, { fail("Working tray must not minimize") }, port)
        assertTrue(tray.install())
        repeat(10) {
            assertTrue(tray.keepInBackground(relayRunning = false))
            assertFalse(visible)
            port.monitor(); assertTrue(monitoring)
            port.show(); assertTrue(visible)
        }
        assertEquals(1, port.installs)
        assertEquals(0, port.removals)
        assertEquals(0, exits)
        port.exit(); assertEquals(1, exits)
        tray.close(); tray.close()
        assertEquals(1, port.removals)
        visible = false; monitoring = false
        port.show(); port.monitor(); port.exit()
        assertFalse("Queued tray actions cannot reopen exited windows", visible)
        assertFalse(monitoring)
        assertEquals(1, exits)
        assertFalse(tray.install())
    }
    @Test fun missingTrayKeepsRunningRelayReachableAndAllowsOrdinaryExitWhenDisabled() {
        var minimized = 0
        val tray = DesktopTray({}, {}, {}, { fail("Must not hide without a tray") }, { minimized++ },
            object : DesktopTray.Port { override fun install(show: () -> Unit, monitor: () -> Unit, exit: () -> Unit) = null })
        assertTrue(tray.keepInBackground(true))
        assertEquals(1, minimized)
        assertFalse(tray.keepInBackground(false))
        tray.close()
    }
    @Test fun failedRegistrationDoesNotHideWindowAndCanRecover() {
        val port = Port()
        var broken = true
        var hidden = false
        val tray = DesktopTray({}, {}, {}, { hidden = true }, {}, object : DesktopTray.Port {
            override fun install(show: () -> Unit, monitor: () -> Unit, exit: () -> Unit): AutoCloseable {
                if (broken) throw IllegalStateException("Desktop tray unavailable")
                return port.install(show, monitor, exit)
            }
        })
        assertFalse(tray.keepInBackground(false)); assertFalse(hidden)
        broken = false
        assertTrue(tray.keepInBackground(false)); assertTrue(hidden)
        tray.close(); assertEquals(1, port.removals)
    }
}
