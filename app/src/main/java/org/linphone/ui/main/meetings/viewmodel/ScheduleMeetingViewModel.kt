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
package org.linphone.ui.main.meetings.viewmodel

import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import java.util.Calendar
import java.util.TimeZone
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.ConferenceInfo
import org.linphone.core.ConferenceScheduler
import org.linphone.core.ConferenceSchedulerListenerStub
import org.linphone.core.Factory
import org.linphone.core.Participant
import org.linphone.core.ParticipantInfo
import org.linphone.core.tools.Log
import org.linphone.ui.GenericViewModel
import org.linphone.ui.main.meetings.model.TimeZoneModel
import org.linphone.ui.main.model.SelectedAddressModel
import org.linphone.utils.Event
import org.linphone.utils.TimestampUtils

class ScheduleMeetingViewModel @UiThread constructor() : GenericViewModel() {
    companion object {
        private const val TAG = "[Schedule Meeting ViewModel]"
    }

    val isBroadcastSelected = MutableLiveData<Boolean>()

    val showBroadcastHelp = MutableLiveData<Boolean>()

    val subject = MutableLiveData<String>()

    val description = MutableLiveData<String>()

    val allDayMeeting = MutableLiveData<Boolean>()

    val fromDate = MutableLiveData<String>()

    val toDate = MutableLiveData<String>()

    val fromTime = MutableLiveData<String>()

    val toTime = MutableLiveData<String>()

    val availableTimeZones: List<TimeZoneModel> = TimeZone.getAvailableIDs().map { id ->
        TimeZoneModel(TimeZone.getTimeZone(id))
    }.toList().sorted()
    var selectedTimeZone = MutableLiveData<TimeZoneModel>()

    val sendInvitations = MutableLiveData<Boolean>()

    val participants = MutableLiveData<ArrayList<SelectedAddressModel>>()

    val operationInProgress = MutableLiveData<Boolean>()

    val hideBroadcast = MutableLiveData<Boolean>()

    val conferenceCreatedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    private var startTimestamp = 0L
    private var endTimestamp = 0L

    internal var startHour = 0
    internal var startMinutes = 0

    internal var endHour = 0
    internal var endMinutes = 0

    private lateinit var conferenceScheduler: ConferenceScheduler

    private lateinit var conferenceInfo: ConferenceInfo

    private val conferenceSchedulerListener = object : ConferenceSchedulerListenerStub() {
        @WorkerThread
        override fun onStateChanged(
            conferenceScheduler: ConferenceScheduler,
            state: ConferenceScheduler.State?
        ) {
            Log.i("$TAG Conference state changed [$state]")
            when (state) {
                ConferenceScheduler.State.Error -> {
                    operationInProgress.postValue(false)
                    showRedToastEvent.postValue(
                        Event(
                            Pair(
                                R.string.meeting_failed_to_schedule_toast,
                                R.drawable.warning_circle
                            )
                        )
                    )
                }
                ConferenceScheduler.State.Ready -> {
                    val conferenceAddress = conferenceScheduler.info?.uri
                    if (::conferenceInfo.isInitialized) {
                        Log.i(
                            "$TAG Conference info [${conferenceInfo.uri?.asStringUriOnly()}] has been updated"
                        )
                    } else {
                        Log.i(
                            "$TAG Conference info created, address will be [${conferenceAddress?.asStringUriOnly()}]"
                        )
                    }

                    if (sendInvitations.value == true) {
                        Log.i("$TAG User asked for invitations to be sent, let's do it")
                        val chatRoomParams = coreContext.core.createDefaultChatRoomParams()
                        chatRoomParams.isGroupEnabled = false
                        chatRoomParams.backend = ChatRoom.Backend.FlexisipChat
                        chatRoomParams.isEncryptionEnabled = true
                        chatRoomParams.subject = "Meeting invitation" // Won't be used
                        conferenceScheduler.sendInvitations(chatRoomParams)
                    } else {
                        Log.i("$TAG User didn't asked for invitations to be sent")
                        operationInProgress.postValue(false)
                        conferenceCreatedEvent.postValue(Event(true))
                    }
                }
                else -> {
                }
            }
        }

        @WorkerThread
        override fun onInvitationsSent(
            conferenceScheduler: ConferenceScheduler,
            failedInvitations: Array<out Address>?
        ) {
            when (val failedCount = failedInvitations?.size) {
                0 -> {
                    Log.i("$TAG All invitations have been sent")
                }
                participants.value.orEmpty().size -> {
                    Log.e("$TAG No invitation sent!")
                    showRedToastEvent.postValue(
                        Event(
                            Pair(
                                R.string.meeting_failed_to_send_invites_toast,
                                R.drawable.warning_circle
                            )
                        )
                    )
                }
                else -> {
                    Log.w("$TAG [$failedCount] invitations couldn't have been sent for:")
                    for (failed in failedInvitations.orEmpty()) {
                        Log.w(failed.asStringUriOnly())
                    }
                    showRedToastEvent.postValue(
                        Event(
                            Pair(
                                R.string.meeting_failed_to_send_part_of_invites_toast,
                                R.drawable.warning_circle
                            )
                        )
                    )
                }
            }

            operationInProgress.postValue(false)
            conferenceCreatedEvent.postValue(Event(true))
        }
    }

