package project.kosh.core.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class LamportClockManagerTest {

    @Test
    fun tick_incrementsMonotonically() {
        val manager = LamportClockManager(initialClock = 10L, testDeviceId = "dev-1")
        assertEquals(10L, manager.getClock())
        assertEquals("dev-1", manager.getDeviceId())
        assertEquals(11L, manager.tick())
        assertEquals(12L, manager.tick())
        assertEquals(13L, manager.tick())
        assertEquals(13L, manager.getClock())
        assertEquals("dev-1", manager.getDeviceId())
    }

    @Test
    fun update_advancesBeyondRemoteClock() {
        val manager = LamportClockManager(initialClock = 10L, testDeviceId = "dev-1")
        assertEquals("dev-1", manager.getDeviceId())

        // Case 1: remote clock is higher (50) -> clock becomes 51
        val updated1 = manager.update(50L)
        assertEquals(51L, updated1)
        assertEquals(51L, manager.getClock())

        // Case 2: remote clock is lower (20) -> clock becomes max(51, 20) + 1 = 52
        val updated2 = manager.update(20L)
        assertEquals(52L, updated2)
        assertEquals(52L, manager.getClock())
    }

    @Test
    fun threadSafety_concurrentTicks() {
        val manager = LamportClockManager(initialClock = 0L, testDeviceId = "dev-1")
        val threadCount = 20
        val ticksPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until ticksPerThread) {
                        manager.tick()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals((threadCount * ticksPerThread).toLong(), manager.getClock())
    }

    @Test
    fun persistence_withAndroidContext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager1 = LamportClockManager(context)
        val devId = manager1.getDeviceId()
        assertNotNull(devId)
        assertTrue(devId.isNotBlank())

        manager1.reset(100L)
        manager1.tick() // 101
        manager1.update(200L) // 201

        // Instantiate manager2 with same context -> should recover state
        val manager2 = LamportClockManager(context)
        assertEquals(201L, manager2.getClock())
        assertEquals(devId, manager2.getDeviceId())
    }
}
