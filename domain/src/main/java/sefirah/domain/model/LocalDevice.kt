package sefirah.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LocalDevice(
    val deviceId: String,
    val deviceName: String,
    val model: String,
    val publicKey: String,
    val privateKey: String,
) : Parcelable