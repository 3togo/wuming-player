package com.wuming.android.tv.player.exo;

import static com.wuming.android.tv.player.exo.PlaybackBufferBudget.MIB;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PlaybackBufferBudgetTest {

    @Test
    public void smallHeapLeavesRoomForDecodingAndApplicationData() {
        assertEquals(16 * MIB, memory(128, 80, 1024, false, false).initialBytes());
        assertEquals(4 * MIB, memory(64, 60, 1024, false, false).initialBytes());
    }

    @Test
    public void plentifulSystemRamCannotOverrideTheAppsHeapLimit() {
        assertEquals(32 * MIB, memory(128, 16, 8192, false, false).initialBytes());
        assertEquals(96 * MIB, memory(1024, 64, 8192, false, false).initialBytes());
    }

    @Test
    public void lowRamDevicesAndSystemPressureLimitTheBuffer() {
        assertEquals(24 * MIB, memory(512, 32, 2048, true, false).initialBytes());
        assertEquals(8 * MIB, memory(512, 32, 96, false, false).initialBytes());
        assertEquals(4 * MIB, memory(512, 32, 2048, false, true).initialBytes());
    }

    @Test
    public void unknownSystemMemoryUsesHeapBudgetWithoutAssumingFreeRam() {
        PlaybackBufferBudget.Memory unknown = new PlaybackBufferBudget.Memory(
                256L * MIB, 64L * MIB, -1, 0, false, false);
        assertEquals(64 * MIB, unknown.initialBytes());
    }

    @Test
    public void pressureShrinksGraduallyAndRecoveryDoesNotCauseBufferOscillation() {
        PlaybackBufferBudget.Memory pressure = memory(256, 240, 512, false, false);
        int budget = pressure.constrainBytes(64 * MIB);
        assertEquals(32 * MIB, budget);
        assertEquals(budget, memory(256, 100, 512, false, false).constrainBytes(budget));
        for (int i = 0; i < 20; i++) budget = pressure.constrainBytes(budget);
        assertEquals(PlaybackBufferBudget.MIN_BYTES, budget);
    }

    @Test
    public void budgetsStayBoundedAcrossDeviceSizesAndHeadroom() {
        for (int heap = 32; heap <= 2048; heap *= 2) {
            for (int used = 0; used <= heap; used += 8) {
                int budget = memory(heap, used, 2048, false, false).initialBytes();
                assertTrue(budget >= PlaybackBufferBudget.MIN_BYTES);
                assertTrue(budget <= PlaybackBufferBudget.MAX_BYTES);
                assertTrue(budget <= (long) heap * MIB / 4);
            }
        }
    }

    private static PlaybackBufferBudget.Memory memory(int heap, int used, int available,
                                                      boolean lowRam, boolean lowMemory) {
        return new PlaybackBufferBudget.Memory((long) heap * MIB, (long) used * MIB,
                (long) available * MIB, 64L * MIB, lowRam, lowMemory);
    }
}
