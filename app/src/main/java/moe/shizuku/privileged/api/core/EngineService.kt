package moe.shizuku.privileged.api.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/**
 * Shizuku Engine Service — provides a binding interface for status queries.
 * The actual Shizuku binder is delivered via LinShizukuProvider (ContentProvider).
 */
class EngineService : Service() {

    private val binder = EngineBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    inner class EngineBinder : Binder() {
        fun getService(): EngineService = this@EngineService

        fun isShizukuRunning(): Boolean = ShizukuManager.getStatus() == ShizukuManager.Status.RUNNING

        fun getServerVersion(): Int = ShizukuManager.getServerVersion()

        fun getServerUid(): Int = ShizukuManager.getServerUid()
    }
}
