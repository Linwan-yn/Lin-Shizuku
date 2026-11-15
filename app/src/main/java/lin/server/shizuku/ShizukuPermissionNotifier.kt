package lin.server.shizuku

fun interface ShizukuPermissionNotifier {
    fun notifyPermissionResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean)
}
