package com.wuming.android.tv.cloud.opendrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.function.Supplier

class OpenDrivePreparedExternalTest {

    @Test
    fun `materialize returns value from supplier`() {
        val prepared = OpenDrivePreparedExternal.of(Supplier { "item123" })
        assertEquals("item123", prepared.materialize())
    }

    @Test
    fun `materialize caches result on first call`() {
        var callCount = 0
        val prepared = OpenDrivePreparedExternal.of(Supplier {
            callCount++
            "item_$callCount"
        })
        assertEquals("item_1", prepared.materialize())
        assertEquals("item_1", prepared.materialize())
        assertEquals("item_1", prepared.materialize())
        assertEquals(1, callCount)
    }

    @Test
    fun `materialize throws when supplier returns blank`() {
        val prepared = OpenDrivePreparedExternal.of(Supplier { "" })
        assertThrows(IllegalArgumentException::class.java) {
            prepared.materialize()
        }
    }

    @Test
    fun `materialize throws when supplier returns whitespace only`() {
        val prepared = OpenDrivePreparedExternal.of(Supplier { "   " })
        assertThrows(IllegalArgumentException::class.java) {
            prepared.materialize()
        }
    }

    @Test
    fun `of throws NullPointerException when supplier is null`() {
        assertThrows(NullPointerException::class.java) {
            OpenDrivePreparedExternal.of(null!!)
        }
    }

    @Test
    fun `materialize is thread safe and only calls supplier once`() {
        val callCount = java.util.concurrent.atomic.AtomicInteger(0)
        val prepared = OpenDrivePreparedExternal.of(Supplier {
            callCount.incrementAndGet()
            Thread.sleep(50)
            "item"
        })
        val threads = (1..10).map {
            Thread { assertEquals("item", prepared.materialize()) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, callCount.get())
    }
}
