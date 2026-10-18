package com.wuming.tv.desktop.ui

import com.wuming.tv.core.media.TvHomeAction
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvHomeNavigationPanelTest {

    @Test
    fun `renders leanback ordered focus tiles and routes by stable action`() {
        val selected = AtomicReference<TvHomeAction>()

        SwingUtilities.invokeAndWait {
            val panel = TvHomeNavigationPanel(onSelected = selected::set)
            val buttons = panel.components.filterIsInstance<JToggleButton>()

            assertEquals(
                listOf("Discover", "Live", "Search", "My library", "Cast & open", "Settings", "Accounts"),
                buttons.map { it.accessibleContext.accessibleName },
            )
            assertEquals(184, buttons.first().preferredSize.width)
            buttons[1].doClick()
            assertEquals(TvHomeAction.LIVE_TV, selected.get())
            assertTrue(buttons[1].isSelected)
        }
    }
}
