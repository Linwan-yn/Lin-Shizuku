package rikka.core.util

import android.os.Build

object BuildUtils {
    val atLeast29: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
