package sefirah.communication.call

import android.content.Context
import sefirah.Feature
import sefirah.common.util.isCallLogsPermissionGranted
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.model.DevicePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallLogFeature @Inject constructor(
    private val context: Context,
    deviceManager: DeviceManager,
    private val networkManager: NetworkManager,
) : Feature(deviceManager) {

    override fun isPrefEnabled(prefs: DevicePreferences) = prefs.callLogSync

    override fun hasPermissions(): Boolean = isCallLogsPermissionGranted(context)

    override suspend fun onStart(deviceId: String) {
        CallLogHelper.getCallLogs(context).forEach { callLog ->
            networkManager.sendMessage(deviceId, callLog)
        }
    }
}
