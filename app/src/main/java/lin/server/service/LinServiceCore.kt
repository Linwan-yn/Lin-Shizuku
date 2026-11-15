package lin.server.service

import lin.server.ClientManager
import lin.server.ConfigManager
import lin.server.service.info.ServiceInfoProvider
import lin.server.service.info.VersionProvider
import lin.server.service.log.LogManager
import lin.server.service.permission.PermissionManager
import lin.server.service.process.ProcessManager
import lin.server.service.system.SystemPropertyManager
import lin.server.service.userservice.UserServiceCoordinator
import lin.server.userservice.UserServiceManager

class LinServiceCore(
    private val clientManager: ClientManager,
    private val configManager: ConfigManager,
    private val userServiceManager: UserServiceManager
) {
    val serviceInfo: ServiceInfoProvider
    val permissionManager: PermissionManager
    val processManager: ProcessManager
    val systemPropertyManager: SystemPropertyManager
    val logManager: LogManager
    val userServiceCoordinator: UserServiceCoordinator

    init {
        val versionProvider = VersionProvider()
        serviceInfo = ServiceInfoProvider(versionProvider)
        permissionManager = PermissionManager(clientManager, configManager)
        processManager = ProcessManager(clientManager)
        systemPropertyManager = SystemPropertyManager()
        logManager = LogManager(clientManager)
        userServiceCoordinator = UserServiceCoordinator(userServiceManager)
    }
}
