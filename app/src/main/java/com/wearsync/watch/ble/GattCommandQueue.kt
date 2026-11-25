package com.wearsync.watch.ble

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

/**
 * Thread-safe serialized queue for Android BluetoothGatt asynchronous operations.
 * Executes every GATT call on the main looper sequentially, waiting for the
 * corresponding callback (or timeout) before starting the next operation.
 */
class GattCommandQueue(
    private val scope: CoroutineScope
) {
    private val TAG = "GattCommandQueue"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val queue = ArrayDeque<QueuedOp>()
    private var inFlight: QueuedOp? = null
    private var timeoutJob: Job? = null
    private var queueGeneration: Long = 0L

    private data class QueuedOp(
        val name: String,
        val timeoutMs: Long,
        val action: () -> Boolean,
        val onResult: ((Int) -> Unit)? = null
    )

    fun enqueue(
        name: String,
        timeoutMs: Long = 6_000L,
        onResult: ((Int) -> Unit)? = null,
        action: () -> Boolean
    ) {
        synchronized(lock) {
            Log.d(TAG, "enqueue('$name') inFlight=${inFlight?.name} qSize=${queue.size}")
            queue.addLast(QueuedOp(name, timeoutMs, action, onResult))
        }
        mainHandler.post { tryDispatchNext() }
    }

    fun completeCurrent(status: Int = 0) {
        val finishedCallback: ((Int) -> Unit)?
        synchronized(lock) {
            timeoutJob?.cancel()
            timeoutJob = null
            Log.d(TAG, "completeCurrent('${inFlight?.name}', status=$status) qSize=${queue.size}")
            finishedCallback = inFlight?.onResult
            inFlight = null
        }
        finishedCallback?.invoke(status)
        mainHandler.post { tryDispatchNext() }
    }

    fun clear() {
        synchronized(lock) {
            queueGeneration += 1L
            timeoutJob?.cancel()
            timeoutJob = null
            inFlight = null
            queue.clear()
            Log.d(TAG, "queue cleared")
        }
    }

    private fun tryDispatchNext() {
        while (true) {
            val gen: Long
            val next = synchronized(lock) {
                if (inFlight != null || queue.isEmpty()) return
                val op = queue.removeFirst()
                inFlight = op
                gen = queueGeneration
                op
            }

            Log.d(TAG, "dispatching '${next.name}'")
            val started = try {
                next.action()
            } catch (e: Exception) {
                Log.e(TAG, "GATT op '${next.name}' threw exception", e)
                false
            }

            if (!started) {
                Log.w(TAG, "GATT op '${next.name}' failed to start immediately")
                val callback = synchronized(lock) {
                    if (inFlight === next && queueGeneration == gen) {
                        inFlight = null
                        next.onResult
                    } else {
                        null
                    }
                }
                callback?.invoke(-1)
                continue
            }

            synchronized(lock) {
                if (inFlight === next && queueGeneration == gen) {
                    timeoutJob?.cancel()
                    timeoutJob = scope.launch {
                        delay(next.timeoutMs)
                        val timedOut = synchronized(lock) {
                            if (inFlight === next && queueGeneration == gen) {
                                Log.w(TAG, "GATT op '${next.name}' timed out after ${next.timeoutMs}ms")
                                inFlight = null
                                true
                            } else false
                        }
                        if (timedOut) {
                            next.onResult?.invoke(-2)
                            mainHandler.post { tryDispatchNext() }
                        }
                    }
                }
            }
            return
        }
    }
}
