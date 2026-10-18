package com.wuming.android.tv.utils

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.*
import org.junit.Test

class LifecycleDelayedActionTest {
    @Test fun destinationDestructionCancelsTheSameRunnableAndSuppressesLateDispatch() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        var callback: Runnable? = null
        var removed: Runnable? = null
        var calls = 0
        LifecycleDelayedAction.schedule(owner.lifecycle, Runnable { calls++ }, { callback = it }, { removed = it })
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertSame(callback, removed)
        callback!!.run()
        assertEquals(0, calls)
    }

    @Test fun normalHandoffRunsOnceWhileDestinationRemainsAlive() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        owner.registry.currentState = Lifecycle.State.CREATED
        var callback: Runnable? = null
        var calls = 0
        LifecycleDelayedAction.schedule(owner.lifecycle, Runnable { calls++ }, { callback = it }, { error("already complete") })
        val scheduled = requireNotNull(callback)
        scheduled.run()
        scheduled.run()
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertEquals(1, calls)
    }
}
