package lin.server.service.info

import lin.LinApiConstants
import lin.server.util.OsUtils

class ServiceInfoProvider(
    private val versionProvider: VersionProvider
) {
    fun getVersion(): Int = LinApiConstants.SERVER_VERSION

    fun getUid(): Int = OsUtils.uid

    fun getSELinuxContext(): String? = OsUtils.sELinuxContext

    fun getVersionName(): String? = versionProvider.getVersionName()

    fun getVersionCode(): Int = versionProvider.getVersionCode()
}