    init {
        coreContext.postOnCoreThread {
            hideBroadcast.postValue(corePreferences.disableBroadcasts)
        }
        isBroadcastSelected.value = false
        showBroadcastHelp.value = false
        allDayMeeting.value = false
        sendInvitations.value = true

        selectedTimeZone.value = availableTimeZones.find {
            it.id == TimeZone.getDefault().id
        }

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance(
            TimeZone.getTimeZone(selectedTimeZone.value?.id ?: TimeZone.getDefault().id)
        )
        cal.timeInMillis = now
        cal.add(Calendar.HOUR, 1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val nextFullHour = cal.timeInMillis
        startHour = cal.get(Calendar.HOUR_OF_DAY)
        startMinutes = 0

        cal.add(Calendar.HOUR, 1)
        val twoHoursLater = cal.timeInMillis
        endHour = cal.get(Calendar.HOUR_OF_DAY)
        endMinutes = 0

        startTimestamp = nextFullHour
        endTimestamp = twoHoursLater

        Log.i(
            "$TAG Default start time is [$startHour:$startMinutes], default end time is [$startHour:$startMinutes]"
        )
        Log.i("$TAG Default start date is [$startTimestamp], default end date is [$endTimestamp]")

        computeDateLabels()
        computeTimeLabels()
    }

    override fun onCleared() {
        super.onCleared()

        coreContext.postOnCoreThread {
            if (::conferenceScheduler.isInitialized) {
                conferenceScheduler.removeListener(conferenceSchedulerListener)
            }
        }
    }

    @UiThread
    fun findConferenceInfo(meeting: ConferenceInfo?, uri: String) {
        coreContext.postOnCoreThread { core ->
            if (meeting != null && ::conferenceInfo.isInitialized && meeting == conferenceInfo) {
                Log.i("$TAG ConferenceInfo object already in memory, skipping")
                configureConferenceInfo()
            }

            val address = Factory.instance().createAddress(uri)

            if (meeting != null && (!::conferenceInfo.isInitialized || conferenceInfo != meeting)) {
                if (address != null && meeting.uri?.equal(address) == true) {
                    Log.i("$TAG ConferenceInfo object available in sharedViewModel, using it")
                    conferenceInfo = meeting
                    configureConferenceInfo()
                    return@postOnCoreThread
                }
            }

            if (address == null) {
                Log.e("$TAG Failed to parse conference URI [$uri], abort")
                return@postOnCoreThread
            }

            val conferenceInfo = core.findConferenceInformationFromUri(address)
            if (conferenceInfo == null) {
                Log.e(
                    "$TAG Failed to find a conference info matching URI [${address.asString()}], abort"
                )
                return@postOnCoreThread
            }
            this.conferenceInfo = conferenceInfo
            Log.i(
                "$TAG Found conference info matching URI [${conferenceInfo.uri?.asString()}] with subject [${conferenceInfo.subject}]"
            )

            configureConferenceInfo()
        }
    }

    @UiThread
    fun getCurrentlySelectedStartDate(): Long {
        return startTimestamp
    }

    @UiThread
    fun setStartDate(timestamp: Long) {
        startTimestamp = timestamp
        endTimestamp = timestamp
        computeDateLabels()
    }

    @UiThread
    fun getCurrentlySelectedEndDate(): Long {
        return endTimestamp
    }

    @UiThread
    fun setEndDate(timestamp: Long) {
        endTimestamp = timestamp
        computeDateLabels()
    }

    @UiThread
    fun updateTimeZone(timeZone: TimeZoneModel) {
        selectedTimeZone.value = timeZone
        computeTimeLabels()
    }

    @UiThread
    fun setStartTime(hours: Int, minutes: Int) {
        startHour = hours
        startMinutes = minutes

        endHour = hours + 1
        endMinutes = minutes

        computeTimeLabels()
    }

    @UiThread
    fun setEndTime(hours: Int, minutes: Int) {
        endHour = hours
        endMinutes = minutes

        computeTimeLabels()
    }

    @UiThread
    fun selectMeeting() {
        isBroadcastSelected.value = false
        showBroadcastHelp.value = false
    }

    @UiThread
    fun selectBroadcast() {
        isBroadcastSelected.value = true
        showBroadcastHelp.value = true
    }

    @UiThread
    fun closeBroadcastHelp() {
        showBroadcastHelp.value = false
    }

    @UiThread
    fun setParticipants(toAdd: List<String>) {
        coreContext.postOnCoreThread {
            val list = arrayListOf<SelectedAddressModel>()

            for (participant in toAdd) {
                val address = Factory.instance().createAddress(participant)
                if (address == null) {
                    Log.e("$TAG Failed to parse [$participant] as address!")
                } else {
                    val avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(
                        address
                    )
                    val model = SelectedAddressModel(address, avatarModel) { model ->
                        // onRemoveFromSelection
                        removeModelFromSelection(model)
                    }
                    list.add(model)
                    Log.i("$TAG Participant [${address.asStringUriOnly()}] added to list")
                }
            }

            Log.i("$TAG Now there are [${list.size}] participants in list")
            participants.postValue(list)
        }
    }

    // TODO FIXME: handle speakers when in broadcast mode

    @UiThread
    fun schedule() {
        if (subject.value.orEmpty().isEmpty() || participants.value.orEmpty().isEmpty()) {
            Log.e(
                "$TAG Either no subject was set or no participant was selected, can't schedule meeting."
            )
            showRedToastEvent.postValue(
                Event(
                    Pair(
                        R.string.meeting_schedule_mandatory_field_not_filled_toast,
                        R.drawable.warning_circle
                    )
                )
            )
            return
        }

        coreContext.postOnCoreThread { core ->
            Log.i(
                "$TAG Scheduling ${if (isBroadcastSelected.value == true) "broadcast" else "meeting"}"
            )
            operationInProgress.postValue(true)

            val localAccount = core.defaultAccount
            val localAddress = localAccount?.params?.identityAddress

            val conferenceInfo = Factory.instance().createConferenceInfo()
            conferenceInfo.organizer = localAddress
            conferenceInfo.subject = subject.value
            conferenceInfo.description = description.value

            if (allDayMeeting.value == true) {
                val cal = Calendar.getInstance(
                    TimeZone.getTimeZone(selectedTimeZone.value?.id ?: TimeZone.getDefault().id)
                )
                cal.timeInMillis = startTimestamp
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val startTime = cal.timeInMillis / 1000 // Linphone expects timestamp in seconds

                cal.timeInMillis = endTimestamp
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.add(Calendar.HOUR, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                val endTime = cal.timeInMillis / 1000 // Linphone expects timestamp in seconds
                val duration = ((endTime - startTime) / 60).toInt() // Linphone expects duration in minutes

                Log.i(
                    "$TAG Scheduling meeting using start day [$startTime] and duration [$duration] (minutes)"
                )
                conferenceInfo.dateTime = startTime
                conferenceInfo.duration = duration
            } else {
                val startTime = startTimestamp / 1000 // Linphone expects timestamp in seconds
                val duration =
                    (((endTimestamp - startTimestamp) / 1000) / 60).toInt() // Linphone expects duration in minutes
                Log.i(
                    "$TAG Scheduling meeting using start hour [$startTime] and duration [$duration] (minutes)"
                )
                conferenceInfo.dateTime = startTime
                conferenceInfo.duration = duration
            }

            val participantsList = participants.value.orEmpty()
            val participantsInfoList = arrayListOf<ParticipantInfo>()
            for (participant in participantsList) {
                val info = Factory.instance().createParticipantInfo(participant.address)
                if (info == null) {
                    Log.e(
                        "$TAG Failed to create Participant Info from address [${participant.address.asStringUriOnly()}]"
                    )
                    continue
                }

                // For meetings, all participants must have Speaker role
                info.role = Participant.Role.Speaker
                participantsInfoList.add(info)
            }

            val participantsInfoArray = arrayOfNulls<ParticipantInfo>(participantsInfoList.size)
            participantsInfoList.toArray(participantsInfoArray)
            conferenceInfo.setParticipantInfos(participantsInfoArray)

            if (!::conferenceScheduler.isInitialized) {
                conferenceScheduler = core.createConferenceScheduler()
                conferenceScheduler.addListener(conferenceSchedulerListener)
            }

            conferenceScheduler.account = localAccount
            // Will trigger the conference creation automatically
            conferenceScheduler.info = conferenceInfo
        }
    }

    @UiThread
    fun update() {
        coreContext.postOnCoreThread { core ->
            Log.i(
                "$TAG Updating ${if (isBroadcastSelected.value == true) "broadcast" else "meeting"}"
            )
            if (!::conferenceInfo.isInitialized) {
                Log.e("No conference info to edit found!")
                return@postOnCoreThread
            }

            operationInProgress.postValue(true)

            conferenceInfo.subject = subject.value
            conferenceInfo.description = description.value

            val startTime = startTimestamp / 1000 // Linphone expects timestamp in seconds
            conferenceInfo.dateTime = startTime
            val duration = (((endTimestamp - startTimestamp) / 1000) / 60).toInt() // Linphone expects duration in minutes
            conferenceInfo.duration = duration

            val participantsList = participants.value.orEmpty()
            val participantsInfoList = arrayListOf<ParticipantInfo>()
            for (participant in participantsList) {
                val info = Factory.instance().createParticipantInfo(participant.address)
                if (info == null) {
                    Log.e(
                        "$TAG Failed to create Participant Info from address [${participant.address.asStringUriOnly()}]"
                    )
                    continue
                }

                // For meetings, all participants must have Speaker role
                info.role = Participant.Role.Speaker
                participantsInfoList.add(info)
            }

            val participantsInfoArray = arrayOfNulls<ParticipantInfo>(participantsInfoList.size)
            participantsInfoList.toArray(participantsInfoArray)
            conferenceInfo.setParticipantInfos(participantsInfoArray)

            if (!::conferenceScheduler.isInitialized) {
                conferenceScheduler = core.createConferenceScheduler()
                conferenceScheduler.addListener(conferenceSchedulerListener)
            }

            // Will trigger the conference update automatically
            conferenceScheduler.info = conferenceInfo
        }
    }

    @WorkerThread
    private fun configureConferenceInfo() {
        if (::conferenceInfo.isInitialized) {
            subject.postValue(conferenceInfo.subject)
            description.postValue(conferenceInfo.description)

            isBroadcastSelected.postValue(false) // TODO FIXME: not implemented yet

            startTimestamp = conferenceInfo.dateTime * 1000 /* Linphone timestamps are in seconds */
            endTimestamp =
                (conferenceInfo.dateTime + conferenceInfo.duration * 60) * 1000 /* Linphone timestamps are in seconds */
            Log.i(
                "$TAG Loaded start date is [$startTimestamp], loaded end date is [$endTimestamp]"
            )
            val cal = Calendar.getInstance(
                TimeZone.getTimeZone(selectedTimeZone.value?.id ?: TimeZone.getDefault().id)
            )
            cal.timeInMillis = startTimestamp
            startHour = cal.get(Calendar.HOUR_OF_DAY)
            startMinutes = cal.get(Calendar.MINUTE)
            cal.timeInMillis = endTimestamp
            endHour = cal.get(Calendar.HOUR_OF_DAY)
            endMinutes = cal.get(Calendar.MINUTE)

            computeDateLabels()
            computeTimeLabels()

            val list = arrayListOf<SelectedAddressModel>()
            for (participant in conferenceInfo.participantInfos) {
                val address = participant.address
                val avatarModel = coreContext.contactsManager.getContactAvatarModelForAddress(
                    address
                )
                val model = SelectedAddressModel(address, avatarModel) { model ->
                    // onRemoveFromSelection
                    removeModelFromSelection(model)
                }
                list.add(model)
                Log.i("$TAG Loaded participant [${address.asStringUriOnly()}]")
            }
            Log.i(
                "$TAG [${list.size}] participants loaded from found conference info"
            )
            participants.postValue(list)
        }
    }

    @UiThread
    private fun removeModelFromSelection(model: SelectedAddressModel) {
        val newList = arrayListOf<SelectedAddressModel>()
        newList.addAll(participants.value.orEmpty())
        newList.remove(model)
        Log.i("$TAG Removed participant [${model.address.asStringUriOnly()}]")
        participants.postValue(newList)
    }

    @AnyThread
    private fun computeDateLabels() {
        val start = TimestampUtils.toString(
            startTimestamp,
            onlyDate = true,
            timestampInSecs = false,
            shortDate = false,
            hideYear = false
        )
        fromDate.postValue(start)
        Log.i("$TAG Computed start date for timestamp [$startTimestamp] is [$start]")

        val end = TimestampUtils.toString(
            endTimestamp,
            onlyDate = true,
            timestampInSecs = false,
            shortDate = false,
            hideYear = false
        )
        toDate.postValue(end)
        Log.i("$TAG Computed end date for timestamp [$endTimestamp] is [$end]")
    }

    @AnyThread
    private fun computeTimeLabels() {
        val timeZoneId = selectedTimeZone.value?.id ?: TimeZone.getDefault().id
        Log.i("$TAG Updating timestamps using time zone [${selectedTimeZone.value}]($timeZoneId)")
        val cal = Calendar.getInstance(
            TimeZone.getTimeZone(timeZoneId)
        )
        cal.timeInMillis = startTimestamp
        if (startHour != -1 && startMinutes != -1) {
            cal.set(Calendar.HOUR_OF_DAY, startHour)
            cal.set(Calendar.MINUTE, startMinutes)
        }
        startTimestamp = cal.timeInMillis
        val start = TimestampUtils.timeToString(startTimestamp, timestampInSecs = false)
        Log.i("$TAG Computed start time for timestamp [$startTimestamp] is [$start]")
        fromTime.postValue(start)

        cal.timeInMillis = endTimestamp
        if (endHour != -1 && endMinutes != -1) {
            cal.set(Calendar.HOUR_OF_DAY, endHour)
            cal.set(Calendar.MINUTE, endMinutes)

            if (endHour < startHour || (endHour == startHour && endMinutes <= startMinutes)) {
                // Make sure if endTime is after startTime that it is on the next day
                if (cal.timeInMillis <= startTimestamp) {
                    Log.i("$TAG endTime < startTime, adding 1 day to endTimestamp")
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        endTimestamp = cal.timeInMillis
        val end = TimestampUtils.timeToString(endTimestamp, timestampInSecs = false)
        Log.i("$TAG Computed end time for timestamp [$endTimestamp] is [$end]")
        toTime.postValue(end)
    }
}
