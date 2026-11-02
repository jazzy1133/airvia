package com.opus.airvia

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge for HAP PIN entry: the cast engine (background thread) needs a
 * code the user reads off the receiver's screen; the activity shows the
 * dialog. One request at a time; the engine blocks with a timeout so a
 * forgotten dialog cannot wedge a cast thread forever. The PIN passes
 * through here and is never logged or persisted.
 */
object PinRequestBus {
    @Volatile
    var pendingSpeaker: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()
    private var latch: CountDownLatch? = null
    private val answer = AtomicReference<String?>(null)

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun fire() {
        for (l in listeners) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Ask the UI for the code shown on [speakerName]'s screen. Blocks the
     * calling (engine) thread until the user submits/cancels or
     * [timeoutMs] elapses. Returns the entered code, or null on
     * cancel/timeout/no-UI.
     */
    fun requestPin(speakerName: String, timeoutMs: Long = 180_000): String? {
        val myLatch: CountDownLatch
        synchronized(lock) {
            if (pendingSpeaker != null) return null // one prompt at a time
            answer.set(null)
            myLatch = CountDownLatch(1)
            latch = myLatch
            pendingSpeaker = speakerName
        }
        fire()
        val done = try {
            myLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        synchronized(lock) {
            if (latch === myLatch) {
                latch = null
                pendingSpeaker = null
            }
        }
        fire()
        return if (done) answer.get() else null
    }

    /** UI side: deliver the entered code, or null for Cancel/dismiss. */
    fun submit(pin: String?) {
        val l: CountDownLatch?
        synchronized(lock) {
            if (pendingSpeaker == null) return
            answer.set(pin)
            l = latch
        }
        l?.countDown()
    }
}
