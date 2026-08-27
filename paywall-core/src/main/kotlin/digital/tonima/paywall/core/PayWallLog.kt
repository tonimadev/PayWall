package digital.tonima.paywall.core

import android.util.Log

object PayWallLog {
    private const val TAG = "PayWallSDK"
    var isDebugEnabled: Boolean = false

    fun d(message: String) {
        if (isDebugEnabled) {
            Log.d(TAG, message)
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (isDebugEnabled) {
            Log.e(TAG, message, throwable)
        }
    }

    fun w(message: String) {
        if (isDebugEnabled) {
            Log.w(TAG, message)
        }
    }

    fun i(message: String) {
        if (isDebugEnabled) {
            Log.i(TAG, message)
        }
    }
}
