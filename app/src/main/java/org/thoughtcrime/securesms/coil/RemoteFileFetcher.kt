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
        // Demo/local avatar support: an Encrypted RemoteFile whose URL uses the "demo-avatar://"
        // scheme is a plain local image bundled by the conference-demo backup ZIP. We load it
        // directly from the cache dir, bypassing the file-server fetch+decrypt pipeline. The key
        // is non-empty (so libsession treats it as a valid profile picture) but never used here.
        if (file is RemoteFile.Encrypted && file.url.startsWith("demo-avatar://")) {
            return fetchDemoAvatar(file.url)
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

    private fun fetchDemoAvatar(url: String): FetchResult {
        val name = url.removePrefix("demo-avatar://")
        val file = java.io.File(context.cacheDir, "demo_avatars/$name")
        if (!file.exists()) throw RuntimeException("Demo avatar not found: $file")
        return SourceFetchResult(
            source = ImageSource(source = file.source().buffer(), fileSystem = FileSystem.SYSTEM, metadata = null),
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