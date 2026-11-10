package project.kosh.core.crypto

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Thread-safe, persistent Lamport logical clock counter.
 *
 * Provides monotonically increasing logical timestamps for Delta-CRDT causality tracking:
 * - Local action: tick() = localClock + 1
 * - Remote action: update(remoteClock) = max(localClock, remoteClock) + 1
 */
@Singleton
class LamportClockManager internal constructor(
    context: Context? = null,
    initialClock: Long? = null,
    overrideDeviceId: String? = null
) {
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val clock = AtomicLong(initialClock ?: prefs?.getLong(KEY_CLOCK, 0L) ?: 0L)
    private val deviceId: String = overrideDeviceId
        ?: prefs?.getString(KEY_DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also { newId ->
            prefs?.edit()?.putString(KEY_DEVICE_ID, newId)?.apply()
        }

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        initialClock = null,
        overrideDeviceId = null
    )

    /**
     * Testing constructor allowing custom initial clock and device ID without Android Context.
     */
    constructor(initialClock: Long, testDeviceId: String = "test-device-id") : this(
        context = null,
        initialClock = initialClock,
        overrideDeviceId = testDeviceId
    )

    fun getClock(): Long = clock.get()

    fun getDeviceId(): String = deviceId

    /**
     * Increments local Lamport clock for local mutations: localClock + 1.
     */
    fun tick(): Long {
        val next = clock.incrementAndGet()
        prefs?.edit()?.putLong(KEY_CLOCK, next)?.apply()
        return next
    }

    /**
     * Advances local Lamport clock upon observing remote clock: max(localClock, remoteClock) + 1.
     */
    fun update(remoteClock: Long): Long {
        while (true) {
            val current = clock.get()
            val next = max(current, remoteClock) + 1
            if (clock.compareAndSet(current, next)) {
                prefs?.edit()?.putLong(KEY_CLOCK, next)?.apply()
                return next
            }
        }
    }

    fun reset(value: Long = 0L) {
        clock.set(value)
        prefs?.edit()?.putLong(KEY_CLOCK, value)?.apply()
    }

    companion object {
        const val PREFS_NAME = "kosh_lamport_clock_prefs"
        const val KEY_CLOCK = "lamport_clock"
        const val KEY_DEVICE_ID = "device_id"
    }
}
