package lin.server.service.process

import com.lin.server.IRemoteProcess
import com.lin.server.IRemotePtyProcess
import rikka.rish.RishConfig
import rikka.rish.RishConstants
import rikka.rish.RishHost
import lin.server.bootstrap.ServerBootstrap
import lin.server.ClientManager
import lin.server.api.RemoteProcessHolder
import lin.server.api.RemotePtyProcessHolder
import lin.server.shizuku.ShizukuApiConstants
import lin.server.util.Logger
import java.io.File
import java.io.IOException

class ProcessManager(
    private val clientManager: ClientManager
) {
    companion object {
        private val LOGGER = Logger("ProcessManager")
    }

    fun newProcess(
        uid: Int,
        pid: Int,
        cmd: Array<String?>,
        env: Array<String?>?,
        dir: String?
    ): IRemoteProcess {
        LOGGER.d(
            "newProcess: uid=$uid, cmd=${cmd.contentToString()}, env=${env.contentToString()}, dir=$dir"
        )

        val process: Process = try {
            Runtime.getRuntime().exec(cmd, env, if (dir != null) File(dir) else null)
        } catch (e: IOException) {
            throw IllegalStateException(e.message)
        }

        val clientRecord = clientManager.findClient(uid, pid)
        val token = clientRecord?.client?.asBinder()

        return RemoteProcessHolder(process, token)
    }

    fun newPtyProcess(
        uid: Int,
        pid: Int,
        cmd: Array<String?>,
        env: Array<String?>?,
        dir: String?
    ): IRemotePtyProcess {
        ServerBootstrap.managerApplicationInfo?.nativeLibraryDir?.let {
            RishConfig.setLibraryPath(it)
        }
        RishConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, 30000)
        val tty = (RishConstants.ATTY_IN or RishConstants.ATTY_OUT or RishConstants.ATTY_ERR).toByte()
        val host = RishHost(cmd.filterNotNull().toTypedArray(), env?.filterNotNull()?.toTypedArray(), dir ?: "", tty, null, null, null)
        host.start()
        val token = clientManager.findClient(uid, pid)?.client?.asBinder()
        return RemotePtyProcessHolder(host, token)
    }
}
