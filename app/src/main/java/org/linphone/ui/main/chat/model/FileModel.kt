/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ui.main.chat.model

import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.tools.Log
import org.linphone.utils.FileUtils
import org.linphone.utils.TimestampUtils

class FileModel @AnyThread constructor(
    val path: String,
    val fileName: String,
    val fileSize: Long,
    val fileCreationTimestamp: Long,
    val isEncrypted: Boolean,
    val originalPath: String,
    val isWaitingToBeDownloaded: Boolean = false,
    private val onClicked: ((model: FileModel) -> Unit)? = null
) {
    companion object {
        private const val TAG = "[File Model]"
    }

    val formattedFileSize = MutableLiveData<String>()

    val transferProgress = MutableLiveData<Int>()

    val mimeType: FileUtils.MimeType

    val mimeTypeString: String

    val isMedia: Boolean

    val isImage: Boolean

    val isVideoPreview: Boolean

    val audioVideoDuration = MutableLiveData<String>()

    val isPdf: Boolean

    val isAudio: Boolean

    val month = TimestampUtils.month(fileCreationTimestamp)

    val dateTime = TimestampUtils.toString(
        fileCreationTimestamp,
        shortDate = false,
        hideYear = false
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        transferProgress.postValue(-1)
        formattedFileSize.postValue(FileUtils.bytesToDisplayableSize(fileSize))

        if (!isWaitingToBeDownloaded) {
            val extension = FileUtils.getExtensionFromFileName(path)
            isPdf = extension == "pdf"

            val mime = FileUtils.getMimeTypeFromExtension(extension)
            mimeTypeString = mime

            mimeType = FileUtils.getMimeType(mime)
            isImage = mimeType == FileUtils.MimeType.Image
            isVideoPreview = mimeType == FileUtils.MimeType.Video
            isAudio = mimeType == FileUtils.MimeType.Audio
            if (isVideoPreview || isAudio) {
                getDuration()
            }
            Log.d(
                "$TAG File has already been downloaded, extension is [$extension], MIME is [$mime]"
            )
        } else {
            mimeType = FileUtils.MimeType.Unknown
            mimeTypeString = "application/octet-stream"
            isPdf = false
            isImage = false
            isVideoPreview = false
            isAudio = false
        }

        isMedia = isVideoPreview || isImage
    }

    @AnyThread
    fun destroy() {
        if (isEncrypted) {
            Log.i("$TAG [VFS] Deleting plain file in cache: $path")
            scope.launch {
                FileUtils.deleteFile(path)
            }
        }
    }

    @UiThread
    fun onClick() {
        onClicked?.invoke(this)
    }

    @AnyThread
    suspend fun deleteFile() {
        Log.i("$TAG Deleting file [$path]")
        FileUtils.deleteFile(path)
    }

    @AnyThread
    private fun getDuration() {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(coreContext.context, Uri.parse(path))
            val durationInMs = retriever.extractMetadata(METADATA_KEY_DURATION)?.toInt() ?: 0
            val seconds = durationInMs / 1000
            val duration = TimestampUtils.durationToString(seconds)
            Log.d("$TAG Duration for file [$path] is $duration")
            audioVideoDuration.postValue(duration)
            retriever.release()
        } catch (e: Exception) {
            Log.e("$TAG Failed to get duration for file [$path]: $e")
        }
    }
}
