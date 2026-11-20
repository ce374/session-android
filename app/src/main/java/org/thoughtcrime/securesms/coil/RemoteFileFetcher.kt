package org.thoughtcrime.securesms.coil

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import okio.FileSystem
import okio.buffer
import okio.source
import org.session.libsession.utilities.recipients.RemoteFile
import org.thoughtcrime.securesms.attachments.AvatarDownloadManager

class RemoteFileFetcher @AssistedInject constructor(
    @Assisted private val file: RemoteFile,
    @Assisted private val options: Options,
    @param:ApplicationContext private val context: Context,
    private val avatarDownloadManager: AvatarDownloadManager,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        // Demo/local avatar support: an Encrypted RemoteFile with an empty key is treated as a
        // plain (unencrypted) image whose URL is a file:// or content:// URI. This lets the
        // conference-demo backup bundle real avatar images and display them offline, bypassing
        // the file-server fetch+decrypt pipeline.
        if (file is RemoteFile.Encrypted && file.key.data.isEmpty()) {
            return fetchPlainAvatar(file.url)
        }

        val downloadedFile = AvatarDownloadManager.computeFileName(context, file)

        // Check if the file already exists in the local storage, otherwise enqueue a download and
        // wait for it to complete.
        val dataSource = when {
            downloadedFile.exists() -> DataSource.DISK
            options.networkCachePolicy == CachePolicy.ENABLED -> DataSource.NETWORK
            else -> {
                throw RuntimeException("RemoteFile doesn't exist locally and we aren't allowed to download" +
                        "from network")
            }
        }

        return SourceFetchResult(
            source = ImageSource(
                source = avatarDownloadManager.download(file).source().buffer(),
                fileSystem = FileSystem.SYSTEM,
                metadata = null
            ),
            mimeType = null,
            dataSource = dataSource
        )
    }

    private fun fetchPlainAvatar(url: String): FetchResult {
        val uri = android.net.Uri.parse(url)
        val source = when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path ?: throw RuntimeException("No path in file URI: $url")
                java.io.File(path).source().buffer()
            }
            "content" -> {
                context.contentResolver.openInputStream(uri)?.source()?.buffer()
                    ?: throw RuntimeException("Cannot open content URI: $url")
            }
            else -> throw RuntimeException("Unsupported plain avatar scheme: $url")
        }
        return SourceFetchResult(
            source = ImageSource(source = source, fileSystem = FileSystem.SYSTEM, metadata = null),
            mimeType = null,
            dataSource = DataSource.DISK
        )
    }

    @AssistedFactory
    abstract class Factory : Fetcher.Factory<RemoteFile> {
        abstract fun create(remoteFile: RemoteFile, options: Options): RemoteFileFetcher

        override fun create(
            data: RemoteFile,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            return create(data, options)
        }
    }
}