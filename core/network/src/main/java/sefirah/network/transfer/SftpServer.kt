package sefirah.network.transfer

import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.core.net.toUri
import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.session.SessionContext
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.scp.server.ScpCommandFactory
import org.apache.sshd.server.ServerBuilder
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.subsystem.SubsystemFactory
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import sefirah.common.util.DEFAULT_RECYCLE_BIN_PATH
import sefirah.common.util.checkStoragePermission
import sefirah.common.util.getPathFromTreeUri
import sefirah.domain.interfaces.DeviceManager
import sefirah.domain.model.SftpServerInfo
import sefirah.network.util.MediaStoreHelper
import sefirah.network.util.NetworkHelper.localAddress
import sefirah.network.util.SslHelper
import sefirah.network.util.generateRandomPassword
import java.nio.channels.Channel
import java.nio.channels.SeekableByteChannel
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.security.KeyPair
import java.security.PrivateKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SftpServer @Inject constructor(
    private val context: Context,
    private val deviceManager: DeviceManager,
) {
    private var sshd: SshServer? = null
    private var isRunning = false

    private var serverInfo: SftpServerInfo? = null


    private fun getRecycleBinDir(): Path {
        val configured = runBlocking {
            preferencesRepository.getRecycleBinLocation().first()
        }
        val path = if (configured.isEmpty()) {
            DEFAULT_RECYCLE_BIN_PATH
        } else {
            getPathFromTreeUri(configured.toUri())
        }
        return Paths.get(path)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @Throws(IOException::class)
    private fun moveToRecycleBin(path: Path) {
        val recycleBinDir = getRecycleBinDir()
        Files.createDirectories(recycleBinDir)
        val dateExpires = defaultTrashExpires()
        var dest = recycleBinDir.resolve(trashedFileName(path.fileName.toString(), dateExpires))
        var suffix = 0
        while (Files.exists(dest)) {
            dest = recycleBinDir.resolve(trashedFileName("${path.fileName}_$suffix", dateExpires))
            suffix++
        }
        Files.move(path, dest)
        Log.d(TAG, "Moved to recycle bin: $path -> $dest (expires=$dateExpires)")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun isInRecycleBin(path: Path): Boolean =
        path.normalize().startsWith(getRecycleBinDir().normalize())

    private class PfxKeyPairProvider : KeyPairProvider {
        private val keyPair: KeyPair = initializeKeyPair()

        private fun initializeKeyPair(): KeyPair {
            val keyStore = SslHelper.getKeyStore()
            val alias = keyStore.aliases().nextElement()
            val privateKey = keyStore.getKey(alias, null) as PrivateKey
            val cert = keyStore.getCertificate(alias)
            val publicKey = cert.publicKey

            return KeyPair(publicKey, privateKey)
        }

        override fun loadKeys(session: SessionContext?): Iterable<KeyPair> = listOf(keyPair)
    }

    fun initialize() {
        if (sshd != null) return
        if (!SUPPORTS_NATIVEFS) return

        val sshd = ServerBuilder.builder().apply {
            fileSystemFactory(NativeFileSystemFactory())
        }.build()


        sshd.commandFactory = ScpCommandFactory()
        sshd.subsystemFactories =
            listOf<SubsystemFactory>(SftpSubsystemFactory.Builder().apply {
                withFileSystemAccessor(object : SftpFileSystemAccessor {
                    fun notifyMediaStore(path: Path) {
                        runCatching {
                            val uri = path.toUri().toString().toUri()
                            MediaStoreHelper.indexFile(context, uri)
                            uri
                        }.fold(
                            onSuccess = { Log.i(TAG, "Notified media store: $path, $it") },
                            onFailure = { Log.w(TAG, "Failed to notify media store: $path", it) }
                        )
                    }

                    override fun openFile(
                        subsystem: SftpSubsystemProxy?,
                        fileHandle: FileHandle?,
                        file: Path?,
                        handle: String?,
                        options: MutableSet<out OpenOption>?,
                        vararg attrs: FileAttribute<*>?
                    ): SeekableByteChannel {
                        return super.openFile(subsystem, fileHandle, file, handle, options, *attrs)
                    }

                    override fun removeFile(
                        subsystem: SftpSubsystemProxy?,
                        path: Path?,
                        isDirectory: Boolean
                    ) {
                        path?.let {
                            if (isInRecycleBin(it)) {
                                super.removeFile(subsystem, it, isDirectory)
                            } else {
                                moveToRecycleBin(it)
                            }
                            notifyMediaStore(it)
                        }
                    }

                    override fun copyFile(
                        subsystem: SftpSubsystemProxy?,
                        src: Path?,
                        dst: Path?,
                        opts: MutableCollection<CopyOption>?
                    ) {
                        super.copyFile(subsystem, src, dst, opts)
                        dst?.let { notifyMediaStore(it) }
                    }

                    override fun renameFile(
                        subsystem: SftpSubsystemProxy?,
                        oldPath: Path?,
                        newPath: Path?,
                        opts: MutableCollection<CopyOption>?
                    ) {
                        super.renameFile(subsystem, oldPath, newPath, opts)
                        oldPath?.let { notifyMediaStore(it) }
                        newPath?.let { notifyMediaStore(it) }
                    }

                    override fun createLink(
                        subsystem: SftpSubsystemProxy?,
                        link: Path?,
                        existing: Path?,
                        symLink: Boolean
                    ) {
                        super.createLink(subsystem, link, existing, symLink)
                        link?.let { notifyMediaStore(it) }
                        existing?.let { notifyMediaStore(it) }
                    }

                    override fun closeFile(
                        subsystem: SftpSubsystemProxy?,
                        fileHandle: FileHandle?,
                        file: Path?,
                        handle: String?,
                        channel: Channel?,
                        options: MutableSet<out OpenOption>?
                    ) {
                        super.closeFile(subsystem, fileHandle, file, handle, channel, options)
                        if (options?.contains(StandardOpenOption.WRITE) == true) {
                            file?.let { notifyMediaStore(it) }
                        }
                    }
                })
            }.build())
        this.sshd = sshd
    }

    fun start() : SftpServerInfo? {
        if (isRunning) return serverInfo

        initialize()
        val server = sshd ?: return null

        val pwd = generateRandomPassword()
        val localDevice = deviceManager.localDevice
        val username = localDevice.deviceName

        val paths = mutableListOf<String>()
        val pathNames = mutableListOf<String>()
        val volumes = context.getSystemService(StorageManager::class.java).storageVolumes
        for (sv in volumes) {
            val dir = sv.directory ?: continue
            paths.add(dir.path)
            pathNames.add(sv.getDescription(context))
        }

        server.keyPairProvider = PfxKeyPairProvider()
        server.publickeyAuthenticator = PublickeyAuthenticator { _, _, _ -> true }
        server.passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
            user == username && password == pwd
        }

        PORT_RANGE.forEach { port ->
            try {
                server.port = port
                server.start()

                isRunning = true

                serverInfo = SftpServerInfo(
                    username = username,
                    password = pwd,
                    port = port,
                    paths = paths,
                    pathNames = pathNames
                )

                return serverInfo
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to start SFTP server on port $port", e)
                throw e
            }
        }
        return null
    }

    fun stop() {
        try {
            if (isRunning) {
                sshd?.stop(true)
                isRunning = false
                serverInfo = null
            }
            Log.d(TAG, "SFTP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop SFTP server", e)
        }
    }

    companion object {
        private const val TAG = "SftpServer"
        private const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        val SUPPORTS_NATIVEFS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        private val PORT_RANGE = 5151..5169

        private fun defaultTrashExpires(): Long =
            (System.currentTimeMillis() + TRASH_RETENTION_MS) / 1000

        private fun trashedFileName(displayName: String, dateExpires: Long): String =
            ".trashed-$dateExpires-$displayName"

        init {
            System.setProperty(SecurityUtils.SECURITY_PROVIDER_REGISTRARS, "") // disable BouncyCastle
            System.setProperty(
                "org.apache.sshd.common.io.IoServiceFactoryFactory",
                "org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory"
            )
            // Remove it when SSHD Core is fixed.
            // Android has no user home folder, so we need to set it to something.
            // `System.getProperty("user.home")` is not available on Android,
            // but it exists in SSHD Core's `org.apache.sshd.common.util.io.PathUtils.LazyDefaultUserHomeFolderHolder`.
            PathUtils.setUserHomeFolderResolver {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Path.of("/")
                } else {
                    Paths.get("/")
                }
            }
        }
    }
}