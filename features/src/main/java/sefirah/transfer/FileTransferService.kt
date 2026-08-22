package sefirah.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sefirah.clipboard.ClipboardFeature
import sefirah.common.notifications.NotificationCenter
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.interfaces.NetworkManager
import sefirah.domain.interfaces.PreferencesRepository
import sefirah.domain.interfaces.SocketFactory
import sefirah.domain.model.FileMetadata
import sefirah.domain.model.FileTransferInfo
import sefirah.domain.model.ServerInfo
import sefirah.transfer.util.getFileMetadata
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileTransferService @Inject constructor(
    private val context: Context,
    private val socketFactory: SocketFactory,
    private val deviceManager: DeviceManager,
    private val preferencesRepository: PreferencesRepository,
    private val notificationCenter: NotificationCenter,
    private val networkManager: NetworkManager,
    private val clipboardFeature: ClipboardFeature,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTransfers = ConcurrentHashMap<String, Job>()

    fun sendFiles(deviceId: String, fileUris: List<Uri>, isClipboard: Boolean = false) {
        val files = fileUris.map { uri ->
            val metadata = getFileMetadata(context, uri)
            TransferSource(metadata) {
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Failed to open $uri")
            }
        }
        send(deviceId, files, isClipboard)
    }

    /**
     * Stream [pfd] to [deviceId]. Takes ownership of [pfd] and closes it when the transfer finishes.
     */
    fun sendFromPfd(
        deviceId: String,
        pfd: ParcelFileDescriptor,
        metadata: FileMetadata,
        isClipboard: Boolean = false,
    ) {
        val files = listOf(
            TransferSource(metadata) {
                FileInputStream(pfd.fileDescriptor)
            },
        )
        send(deviceId, files, isClipboard, onFinished = {
            try {
                pfd.close()
            } catch (_: Exception) {
            }
        })
    }

    private fun send(
        deviceId: String,
        files: List<TransferSource>,
        isClipboard: Boolean,
        onFinished: (() -> Unit)? = null,
    ) {
        val transferId = UUID.randomUUID().toString()
        val filesMetadata = files.map { it.metadata }

        val job = scope.launch {
            try {
                val device = deviceManager.getPairedDevice(deviceId)
                    ?: throw IOException("Device $deviceId not found")

                val serverSocket = socketFactory.tcpServerSocket(PORT_RANGE, device.certificate)
                    ?: throw IOException("Failed to create server socket")

                val serverInfo = ServerInfo(serverSocket.localPort)

                val handler = SendFileHandler(
                    context = context,
                    transferId = transferId,
                    serverSocket = serverSocket,
                    files = files,
                    deviceName = device.deviceName,
                    notificationCenter = if (isClipboard) null else notificationCenter,
                )

                networkManager.sendMessage(
                    deviceId,
                    FileTransferInfo(
                        files = filesMetadata,
                        serverInfo = serverInfo,
                        isClipboard = isClipboard,
                    ),
                )
                handler.send()
            } catch (e: CancellationException) {
                Log.d(TAG, "Transfer $transferId cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Send files failed", e)
            } finally {
                activeTransfers.remove(transferId)
                onFinished?.invoke()
            }
        }
        activeTransfers[transferId] = job
    }

    fun receiveFiles(deviceId: String, transfer: FileTransferInfo) {
        val transferId = UUID.randomUUID().toString()

        val job = scope.launch {
            try {
                val device = deviceManager.getPairedDevice(deviceId)
                    ?: throw IOException("Device $deviceId not found")

                val address = device.address
                    ?: throw IOException("No connected address for device $deviceId")

                val clientSocket = socketFactory.tcpClientSocket(address, transfer.serverInfo.port, device.certificate)
                    ?: throw IOException("Failed to establish connection")

                val handler = ReceiveFileHandler(
                    context = context,
                    transferId = transferId,
                    clientSocket = clientSocket,
                    files = transfer.files,
                    deviceName = device.deviceName,
                    preferencesRepository = if (transfer.isClipboard) null else preferencesRepository,
                    notificationCenter = if (transfer.isClipboard) null else notificationCenter,
                )

                val fileUri = handler.receive() ?: return@launch
                if (transfer.isClipboard ||
                    preferencesRepository.readImageClipboardSettingsForDevice(deviceId).first()
                ) {
                    clipboardFeature.setClipboardUri(fileUri)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Transfer $transferId cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Receive files failed", e)
            } finally {
                activeTransfers.remove(transferId)
            }
        }
        activeTransfers[transferId] = job
    }

    fun cancelTransfer(transferId: String) {
        activeTransfers[transferId]?.cancel()
        activeTransfers.remove(transferId)
    }

    companion object {
        private const val TAG = "FileTransferManager"
        val PORT_RANGE = 5152..5169
        const val ACTION_CANCEL_TRANSFER = "CANCEL_TRANSFER"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"
    }
}
