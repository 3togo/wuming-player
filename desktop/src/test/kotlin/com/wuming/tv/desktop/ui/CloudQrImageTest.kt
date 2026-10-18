package com.wuming.tv.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudQrImageTest {

    @Test
    fun rendersHttpsLoginChallenge() {
        val image = CloudQrImage.render("https://login.example/qr?id=test", 96)

        assertEquals(96, image.width)
        assertEquals(96, image.height)
    }
}
