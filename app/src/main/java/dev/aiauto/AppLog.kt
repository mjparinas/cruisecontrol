package dev.aiauto

import android.util.Log
import dev.aiauto.data.ErrorStore

/**
 * Logs a caught error to Android's log and keeps it in [ErrorStore], so it shows on the Recent errors screen. Use it
 * instead of `Log.w` or `Log.e` for anything that went wrong.
 */
object AppLog {
    private const val TAG = "AiAuto"

    /** Set once the app starts. Errors before then only reach Android's log. */
    @Volatile
    var store: ErrorStore? = null

    fun w(where: String, error: Throwable? = null) {
        Log.w(TAG, where, error)
        record(where, error)
    }

    fun e(where: String, error: Throwable? = null) {
        Log.e(TAG, where, error)
        record(where, error)
    }

    // Recording an error must never cause another one.
    private fun record(where: String, error: Throwable?) {
        runCatching { store?.record(where, error) }
    }
}
