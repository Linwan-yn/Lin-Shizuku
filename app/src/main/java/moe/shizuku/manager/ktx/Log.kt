package moe.shizuku.manager.ktx

import android.util.Log

fun Any.logd(msg: String) {
    Log.d(this::class.java.simpleName, msg)
}
