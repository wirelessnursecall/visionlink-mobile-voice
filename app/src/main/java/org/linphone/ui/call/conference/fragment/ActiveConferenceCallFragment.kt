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
package org.linphone.ui.call.conference.fragment

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.CallActiveConferenceFragmentBinding
import org.linphone.ui.call.fragment.GenericCallFragment
import org.linphone.ui.call.viewmodel.CallsViewModel
import org.linphone.ui.call.viewmodel.CurrentCallViewModel
import org.linphone.utils.Event
import org.linphone.utils.startAnimatedDrawable

class ActiveConferenceCallFragment : GenericCallFragment() {
    companion object {
        private const val TAG = "[Active Conference Call Fragment]"
    }

    private lateinit var binding: CallActiveConferenceFragmentBinding

    private lateinit var callViewModel: CurrentCallViewModel

    private lateinit var callsViewModel: CallsViewModel

    private val actionsBottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) {
            if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                val drawable = AnimatedVectorDrawableCompat.create(
                    requireContext(),
                    R.drawable.animated_handle_to_caret
                )
                binding.bottomBar.mainActions.callActionsHandle.setImageDrawable(drawable)
            } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                val drawable = AnimatedVectorDrawableCompat.create(
                    requireContext(),
                    R.drawable.animated_caret_to_handle
                )
                binding.bottomBar.mainActions.callActionsHandle.setImageDrawable(drawable)
            }
        }

        override fun onSlide(bottomSheet: View, slideOffset: Float) { }
    }

    // For moving video preview purposes

    private var previewX: Float = 0f
    private var previewY: Float = 0f

    private val previewTouchListener = View.OnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                previewX = view.x - event.rawX
                previewY = view.y - event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                view.animate()
                    .x(event.rawX + previewX)
                    .y(event.rawY + previewY)
                    .setDuration(0)
                    .start()
                true
            }
            else -> {
                view.performClick()
                false
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val actionsBottomSheetBehavior = BottomSheetBehavior.from(binding.bottomBar.root)
            if (actionsBottomSheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
                actionsBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                return
            }

            val callStatsBottomSheetBehavior = BottomSheetBehavior.from(binding.callStats.root)
            if (callStatsBottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                callStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                return
            }

            val callMediaEncryptionStatsBottomSheetBehavior = BottomSheetBehavior.from(
                binding.callMediaEncryptionStats.root
            )
            if (callMediaEncryptionStatsBottomSheetBehavior.state != BottomSheetBehavior.STATE_HIDDEN) {
                callMediaEncryptionStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                return
            }

            Log.i("$TAG Back gesture/click detected, no bottom sheet is expanded, going back")
            isEnabled = false
            try {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } catch (ise: IllegalStateException) {
                Log.w("$TAG Can't go back: $ise")
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = CallActiveConferenceFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        callViewModel = requireActivity().run {
            ViewModelProvider(this)[CurrentCallViewModel::class.java]
        }

        callsViewModel = requireActivity().run {
            ViewModelProvider(this)[CallsViewModel::class.java]
        }

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = callViewModel
        binding.conferenceViewModel = callViewModel.conferenceModel
        binding.callsViewModel = callsViewModel
        binding.numpadModel = callViewModel.numpadModel

        val actionsBottomSheetBehavior = BottomSheetBehavior.from(binding.bottomBar.root)
        actionsBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        actionsBottomSheetBehavior.addBottomSheetCallback(actionsBottomSheetCallback)

        val callStatsBottomSheetBehavior = BottomSheetBehavior.from(binding.callStats.root)
        callStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        callStatsBottomSheetBehavior.skipCollapsed = true

        val callMediaEncryptionStatsBottomSheetBehavior = BottomSheetBehavior.from(
            binding.callMediaEncryptionStats.root
        )
        callMediaEncryptionStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        callMediaEncryptionStatsBottomSheetBehavior.skipCollapsed = true

        callViewModel.callDuration.observe(viewLifecycleOwner) { duration ->
            binding.chronometer.base = SystemClock.elapsedRealtime() - (1000 * duration)
            binding.chronometer.start()
        }

        callViewModel.toggleExtraActionsBottomSheetEvent.observe(viewLifecycleOwner) {
            it.consume {
                val state = actionsBottomSheetBehavior.state
                if (state == BottomSheetBehavior.STATE_COLLAPSED) {
                    val drawable = AnimatedVectorDrawableCompat.create(
                        requireContext(),
                        R.drawable.animated_caret_to_handle
                    )
                    binding.bottomBar.mainActions.callActionsHandle.setImageDrawable(drawable)
                    binding.bottomBar.mainActions.callActionsHandle.startAnimatedDrawable()
                    actionsBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
                } else if (state == BottomSheetBehavior.STATE_EXPANDED) {
                    val drawable = AnimatedVectorDrawableCompat.create(
                        requireContext(),
                        R.drawable.animated_handle_to_caret
                    )
                    binding.bottomBar.mainActions.callActionsHandle.setImageDrawable(drawable)
                    binding.bottomBar.mainActions.callActionsHandle.startAnimatedDrawable()
                    actionsBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
            }
        }

        callViewModel.fullScreenMode.observe(viewLifecycleOwner) { hide ->
            Log.i("$TAG Switching full screen mode to ${if (hide) "ON" else "OFF"}")
            sharedViewModel.toggleFullScreenEvent.value = Event(hide)
            callStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            callMediaEncryptionStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        callViewModel.conferenceModel.conferenceLayout.observe(viewLifecycleOwner) { layout ->
            // Collapse bottom sheet after changing conference layout
            actionsBottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        callViewModel.conferenceModel.participants.observe(viewLifecycleOwner) { participants ->
            coreContext.postOnCoreThread { core ->
                if (participants.size == 1) {
                    Log.i("$TAG We are alone in that conference, using nativePreviewWindowId")
                    core.nativePreviewWindowId = binding.localPreviewVideoSurface

                    // Don't forget to leave full screen mode, otherwise we won't be able to leave it by touching video surface...
                    callViewModel.fullScreenMode.postValue(false)
                }
            }
        }

        callViewModel.goToCallEvent.observe(viewLifecycleOwner) {
            it.consume {
                if (findNavController().currentDestination?.id == R.id.activeConferenceCallFragment) {
                    Log.i("$TAG Going to active call fragment")
                    val action =
                        ActiveConferenceCallFragmentDirections.actionActiveConferenceCallFragmentToActiveCallFragment()
                    findNavController().navigate(action)
                }
            }
        }

        binding.setBackClickListener {
            requireActivity().finish()
        }

        binding.setCallsListClickListener {
            Log.i("$TAG Going to calls list fragment")
            val action = ActiveConferenceCallFragmentDirections.actionActiveConferenceCallFragmentToCallsListFragment()
            findNavController().navigate(action)
        }

        binding.setParticipantsListClickListener {
            Log.i("$TAG Going to conference participants list fragment")
            val action = ActiveConferenceCallFragmentDirections.actionActiveConferenceCallFragmentToConferenceParticipantsListFragment()
            findNavController().navigate(action)
        }

        binding.setShareConferenceClickListener {
            val sipUri = callViewModel.conferenceModel.sipUri.value.orEmpty()
            if (sipUri.isNotEmpty()) {
                Log.i("$TAG Sharing conference SIP URI [$sipUri]")

                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val label = "Conference SIP address"
                clipboard.setPrimaryClip(ClipData.newPlainText(label, sipUri))
            }
        }

        binding.setCallStatisticsClickListener {
            callMediaEncryptionStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            callStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        binding.setCallMediaEncryptionStatisticsClickListener {
            callStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            callMediaEncryptionStatsBottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onResume() {
        super.onResume()

        coreContext.postOnCoreThread {
            binding.localPreviewVideoSurface.setOnTouchListener(previewTouchListener)

            // Need to be done manually
            callViewModel.updateCallDuration()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onPause() {
        super.onPause()

        binding.localPreviewVideoSurface.setOnTouchListener(null)
    }
}
