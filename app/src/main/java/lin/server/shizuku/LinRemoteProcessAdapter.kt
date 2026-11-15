package lin.server.shizuku

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.lin.server.IRemoteProcess as ILinRemoteProcess
import moe.shizuku.server.IRemoteProcess as IShizukuRemoteProcess

class LinRemoteProcessAdapter(
    private val linProcess: ILinRemoteProcess
) : IShizukuRemoteProcess.Stub() {

    override fun getOutputStream(): ParcelFileDescriptor? = linProcess.outputStream

    override fun getInputStream(): ParcelFileDescriptor? = linProcess.inputStream

    override fun getErrorStream(): ParcelFileDescriptor? = linProcess.errorStream

    override fun waitFor(): Int = linProcess.waitFor()

    override fun exitValue(): Int = linProcess.exitValue()

    override fun destroy() = linProcess.destroy()

    @Throws(RemoteException::class)
    override fun alive(): Boolean = linProcess.alive()

    @Throws(RemoteException::class)
    override fun waitForTimeout(timeout: Long, unitName: String?): Boolean =
        linProcess.waitForTimeout(timeout, unitName)
}
