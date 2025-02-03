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
package org.linphone.utils

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import androidx.annotation.AnyThread
import androidx.annotation.DrawableRes
import androidx.annotation.IntegerRes
import androidx.annotation.WorkerThread
import androidx.core.text.toSpannable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.contacts.getListOfSipAddresses
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.Call
import org.linphone.core.Call.Dir
import org.linphone.core.Call.Status
import org.linphone.core.CallLog
import org.linphone.core.ChatMessage
import org.linphone.core.ChatRoom
import org.linphone.core.ConferenceInfo
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.Friend
import org.linphone.core.Reason
import org.linphone.core.tools.Log
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.model.isEndToEndEncryptionMandatory

class LinphoneUtils {
    companion object {
        private const val TAG = "[Linphone Utils]"

        const val RECORDING_FILE_NAME_HEADER = "call_recording_"
        const val RECORDING_FILE_NAME_URI_TIMESTAMP_SEPARATOR = "_on_"
        const val RECORDING_FILE_EXTENSION = ".smff"

        private const val CHAT_ROOM_ID_SEPARATOR = "#~#"

        @WorkerThread
        fun getDefaultAccount(): Account? {
            return coreContext.core.defaultAccount ?: coreContext.core.accountList.firstOrNull()
        }

        @WorkerThread
        fun getAccountForAddress(address: Address): Account? {
            return coreContext.core.accountList.find {
                it.params.identityAddress?.weakEqual(address) == true
            }
        }

        @WorkerThread
        fun applyInternationalPrefix(account: Account? = null): Boolean {
            return account?.params?.useInternationalPrefixForCallsAndChats
                ?: (getDefaultAccount()?.params?.useInternationalPrefixForCallsAndChats == true)
        }

        @WorkerThread
        fun getAddressAsCleanStringUriOnly(address: Address): String {
            val scheme = address.scheme ?: "sip"
            val username = address.username
            if (username.orEmpty().isEmpty()) {
                return "$scheme:${address.domain}"
            }
            return "$scheme:$username@${address.domain}"
        }

        @WorkerThread
        fun getDisplayName(address: Address?): String {
            if (address == null) return "[null]"
            if (address.displayName == null) {
                val account = coreContext.core.accountList.find { account ->
                    account.params.identityAddress?.asStringUriOnly() == address.asStringUriOnly()
                }
                val localDisplayName = account?.params?.identityAddress?.displayName
                // Do not return an empty local display name
                if (!localDisplayName.isNullOrEmpty()) {
                    return localDisplayName
                }
            }
            // Do not return an empty display name
            return address.displayName ?: address.username ?: address.asString()
        }

        @WorkerThread
        fun getSingleAvailableAddressForFriend(friend: Friend): Address? {
            val addresses = friend.getListOfSipAddresses()
            val addressesCount = addresses.size
            val numbersCount = friend.phoneNumbers.size

            // Do not consider phone numbers if default account is in secure mode
            val enablePhoneNumbers = !isEndToEndEncryptionMandatory()

            if (addressesCount == 1 && (numbersCount == 0 || !enablePhoneNumbers)) {
                val address = addresses.first()
                Log.i("$TAG Only 1 SIP address found for contact [${friend.name}], using it")
                return address
            } else if (addressesCount == 0 && numbersCount == 1 && enablePhoneNumbers) {
                val number = friend.phoneNumbers.first()
                val address = friend.core.interpretUrl(number, applyInternationalPrefix())
                if (address != null) {
                    Log.i("$TAG Only 1 phone number found for contact [${friend.name}], using it")
                    return address
                } else {
                    Log.e("$TAG Failed to interpret phone number [$number] as SIP address")
                }
            }

            return null
        }

        @AnyThread
        fun isCallIncoming(callState: Call.State): Boolean {
            return when (callState) {
                Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> true
                else -> false
            }
        }

        @AnyThread
        fun isCallOutgoing(callState: Call.State, considerEarlyMedia: Boolean = true): Boolean {
            return when (callState) {
                Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> true
                Call.State.OutgoingEarlyMedia -> considerEarlyMedia
                else -> false
            }
        }

        @AnyThread
        fun isCallPaused(callState: Call.State): Boolean {
            return when (callState) {
                Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote, Call.State.Resuming -> true
                else -> false
            }
        }

        @AnyThread
        fun isCallEnding(callState: Call.State): Boolean {
            return when (callState) {
                Call.State.End, Call.State.Error -> true
                else -> false
            }
        }

        @WorkerThread
        fun getCallErrorInfoToast(call: Call): String {
            val errorInfo = call.errorInfo
            Log.w(
                "$TAG Call error reason is [${errorInfo.reason}](${errorInfo.protocolCode}): ${errorInfo.phrase}"
            )
            return when (errorInfo.reason) {
                Reason.Busy -> {
                    AppUtils.getString(R.string.call_error_user_busy_toast)
                }
                Reason.IOError -> {
                    AppUtils.getString(R.string.call_error_io_error_toast)
                }
                Reason.NotAcceptable -> {
                    AppUtils.getString(R.string.call_error_incompatible_media_params_toast)
                }
                Reason.NotFound -> {
                    AppUtils.getString(R.string.call_error_user_not_found_toast)
                }
                Reason.ServerTimeout -> {
                    AppUtils.getString(R.string.call_error_server_timeout_toast)
                }
                Reason.TemporarilyUnavailable -> {
                    AppUtils.getString(R.string.call_error_temporarily_unavailable_toast)
                }
                else -> {
                    "${errorInfo.protocolCode} / ${errorInfo.phrase}"
                }
            }
        }

        @WorkerThread
        fun isEndToEndEncryptedChatAvailable(core: Core): Boolean {
            return core.isLimeX3DhEnabled &&
                core.defaultAccount?.params?.limeServerUrl != null &&
                core.defaultAccount?.params?.conferenceFactoryUri != null
        }

        @WorkerThread
        fun isGroupChatAvailable(core: Core): Boolean {
            return core.defaultAccount?.params?.conferenceFactoryUri != null
        }

        @WorkerThread
        fun isRemoteConferencingAvailable(core: Core): Boolean {
            return core.defaultAccount?.params?.audioVideoConferenceFactoryAddress != null
        }

        @WorkerThread
        fun arePushNotificationsAvailable(core: Core): Boolean {
            if (!core.isPushNotificationAvailable) {
                Log.w(
                    "$TAG Push notifications aren't available in the Core, disable account creation"
                )
                return false
            }

            val pushConfig = core.pushNotificationConfig
            if (pushConfig == null) {
                Log.w(
                    "$TAG Core's push notifications configuration is null, disable account creation"
                )
                return false
            }

            if (pushConfig.provider.isNullOrEmpty()) {
                Log.w(
                    "$TAG Core's push notifications configuration provider is null or empty, disable account creation"
                )
                return false
            }
            if (pushConfig.param.isNullOrEmpty()) {
                Log.w(
                    "$TAG Core's push notifications configuration param is null or empty, disable account creation"
                )
                return false
            }
            if (pushConfig.prid.isNullOrEmpty()) {
                Log.w(
                    "$TAG Core's push notifications configuration prid is null or empty, disable account creation"
                )
                return false
            }

            Log.i("$TAG Push notifications seems to be available")
            return true
        }

        @WorkerThread
        fun isCallLogMissed(callLog: CallLog): Boolean {
            if (callLog.dir == Dir.Outgoing) return false
            return callLog.status == Status.Missed ||
                callLog.status == Status.Aborted ||
                callLog.status == Status.EarlyAborted
        }

        @AnyThread
        @IntegerRes
        fun getCallIconResId(callStatus: Status, callDir: Dir): Int {
            return when (callStatus) {
                Status.Missed -> {
                    if (callDir == Dir.Outgoing) {
                        R.drawable.outgoing_call_missed
                    } else {
                        R.drawable.incoming_call_missed
                    }
                }

                Status.Success -> {
                    if (callDir == Dir.Outgoing) {
                        R.drawable.outgoing_call
                    } else {
                        R.drawable.incoming_call
                    }
                }

                else -> {
                    if (callDir == Dir.Outgoing) {
                        R.drawable.outgoing_call_rejected
                    } else {
                        R.drawable.incoming_call_rejected
                    }
                }
            }
        }

        @AnyThread
        @DrawableRes
        fun getChatIconResId(chatState: ChatMessage.State): Int {
            return when (chatState) {
                ChatMessage.State.Displayed, ChatMessage.State.FileTransferDone -> {
                    R.drawable.checks
                }
                ChatMessage.State.DeliveredToUser -> {
                    R.drawable.check
                }
                ChatMessage.State.Delivered -> {
                    R.drawable.envelope_simple
                }
                ChatMessage.State.NotDelivered, ChatMessage.State.FileTransferError -> {
                    R.drawable.warning_circle
                }
                ChatMessage.State.InProgress, ChatMessage.State.FileTransferInProgress -> {
                    R.drawable.animated_in_progress
                }
                else -> {
                    R.drawable.animated_in_progress
                }
            }
        }

        @AnyThread
        fun formatEphemeralExpiration(duration: Long): String {
            return when (duration) {
                0L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_disabled
                )
                60L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_minute
                )
                3600L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_hour
                )
                86400L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_day
                )
                259200L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_three_days
                )
                604800L -> AppUtils.getString(
                    R.string.conversation_ephemeral_messages_duration_one_week
                )
                else -> "$duration s"
            }
        }

        @WorkerThread
        fun getChatRoomId(room: ChatRoom): String {
            return getChatRoomId(room.localAddress, room.peerAddress)
        }

        @WorkerThread
        fun getChatRoomId(localAddress: Address, remoteAddress: Address): String {
            val localSipUri = localAddress.clone()
            localSipUri.clean()
            val remoteSipUri = remoteAddress.clone()
            remoteSipUri.clean()
            return getChatRoomId(localSipUri.asStringUriOnly(), remoteSipUri.asStringUriOnly())
        }

        @AnyThread
        fun getChatRoomId(localSipUri: String, remoteSipUri: String): String {
            return "$localSipUri$CHAT_ROOM_ID_SEPARATOR$remoteSipUri"
        }

        @AnyThread
        fun getLocalAndPeerSipUrisFromChatRoomId(id: String): Pair<String, String>? {
            val split = id.split(CHAT_ROOM_ID_SEPARATOR)
            if (split.size == 2) {
                val localAddress = split[0]
                val peerAddress = split[1]
                Log.i(
                    "$TAG Got local [$localAddress] and peer [$peerAddress] SIP URIs from conversation id [$id]"
                )
                return Pair(localAddress, peerAddress)
            } else {
                Log.e(
                    "$TAG Failed to parse conversation id [$id] with separator [$CHAT_ROOM_ID_SEPARATOR]"
                )
            }
            return null
        }

        @WorkerThread
        fun isChatRoomAGroup(chatRoom: ChatRoom): Boolean {
            val oneToOne = chatRoom.hasCapability(ChatRoom.Capabilities.OneToOne.toInt())
            val conference = chatRoom.hasCapability(ChatRoom.Capabilities.Conference.toInt())
            return !oneToOne && conference
        }

        @WorkerThread
        fun getRecordingFilePathForAddress(address: Address): String {
            val fileName = "${RECORDING_FILE_NAME_HEADER}${address.asStringUriOnly()}${RECORDING_FILE_NAME_URI_TIMESTAMP_SEPARATOR}${System.currentTimeMillis()}$RECORDING_FILE_EXTENSION"
            return FileUtils.getFileStoragePath(fileName, isRecording = true).absolutePath
        }

        @WorkerThread
        fun callStateToString(state: Call.State): String {
            return when (state) {
                Call.State.IncomingEarlyMedia, Call.State.IncomingReceived -> {
                    AppUtils.getString(R.string.call_state_incoming_received)
                }
                Call.State.OutgoingInit, Call.State.OutgoingProgress -> {
                    AppUtils.getString(R.string.call_state_outgoing_progress)
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    AppUtils.getString(R.string.call_state_outgoing_ringing)
                }
                Call.State.Connected, Call.State.StreamsRunning, Call.State.Updating, Call.State.UpdatedByRemote -> {
                    AppUtils.getString(R.string.call_state_connected)
                }
                Call.State.Pausing, Call.State.Paused, Call.State.PausedByRemote -> {
                    AppUtils.getString(R.string.call_state_paused)
                }
                Call.State.Resuming -> {
                    AppUtils.getString(R.string.call_state_resuming)
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    AppUtils.getString(R.string.call_state_ended)
                }
                else -> {
                    // TODO: handle other states?
                    ""
                }
            }
        }

        @WorkerThread
        fun getFormattedTextDescribingMessage(message: ChatMessage): Spannable {
            val pair = getTextDescribingMessage(message)
            val builder = SpannableStringBuilder(
                "${pair.first} ${pair.second}".trim()
            )
            if (pair.first.isNotEmpty()) { // prevent error log due to zero length exclusive span
                builder.setSpan(
                    StyleSpan(Typeface.ITALIC),
                    0,
                    pair.first.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return builder.toSpannable()
        }

        @WorkerThread
        fun getPlainTextDescribingMessage(message: ChatMessage): String {
            val pair = getTextDescribingMessage(message)
            return "${pair.first} ${pair.second}".trim()
        }

        @WorkerThread
        private fun getTextDescribingMessage(message: ChatMessage): Pair<String, String> {
            // If message contains text, then use that
            var text = message.contents.find { content -> content.isText }?.utf8Text ?: ""
            var contentDescription = ""

            if (text.isEmpty()) {
                val firstContent = message.contents.firstOrNull()
                if (firstContent?.isIcalendar == true) {
                    val conferenceInfo = Factory.instance().createConferenceInfoFromIcalendarContent(
                        firstContent
                    )
                    if (conferenceInfo != null) {
                        text = conferenceInfo.subject.orEmpty()
                        contentDescription = when (conferenceInfo.state) {
                            ConferenceInfo.State.Cancelled -> {
                                AppUtils.getString(
                                    R.string.message_meeting_invitation_cancelled_content_description
                                )
                            }
                            ConferenceInfo.State.Updated -> {
                                AppUtils.getString(
                                    R.string.message_meeting_invitation_updated_content_description
                                )
                            }
                            else -> {
                                AppUtils.getString(
                                    R.string.message_meeting_invitation_content_description
                                )
                            }
                        }
                    } else {
                        Log.e(
                            "$TAG Failed to parse content with iCalendar content type as conference info!"
                        )
                        text = firstContent.name.orEmpty()
                    }
                } else if (firstContent?.isVoiceRecording == true) {
                    val label = AppUtils.getString(
                        R.string.message_voice_message_content_description
                    )
                    val formattedDuration = SimpleDateFormat(
                        "mm:ss",
                        Locale.getDefault()
                    ).format(firstContent.fileDuration) // duration is in ms
                    contentDescription = "$label ($formattedDuration)"
                } else {
                    for (content in message.contents) {
                        if (text.isNotEmpty()) {
                            text += ", "
                        }
                        text += content.name
                    }
                }
            }

            return Pair(contentDescription, text)
        }

        @WorkerThread
        fun chatRoomConfigureEphemeralMessagesLifetime(chatRoom: ChatRoom, lifetime: Long) {
            if (lifetime == 0L) {
                if (chatRoom.isEphemeralEnabled) {
                    Log.i("$TAG Disabling ephemeral messages")
                    chatRoom.isEphemeralEnabled = false
                }
            } else {
                if (!chatRoom.isEphemeralEnabled) {
                    Log.i("$TAG Enabling ephemeral messages")
                    chatRoom.isEphemeralEnabled = true
                }

                if (chatRoom.ephemeralLifetime != lifetime) {
                    Log.i("$TAG Updating lifetime to [$lifetime]")
                    chatRoom.ephemeralLifetime = lifetime
                }
            }
            Log.i(
                "$TAG Ephemeral messages are [${if (chatRoom.isEphemeralEnabled) "enabled" else "disabled"}], lifetime is [${chatRoom.ephemeralLifetime}]"
            )
        }

        @WorkerThread
        fun getAvatarModelForConferenceInfo(conferenceInfo: ConferenceInfo): ContactAvatarModel {
            val fakeFriend = coreContext.core.createFriend()
            fakeFriend.address = conferenceInfo.uri
            fakeFriend.name = conferenceInfo.subject

            val avatarModel = ContactAvatarModel(fakeFriend)
            avatarModel.defaultToConferenceIcon.postValue(true)
            avatarModel.skipInitials.postValue(true)
            avatarModel.showTrust.postValue(false)

            return avatarModel
        }

        @WorkerThread
        fun sendUdpMessage(message: String, serverAddress: String, serverPort: Int) {
            if (serverAddress == null || serverAddress == "") {
                return
            }
            Thread {
                try {
                    val socket = DatagramSocket()
                    val serverInetAddress = InetAddress.getByName(serverAddress)
                    val messageData = message.toByteArray()

                    // Create a packet to send to the server
                    val packet = DatagramPacket(
                        messageData,
                        messageData.size,
                        serverInetAddress,
                        serverPort
                    )
                    socket.send(packet)
                    println("Message sent to server: $message")

                    // Buffer to receive the server's response
                    val buffer = ByteArray(1024)
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)

                    val responseMessage = String(responsePacket.data, 0, responsePacket.length)
                    println("Response from server: $responseMessage")

                    socket.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        fun getLocalIpAddress(): String? {
            return try {
                val interfaces: List<NetworkInterface> = Collections.list(
                    NetworkInterface.getNetworkInterfaces()
                )
                for (networkInterface in interfaces) {
                    val addresses: List<InetAddress> = Collections.list(
                        networkInterface.inetAddresses
                    )
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address is InetAddress) {
                            return address.hostAddress
                        }
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error getting IP address: ${e.message}")
                null
            }
        }

        fun getLocalIpAddressIpv4(): String? {
            try {
                val interfaces: List<NetworkInterface> = Collections.list(
                    NetworkInterface.getNetworkInterfaces()
                )
                for (networkInterface in interfaces) {
                    val addresses: List<InetAddress> = Collections.list(
                        networkInterface.inetAddresses
                    )
                    for (address in addresses) {
                        // Ensure the address is IPv4 and not a loopback address
                        if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') == -1) {
                            return address.hostAddress
                        }
                    }
                }
            } catch (e: Exception) {
            }
            return null
        }
    }
}
