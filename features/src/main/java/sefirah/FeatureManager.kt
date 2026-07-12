package sefirah

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.model.DevicePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns feature lifecycle for the app.
 */
@Singleton
class FeatureManager @Inject constructor(
    private val features: Set<@JvmSuppressWildcards Feature>,
    private val deviceManager: DeviceManager,
    private val preferencesRepository: PreferencesRepository,
) {
    private val mutex = Mutex()

    /** Connected: enable features whose prefs + permissions allow. */
    suspend fun onConnect(deviceId: String) = mutex.withLock {
        apply(deviceId, preferencesRepository.preferenceSettings(deviceId).first())
    }

    /** Disconnected: disable every feature for [deviceId]. */
    suspend fun onDisconnect(deviceId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            features.forEach { it.disable(deviceId) }
        }
    }

    /** Prefs changed; if connected, re-apply enable/disable for [deviceId]. */
    suspend fun onPreferencesChanged(deviceId: String) = mutex.withLock {
        val connected = deviceManager.pairedDevices.value
            .any { it.deviceId == deviceId && it.connectionState.isConnected }
        if (!connected) return@withLock

        apply(deviceId, preferencesRepository.preferenceSettings(deviceId).first())
    }

    private suspend fun apply(deviceId: String, prefs: DevicePreferences) {
        withContext(Dispatchers.IO) {
            features.forEach { feature ->
                if (feature.isPrefEnabled(prefs) && feature.hasPermissions()) {
                    feature.enable(deviceId)
                } else {
                    feature.disable(deviceId)
                }
            }
        }
    }
}
