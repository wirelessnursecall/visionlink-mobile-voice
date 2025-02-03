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
package org.linphone.ui.file_viewer.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.core.tools.Log
import org.linphone.ui.main.chat.model.FileModel
import org.linphone.ui.main.chat.viewmodel.AbstractConversationViewModel
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils

class MediaListViewModel @UiThread constructor() : AbstractConversationViewModel() {
    companion object {
        private const val TAG = "[Media List ViewModel]"
    }

    val mediaList = MutableLiveData<List<FileModel>>()

    val currentlyDisplayedFileName = MutableLiveData<String>()

    val currentlyDisplayedFileDateTime = MutableLiveData<String>()

    override fun beforeNotifyingChatRoomFound(sameOne: Boolean) {
        loadMediaList()
    }

    override fun onCleared() {
        super.onCleared()

        mediaList.value.orEmpty().forEach(FileModel::destroy)
    }

    @UiThread
    fun initTempModel(path: String, timestamp: Long, isEncrypted: Boolean, originalPath: String) {
        val name = FileUtils.getNameFromFilePath(path)
        val model = FileModel(path, name, 0, timestamp, isEncrypted, originalPath)
        mediaList.postValue(arrayListOf(model))
    }

    @WorkerThread
    private fun loadMediaList() {
        val list = arrayListOf<FileModel>()
        val chatRoomId = LinphoneUtils.getChatRoomId(chatRoom)
        Log.i("$TAG Loading media contents for conversation [$chatRoomId]")

        val media = chatRoom.mediaContents
        Log.i("$TAG [${media.size}] media have been fetched")
        for (mediaContent in media) {
            // Do not display voice recordings here, even if they are media file
            if (mediaContent.isVoiceRecording) continue

            val isEncrypted = mediaContent.isFileEncrypted
            val originalPath = mediaContent.filePath.orEmpty()
            val path = if (isEncrypted) {
                Log.d(
                    "$TAG [VFS] Content is encrypted, requesting plain file path for file [${mediaContent.filePath}]"
                )
                mediaContent.exportPlainFile()
            } else {
                originalPath
            }
            val name = mediaContent.name.orEmpty()
            val size = mediaContent.size.toLong()
            val timestamp = mediaContent.creationTimestamp
            if (path.isNotEmpty() && name.isNotEmpty()) {
                val model = FileModel(path, name, size, timestamp, isEncrypted, originalPath)
                list.add(model)
            }
        }
        Log.i("$TAG [${list.size}] media have been processed")
        mediaList.postValue(list)
    }
}
