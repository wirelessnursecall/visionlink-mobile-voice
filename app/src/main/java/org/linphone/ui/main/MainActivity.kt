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
package org.linphone.ui.main
import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcelable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import kotlin.math.max
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.contacts.AvatarGenerator
import org.linphone.core.Account
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import org.linphone.core.tools.Log
import org.linphone.databinding.MainActivityBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.main.chat.fragment.ConversationsListFragmentDirections
import org.linphone.ui.main.help.fragment.DebugFragmentDirections
import org.linphone.ui.main.history.fragment.HistoryListFragment
import org.linphone.ui.main.model.AuthRequestedDialogModel
import org.linphone.ui.main.sso.fragment.SingleSignOnFragmentDirections
import org.linphone.ui.main.viewmodel.MainViewModel
import org.linphone.ui.main.viewmodel.SharedMainViewModel
import org.linphone.ui.welcome.WelcomeActivity
import org.linphone.utils.AppUtils
import org.linphone.utils.DialogUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils

@UiThread
class MainActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Main Activity]"

        private const val DEFAULT_FRAGMENT_KEY = "default_fragment"
        private const val CONTACTS_FRAGMENT_ID = 1
        private const val HISTORY_FRAGMENT_ID = 2
        private const val CHAT_FRAGMENT_ID = 3
        private const val MEETINGS_FRAGMENT_ID = 4

        var VoipUserName = ""
        var VoipDisplayName = ""
        var VoipRegAuthId = ""
        var VoipPassword = ""
        var VoipDomain = ""
        var VoipIdentity = ""
        var firstStartCore = true
    }

    private lateinit var binding: MainActivityBinding

    private lateinit var viewModel: MainViewModel

    private lateinit var sharedViewModel: SharedMainViewModel

    private var currentlyDisplayedAuthDialog: Dialog? = null

    private var navigatedToDefaultFragment = false

    lateinit var newlyCreatedAuthInfo: AuthInfo
    lateinit var newlyCreatedAccount: Account

    private val destinationListener = object : NavController.OnDestinationChangedListener {
        override fun onDestinationChanged(
            controller: NavController,
            destination: NavDestination,
            arguments: Bundle?
        ) {
            Log.i("$TAG Latest visited fragment was restored")
            navigatedToDefaultFragment = true
            controller.removeOnDestinationChangedListener(this)
        }
    }

    private val postNotificationsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG POST_NOTIFICATIONS permission has been granted")
            viewModel.updatePostNotificationsPermission()
        } else {
            Log.w("$TAG POST_NOTIFICATIONS permission has been denied!")
        }
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be done before the setContentView
        // installSplashScreen()

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                true // Force dark mode to always have white icons in status bar
            }
        )

        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.main_activity)
        binding.lifecycleOwner = this
        setUpToastsArea(binding.toastsArea)

        ViewCompat.setOnApplyWindowInsetsListener(binding.inCallTopBar.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(0, insets.top, 0, 0)
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainNavContainer) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(insets.left, 0, insets.right, max(insets.bottom, keyboard.bottom))
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerMenuContent) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val mlp = v.layoutParams as ViewGroup.MarginLayoutParams
            mlp.leftMargin = insets.left
            mlp.topMargin = insets.top
            mlp.rightMargin = insets.right
            mlp.bottomMargin = insets.bottom
            v.layoutParams = mlp
            WindowInsetsCompat.CONSUMED
        }

        while (!coreContext.isReady()) {
            Thread.sleep(50)
        }

        viewModel = run {
            ViewModelProvider(this)[MainViewModel::class.java]
        }
        binding.viewModel = viewModel

        sharedViewModel = run {
            ViewModelProvider(this)[SharedMainViewModel::class.java]
        }

        viewModel.goBackToCallEvent.observe(this) {
            it.consume {
                coreContext.showCallActivity()
            }
        }

        viewModel.openDrawerEvent.observe(this) {
            it.consume {
                openDrawerMenu()
            }
        }

        viewModel.askPostNotificationsPermissionEvent.observe(this) {
            it.consume {
                postNotificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        viewModel.defaultAccountRegistrationErrorEvent.observe(this) {
            it.consume { error ->
                val tag = "DEFAULT_ACCOUNT_REGISTRATION_ERROR"
                if (error) {
                    // First remove any already existing connection error toast
                    removePersistentRedToast(tag)

                    // val message = getString(R.string.default_account_connection_state_error_toast)
                    // showPersistentRedToast(message, R.drawable.warning_circle, tag)

                    // sharedViewModel.textInfoHistoryList.value = "Connect to VoIP server failed. Please check VoIP settings"
                    HistoryListFragment.registrationStatus = -1
                    sharedViewModel.currentAccountRegisterState.value = -1
                    // updateHistoryListInfo("Connect to VoIP server failed. Please check VoIP settings")
                    // showAlert(this, "Connect to VoIP server failed. Please check VoIP settings.")
                } else {
                    removePersistentRedToast(tag)
                }
            }
        }

        viewModel.AccountRegistrationErrorEvent.observe(this) {
            it.consume { error ->
                val tag = "ACCOUNT_REGISTRATION_ERROR"
                removePersistentRedToast(tag)

                HistoryListFragment.registrationStatus = -1
                sharedViewModel.currentAccountRegisterState.value = -1
                sharedViewModel.refreshDrawerMenuAccountsListEvent.value = Event(true)
                // val message = getString(R.string.default_account_connection_state_error_toast)
                // sharedViewModel.textInfoHistoryList.value = "Connect to VoIP server failed. Please check VoIP settings"
                // HistoryListFragment.registrationStatus = -1
                // sharedViewModel.currentAccountRegisterState.value = -1
                // showPersistentRedToast(message, R.drawable.warning_circle, tag)
                // updateHistoryListInfo("Connect to VoIP server failed. Please check VoIP settings.")
                // showAlert(this, "Connect to VoIP server failed. Please check VoIP settings.")
            }
        }

        viewModel.showNewAccountToastEvent.observe(this) {
            it.consume {
                // val message = getString(R.string.new_account_configured_toast)
                //  HistoryListFragment.registrationStatus = 1
                // updateHistoryListInfo("Setting up VoIP account, please wait ...")
                // sharedViewModel.textInfoHistoryList.value = "Setting up VoIP account, please wait ..."
                // sharedViewModel.currentAccountRegisterState.value = 1
                // showGreenToast("Setting up VoIP account, please wait ...", R.drawable.user_circle)
            }
        }

        viewModel.AccountRegistrationSuccessEvent.observe(this) {
            it.consume {
                // HistoryListFragment.registrationStatus = 2
                // updateHistoryListInfo("Ready for VoIP calling")
                // sharedViewModel.textInfoHistoryList.value = "VoIP ready to use"
                // sharedViewModel.currentAccountRegisterState.value = 2
                // showAlert(this, "Initialize VoIP account successfully. Return to main application.")
                HistoryListFragment.registrationStatus = 2
                sharedViewModel.currentAccountRegisterState.value = 2
            }
        }
        /*
        viewModel.AccountRegistrationInProgressEvent.observe(this) {
            it.consume {
                //  HistoryListFragment.registrationStatus = 1
                // updateHistoryListInfo("Setting up VoIP account, please wait ...")
                // sharedViewModel.currentAccountRegisterState.value = 1
                // sharedViewModel.textInfoHistoryList.value = "Setting up VoIP account, please wait ..."
            }
        }*/
        /*
       viewModel.startLoadingContactsEvent.observe(this) {
           it.consume {

               if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                   loadContacts()
               }
            }
        }
        */
        viewModel.lastAccountRemovedEvent.observe(this) {
            it.consume {
                HistoryListFragment.registrationStatus = 0
                sharedViewModel.currentAccountRegisterState.value = 0
            }
        }

        // Wait for latest visited fragment to be displayed before hiding the splashscreen
        binding.root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                return if (navigatedToDefaultFragment) {
                    Log.i("$TAG Report UI has been fully drawn (TTFD)")
                    try {
                        reportFullyDrawn()
                    } catch (se: SecurityException) {
                        Log.e("$TAG Security exception when doing reportFullyDrawn(): $se")
                    }
                    binding.root.viewTreeObserver.removeOnPreDrawListener(this)
                    true
                } else {
                    false
                }
            }
        })

        coreContext.bearerAuthenticationRequestedEvent.observe(this) {
            it.consume { pair ->
                val serverUrl = pair.first
                val username = pair.second

                Log.i(
                    "$TAG Navigating to Single Sign On Fragment with server URL [$serverUrl] and username [$username]"
                )
                val action = SingleSignOnFragmentDirections.actionGlobalSingleSignOnFragment(
                    serverUrl,
                    username
                )
                findNavController().navigate(action)
            }
        }

        coreContext.digestAuthenticationRequestedEvent.observe(this) {
            it.consume { identity ->
                showAuthenticationRequestedDialog(identity)
            }
        }

        coreContext.showGreenToastEvent.observe(this) {
            it.consume { pair ->
                val message = getString(pair.first)
                val icon = pair.second
                showGreenToast(message, icon)
            }
        }

        coreContext.showRedToastEvent.observe(this) {
            it.consume { pair ->
                val message = getString(pair.first)
                val icon = pair.second
                if (!message.contains("600")) {
                    showRedToast(message, icon)
                }
                // showRedToast(message, icon)
            }
        }

        coreContext.showFormattedRedToastEvent.observe(this) {
            it.consume { pair ->
                val message = pair.first
                val icon = pair.second
                if (!message.contains("600")) {
                    showRedToast(message, icon)
                }
            }
        }

        /*
        CarConnection(this).type.observe(this) {
            val asString = when (it) {
                CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "NOT CONNECTED"
                CarConnection.CONNECTION_TYPE_PROJECTION -> "PROJECTION"
                CarConnection.CONNECTION_TYPE_NATIVE -> "NATIVE"
                else -> "UNEXPECTED ($it)"
            }
            Log.i("$TAG Car connection is [$asString]")
            val projection = it == CarConnection.CONNECTION_TYPE_PROJECTION
            coreContext.isConnectedToAndroidAuto = projection
        }*/
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        goToLatestVisitedFragment()

        // We don't want that intent to be handled upon rotation
        if (savedInstanceState == null && intent != null) {
            Log.d("$TAG savedInstanceState is null but intent isn't, handling it")
            handleIntent(intent)
        }
        sharedViewModel.currentAccountRegisterState.value = HistoryListFragment.registrationStatus
    }
    fun openOriginalApp() {
        val packageName = "com.systemstechnologies.visionpromobileapp"

        // Create an intent to open the app
        val launchIntent: Intent? = packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            // If the app is installed, start it
            startActivity(launchIntent)
        } else {
            // If the app is not installed, open the Play Store
            val playStoreIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName")
            )
            startActivity(playStoreIntent)
        }
    }

    private fun queryContentProvider(uri: Uri, projection: Array<String>): Cursor? {
        val resolver: ContentResolver = contentResolver
        try {
            val cursor = resolver.query(uri, projection, null, null, null)
            return cursor
        } catch (e: SecurityException) {
            // Handle permission error or other exceptions
            Toast.makeText(this, "Permission denied or invalid content provider", Toast.LENGTH_LONG).show()
        }
        return null
    }

    override fun onPause() {
        viewModel.enableAccountMonitoring(false)

        currentlyDisplayedAuthDialog?.dismiss()
        currentlyDisplayedAuthDialog = null
        val defaultFragmentId = HISTORY_FRAGMENT_ID
        /*
        val defaultFragmentId = when (sharedViewModel.currentlyDisplayedFragment.value) {
            R.id.contactsListFragment -> {
                CONTACTS_FRAGMENT_ID
            }
            R.id.historyListFragment -> {
                HISTORY_FRAGMENT_ID
            }
            R.id.conversationsListFragment -> {
                CHAT_FRAGMENT_ID
            }
            R.id.meetingsListFragment -> {
                MEETINGS_FRAGMENT_ID
            }
            else -> { // Default
                HISTORY_FRAGMENT_ID
            }
        }*/
        with(getPreferences(Context.MODE_PRIVATE).edit()) {
            putInt(DEFAULT_FRAGMENT_KEY, defaultFragmentId)
            apply()
        }
        Log.i("$TAG Stored [$defaultFragmentId] as default page")

        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        viewModel.enableAccountMonitoring(true)
        viewModel.checkForNewAccount()
        viewModel.updateNetworkReachability()
        viewModel.updatePostNotificationsPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("$TAG Handling new intent")
        handleIntent(intent)
    }

    @SuppressLint("RtlHardcoded")
    fun toggleDrawerMenu() {
        if (binding.drawerMenu.isDrawerOpen(Gravity.LEFT)) {
            closeDrawerMenu()
        } else {
            openDrawerMenu()
        }
    }

    fun closeDrawerMenu() {
        binding.drawerMenu.closeDrawer(binding.drawerMenuContent, true)
    }

    private fun openDrawerMenu() {
        binding.drawerMenu.openDrawer(binding.drawerMenuContent, true)
    }

    fun findNavController(): NavController {
        return findNavController(R.id.main_nav_container)
    }

    fun loadContacts() {
        coreContext.contactsManager.loadContacts(this)
    }

    private fun goToLatestVisitedFragment() {
        try {
            // Prevent navigating to default fragment upon rotation (we only want to do it on first start)
            if (intent.action == Intent.ACTION_MAIN && intent.type == null && intent.data == null) {
                if (viewModel.mainIntentHandled) {
                    Log.d(
                        "$TAG Main intent without type nor data was already handled, do nothing"
                    )
                    navigatedToDefaultFragment = true
                    return
                } else {
                    viewModel.mainIntentHandled = true
                }
            }

            val defaultFragmentId = getPreferences(MODE_PRIVATE).getInt(
                DEFAULT_FRAGMENT_KEY,
                HISTORY_FRAGMENT_ID
            )
            Log.i(
                "$TAG Trying to navigate to set default destination [$defaultFragmentId]"
            )
            try {
                val navOptionsBuilder = NavOptions.Builder()
                navOptionsBuilder.setPopUpTo(R.id.historyListFragment, true)
                navOptionsBuilder.setLaunchSingleTop(true)
                val navOptions = navOptionsBuilder.build()
                val args = bundleOf()
                when (defaultFragmentId) {
                    CONTACTS_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.contactsListFragment,
                            args,
                            navOptions
                        )
                    }
                    CHAT_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.conversationsListFragment,
                            args,
                            navOptions
                        )
                    }
                    MEETINGS_FRAGMENT_ID -> {
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.meetingsListFragment,
                            args,
                            navOptions
                        )
                    }
                    else -> {
                        Log.i("$TAG Default fragment is the same as the latest visited one")
                        navigatedToDefaultFragment = true
                    }
                }
            } catch (ise: IllegalStateException) {
                Log.e("$TAG Can't navigate to Conversations fragment: $ise")
            }
        } catch (ise: IllegalStateException) {
            Log.i("$TAG Failed to handle intent: $ise")
        }
    }

    private fun handleIntent(intent: Intent) {
        val extras = intent.extras
        val hasExtra = extras != null && !extras.isEmpty
        Log.i(
            "$TAG Handling intent action [${intent.action}], type [${intent.type}], data [${intent.data}] and has ${if (hasExtra) "extras" else "no extra"}"
        )

        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_SEND -> {
                handleSendIntent(intent, false)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                handleSendIntent(intent, true)
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data?.toString() ?: ""
                if (uri.startsWith("linphone-config:")) {
                    handleConfigIntent(uri)
                } else {
                    handleCallIntent(intent)
                }
            }
            Intent.ACTION_DIAL, Intent.ACTION_CALL -> {
                handleCallIntent(intent)
            }
            "android.intent.action.SETTING" -> {
                handleSettingVoipIntent(intent)
            }
            "android.intent.action.LOGOUT" -> {
                handleLogoutVoipIntent(intent)
            }
            Intent.ACTION_VIEW_LOCUS -> {
                val locus = Compatibility.extractLocusIdFromIntent(intent)
                if (locus != null) {
                    Log.i("$TAG Found chat room locus intent extra: $locus")
                    handleLocusOrShortcut(locus)
                }
            }
            else -> {
                handleMainIntent(intent)
            }
        }
    }

    private fun handleLocusOrShortcut(id: String) {
        Log.i("$TAG Found locus ID [$id]")
        val pair = LinphoneUtils.getLocalAndPeerSipUrisFromChatRoomId(id)
        if (pair != null) {
            val localSipUri = pair.first
            val remoteSipUri = pair.second
            Log.i(
                "$TAG Navigating to conversation with local [$localSipUri] and peer [$remoteSipUri] addresses, computed from shortcut ID"
            )
            sharedViewModel.showConversationEvent.value = Event(pair)
        }
    }

    private fun handleMainIntent(intent: Intent) {
        coreContext.postOnCoreThread { core ->
            if (corePreferences.firstLaunch) {
                Log.i("$TAG First time Linphone 6.0 has been started, showing Welcome activity")
                corePreferences.firstLaunch = false
                coreContext.postOnMainThread {
                    try {
                        startActivity(Intent(this, WelcomeActivity::class.java))
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't start activity: $ise")
                    }
                }
            } else if (core.accountList.isEmpty()) {
                Log.w("$TAG No account found, showing Assistant activity")
                coreContext.postOnMainThread {
                    try {
                        // startActivity(Intent(this, AssistantActivity::class.java))
                        startActivity(Intent(this, MainActivity::class.java))
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't start activity: $ise")
                    }
                }
            } else {
                if (intent.hasExtra("Chat")) {
                    Log.i("$TAG Intent has [Chat] extra")
                    coreContext.postOnMainThread {
                        try {
                            Log.i("$TAG Trying to go to Conversations fragment")
                            val args = intent.extras
                            val localSipUri = args?.getString("LocalSipUri", "")
                            val remoteSipUri = args?.getString("RemoteSipUri", "")
                            if (remoteSipUri.isNullOrEmpty() || localSipUri.isNullOrEmpty()) {
                                Log.w("$TAG Found [Chat] extra but no local and/or remote SIP URI!")
                            } else {
                                Log.i(
                                    "$TAG Found [Chat] extra with local [$localSipUri] and peer [$remoteSipUri] addresses"
                                )
                                val pair = Pair(localSipUri, remoteSipUri)
                                sharedViewModel.showConversationEvent.value = Event(pair)
                            }
                            args?.clear()

                            if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                                Log.w(
                                    "$TAG Current destination is already conversations list, skipping navigation"
                                )
                            } else {
                                val navOptionsBuilder = NavOptions.Builder()
                                navOptionsBuilder.setPopUpTo(
                                    findNavController().currentDestination?.id ?: R.id.historyListFragment,
                                    true
                                )
                                navOptionsBuilder.setLaunchSingleTop(true)
                                val navOptions = navOptionsBuilder.build()
                                findNavController().navigate(
                                    R.id.conversationsListFragment,
                                    args,
                                    navOptions
                                )
                            }
                        } catch (ise: IllegalStateException) {
                            Log.e("$TAG Can't navigate to Conversations fragment: $ise")
                        }
                    }
                }
            }
        }
    }

    private fun handleSendIntent(intent: Intent, multiple: Boolean) {
        val parcelablesUri = arrayListOf<Uri>()

        if (intent.type == "text/plain") {
            Log.i("$TAG Intent type is [${intent.type}], expecting text in Intent.EXTRA_TEXT")
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { extraText ->
                Log.i("$TAG Found extra text in intent, long of [${extraText.length}]")
                sharedViewModel.textToShareFromIntent.value = extraText
            }
        }

        if (multiple) {
            val parcelables =
                intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
            for (parcelable in parcelables.orEmpty()) {
                val uri = parcelable as? Uri
                if (uri != null) {
                    Log.i("$TAG Found URI [$uri] in parcelable extra list")
                    parcelablesUri.add(uri)
                }
            }
        } else {
            val uri = intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri
            if (uri != null) {
                Log.i("$TAG Found URI [$uri] in parcelable extra")
                parcelablesUri.add(uri)
            }
        }

        val list = arrayListOf<String>()
        lifecycleScope.launch {
            val deferred = arrayListOf<Deferred<String?>>()
            for (uri in parcelablesUri) {
                Log.i("$TAG Deferring copy from file [${uri.path}] to local storage")
                deferred.add(async { FileUtils.getFilePath(this@MainActivity, uri, false) })
            }

            if (binding.drawerMenu.isOpen) {
                Log.i("$TAG Drawer menu is opened, closing it")
                closeDrawerMenu()
            }

            if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                if (sharedViewModel.displayedChatRoom != null) {
                    Log.w(
                        "$TAG Closing already opened conversation to prevent attaching file in it directly"
                    )
                    sharedViewModel.hideConversationEvent.value = Event(true)
                } else {
                    Log.i("$TAG No chat room currently displayed, nothing to close")
                }
            }

            val paths = deferred.awaitAll()
            for (path in paths) {
                Log.i("$TAG Found file to share [$path] in intent")
                if (path != null) list.add(path)
            }

            if (list.isNotEmpty()) {
                sharedViewModel.filesToShareFromIntent.value = list
            } else {
                if (sharedViewModel.textToShareFromIntent.value.orEmpty().isNotEmpty()) {
                    Log.i("$TAG Found plain text to share")
                } else {
                    Log.w("$TAG Failed to find at least one file or text to share!")
                }
            }

            if (findNavController().currentDestination?.id == R.id.debugFragment) {
                Log.i(
                    "$TAG App is already started and in debug fragment, navigating to conversations list"
                )
                val pair = parseShortcutIfAny(intent)
                if (pair != null) {
                    Log.i(
                        "$TAG Navigating from debug to conversation with local [${pair.first}] and peer [${pair.second}] addresses, computed from shortcut ID"
                    )
                    sharedViewModel.showConversationEvent.value = Event(pair)
                }

                val action = DebugFragmentDirections.actionDebugFragmentToConversationsListFragment()
                findNavController().navigate(action)
            } else {
                val pair = parseShortcutIfAny(intent)
                if (pair != null) {
                    val localSipUri = pair.first
                    val remoteSipUri = pair.second
                    Log.i(
                        "$TAG Navigating to conversation with local [$localSipUri] and peer [$remoteSipUri] addresses, computed from shortcut ID"
                    )
                    sharedViewModel.showConversationEvent.value = Event(pair)
                }

                if (findNavController().currentDestination?.id == R.id.conversationsListFragment) {
                    Log.w(
                        "$TAG Current destination is already conversations list, skipping navigation"
                    )
                } else {
                    val action = ConversationsListFragmentDirections.actionGlobalConversationsListFragment()
                    findNavController().navigate(action)
                }
            }
        }
    }

    private fun parseShortcutIfAny(intent: Intent): Pair<String, String>? {
        val shortcutId = intent.getStringExtra("android.intent.extra.shortcut.ID") // Intent.EXTRA_SHORTCUT_ID
        if (shortcutId != null) {
            Log.i("$TAG Found shortcut ID [$shortcutId]")
            return LinphoneUtils.getLocalAndPeerSipUrisFromChatRoomId(shortcutId)
        } else {
            Log.i("$TAG No shortcut ID was found")
        }
        return null
    }

    private fun handleCallIntent(intent: Intent) {
        // val uri = intent.data?.toString()
        val uri = intent.getStringExtra("data")
        if (uri.isNullOrEmpty()) {
            Log.e("$TAG Intent data is null or empty, can't process [${intent.action}] intent")
            return
        }
        val displayName = intent.getStringExtra("displayname")
        // Log.i("$TAG Found URI [$uri] as data for intent [${intent.action}]")
        val sipUriToCall = when {
            uri.startsWith("tel:") -> uri.substring("tel:".length)
            uri.startsWith("callto:") -> uri.substring("callto:".length)
            uri.startsWith("sip-linphone:") -> uri.replace("sip-linphone:", "sip:")
            uri.startsWith("linphone-sip:") -> uri.replace("linphone-sip:", "sip:")
            uri.startsWith("sips-linphone:") -> uri.replace("sips-linphone:", "sips:")
            uri.startsWith("linphone-sips:") -> uri.replace("linphone-sips:", "sips:")
            else -> uri.replace("%40", "@") // Unescape @ character if needed
        }

        coreContext.postOnCoreThread {
            val address = coreContext.core.interpretUrl(
                sipUriToCall,
                LinphoneUtils.applyInternationalPrefix()
            )
            // Log.i("$TAG Interpreted SIP URI is [${address?.asStringUriOnly()}]")
            if (address != null) {
                address.setDisplayName(displayName)
                coreContext.startAudioCall(address)
            }
        }
    }

    private fun handleConfigIntent(uri: String) {
        val remoteConfigUri = uri.substring("linphone-config:".length)
        val url = when {
            remoteConfigUri.startsWith("http://") || remoteConfigUri.startsWith("https://") -> remoteConfigUri
            remoteConfigUri.startsWith("file://") -> remoteConfigUri
            else -> "https://$remoteConfigUri"
        }

        coreContext.postOnCoreThread { core ->
            core.provisioningUri = url
            Log.w("$TAG Remote provisioning URL set to [$url], restarting Core now")
            core.stop()
            Log.i("$TAG Core has been stopped, let's restart it")
            core.start()
            Log.i("$TAG Core has been restarted")
        }
    }

    private fun showAuthenticationRequestedDialog(identity: String) {
        currentlyDisplayedAuthDialog?.dismiss()

        val model = AuthRequestedDialogModel(identity)
        val dialog = DialogUtils.getAuthRequestedDialog(this, model)

        model.dismissEvent.observe(this) {
            it.consume {
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(this) {
            it.consume { password ->
                coreContext.postOnCoreThread {
                    coreContext.updateAuthInfo(password)
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        currentlyDisplayedAuthDialog = dialog
    }

    @WorkerThread
    private fun onFlexiApiTokenRequestError() {
        Log.e("Flexi API token request by push error!")
        Log.i(
            AppUtils.getString(
                R.string.assistant_account_register_push_notification_not_received_error
            )
        )
    }

    private val coreListener = object : CoreListenerStub() {

        @WorkerThread
        override fun onPushNotificationReceived(core: Core, payload: String?) {
            Log.i("Push received: [$payload]")

            val data = payload.orEmpty()
            if (data.isNotEmpty()) {
                try {
                    val cleanPayload = data
                        .replace("\\\"", "\"")
                        .replace("\"{", "{")
                        .replace("}\"", "}")
                    Log.i("Cleaned payload is: [$cleanPayload]")

                    val json = JSONObject(cleanPayload)
                    val customPayload = json.getJSONObject("custom-payload")
                    if (customPayload.has("token")) {
                        // waitForPushJob?.cancel()
                        // waitingForFlexiApiPushToken = false
                        // operationInProgress.postValue(false)

                        val token = customPayload.getString("token")
                        if (token.isNotEmpty()) {
                            Log.i(
                                "Extracted token [$token] from push payload, creating account"
                            )
                        } else {
                            Log.e("Push payload JSON object has an empty 'token'!")
                            onFlexiApiTokenRequestError()
                        }
                    } else {
                        Log.e("payload JSON object has no 'token' key!")
                        onFlexiApiTokenRequestError()
                    }
                } catch (e: JSONException) {
                    Log.e("Exception trying to parse push payload as JSON: [$e]")
                    onFlexiApiTokenRequestError()
                }
            } else {
                Log.e("Push payload is null or empty, can't extract auth token!")
                onFlexiApiTokenRequestError()
            }
        }

        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            if (account == newlyCreatedAccount) {
                if (state == RegistrationState.Ok) {
                    core.removeListener(this)
                    // Set new account as default
                    core.defaultAccount = newlyCreatedAccount
                    AvatarGenerator.isLoggedOut = false
                    HistoryListFragment.isLoggedOut = false
                    android.os.Handler(Looper.getMainLooper()).post {
                        HistoryListFragment.registrationStatus = 2
                        sharedViewModel.currentAccountRegisterState.value = 2
                    }

                    if (!firstStartCore) {
                        core.refreshRegisters()
                    }
                    firstStartCore = false
                } else if (state == RegistrationState.Failed) {
                    core.removeListener(this)
                    /*
                    val error = when (account.error) {
                        Reason.Forbidden -> {
                            AppUtils.getString(R.string.assistant_account_login_forbidden_error)
                        }
                        else -> {
                            AppUtils.getFormattedString(
                                R.string.assistant_account_login_error,
                                account.error.toString()
                            )
                        }
                    }
                    */
                    core.removeAuthInfo(newlyCreatedAuthInfo)
                    core.removeAccount(newlyCreatedAccount)
                    core.isPushNotificationEnabled = false
                    core.clearCallLogs()
                    core.clearAllAuthInfo()
                    core.clearAccounts()
                    HistoryListFragment.registrationStatus = -1

                    android.os.Handler(Looper.getMainLooper()).post {
                        sharedViewModel.currentAccountRegisterState.value = -1
                        sharedViewModel.refreshDrawerMenuAccountsListEvent.value = Event(true)
                    }

                    coreContext.postOnMainThread {
                        val navOptionsBuilder = NavOptions.Builder()
                        val navOptions = navOptionsBuilder.build()
                        val args = bundleOf()
                        findNavController().addOnDestinationChangedListener(destinationListener)
                        findNavController().navigate(
                            R.id.historyListFragment,
                            args,
                            navOptions
                        )
                    }
                } else if (state == RegistrationState.Progress) {
                    HistoryListFragment.registrationStatus = 1
                    android.os.Handler(Looper.getMainLooper()).post {
                        sharedViewModel.currentAccountRegisterState.value = 1
                        sharedViewModel.refreshDrawerMenuAccountsListEvent.value = Event(true)
                    }
                }
            }
        }
    }
    fun reConnectVoip() {
        connectVoip()
    }
    private fun handleSettingVoipIntent(intent: Intent) {
        val domainValue = intent.getStringExtra("domain").toString()

        val domain = if (domainValue.startsWith("sip:")) {
            domainValue.substring("sip:".length)
        } else {
            domainValue
        }
        val username = intent.getStringExtra("username").toString()
        VoipRegAuthId = intent.getStringExtra("regauthid").toString()
        VoipIdentity = if (VoipRegAuthId.startsWith("sip:")) {
            if (VoipRegAuthId.contains("@")) {
                VoipRegAuthId
            } else {
                "$VoipRegAuthId@$domain"
            }
        } else {
            if (VoipRegAuthId.contains("@")) {
                "sip:$VoipRegAuthId"
            } else {
                "sip:$VoipRegAuthId@$domain"
            }
        }

        VoipDisplayName = intent.getStringExtra("displayname").toString()
        VoipPassword = intent.getStringExtra("password").toString()
        VoipUserName = username
        VoipDomain = domain

        connectVoip()
    }

    fun handleLogoutVoipIntent(intent: Intent) {
        HistoryListFragment.registrationStatus = 0
        HistoryListFragment.displayAccountName = ""
        sharedViewModel.currentAccountRegisterState.value = 1
        sharedViewModel.refreshDrawerMenuAccountsListEvent.value = Event(true)

        coreContext.postOnCoreThread { core ->

            newlyCreatedAuthInfo.username = ""
            newlyCreatedAuthInfo.userid = null
            newlyCreatedAuthInfo.password = null
            newlyCreatedAuthInfo.ha1 = null
            newlyCreatedAuthInfo.realm = null
            newlyCreatedAuthInfo.domain = null

            core.removeListener(coreListener)
            core.clearAccounts()
            core.clearAllAuthInfo()
            core.clearCallLogs()

            core.pushNotificationConfig?.prid = ""
            core.pushNotificationConfig?.provider = "fcm"
            core.pushNotificationConfig?.param = "visionlink-ii-mobile-voice"

            core.isPushNotificationEnabled = false

            core.isKeepAliveEnabled = false
            AvatarGenerator.isLoggedOut = false
            HistoryListFragment.isLoggedOut = false
            core.stop()
        }

        openOriginalApp()
    }

    fun connectVoip() {
        HistoryListFragment.registrationStatus = 1
        HistoryListFragment.displayAccountName = VoipDisplayName
        sharedViewModel.currentAccountRegisterState.value = 1
        sharedViewModel.refreshDrawerMenuAccountsListEvent.value = Event(true)

        coreContext.postOnCoreThread { core ->
            if (!firstStartCore) {
                core.start()
            }

            val identityAddress = Factory.instance().createAddress(VoipIdentity)
            newlyCreatedAuthInfo = Factory.instance().createAuthInfo(
                VoipUserName,
                VoipRegAuthId,
                VoipPassword,
                null,
                null,
                VoipDomain
            )

            core.clearAccounts()
            core.clearAllAuthInfo()
            core.clearCallLogs()

            core.addAuthInfo(newlyCreatedAuthInfo)

            val accountParams = core.createAccountParams()
            var pn = core.pushNotificationConfig

            core.pushNotificationConfig?.prid = pn?.prid
            core.pushNotificationConfig?.provider = "fcm"
            core.pushNotificationConfig?.param = "visionlink-ii-mobile-voice"
            core.isPushNotificationEnabled = true
            core.isKeepAliveEnabled = true
            identityAddress?.displayName = VoipDisplayName
            accountParams.pushNotificationConfig.prid = pn?.prid
            accountParams.pushNotificationConfig.provider = "fcm"
            accountParams.pushNotificationConfig.param = "visionlink-ii-mobile-voice"
            accountParams.pushNotificationAllowed = true
            accountParams.isPublishEnabled = true

            accountParams.identityAddress = identityAddress
            accountParams.pushNotificationAllowed = true
            accountParams.remotePushNotificationAllowed = true
            val serverAddress = Factory.instance().createAddress("sip:$VoipDomain")
            serverAddress?.transport = TransportType.Tcp // TransportType.Udp
            accountParams.serverAddress = serverAddress

            accountParams.isRegisterEnabled = true
            newlyCreatedAccount = core.createAccount(accountParams)
            core.setUserAgent("Header", "Wireless nurse call")

            core.addAccount(newlyCreatedAccount)
            core.addListener(coreListener)
        }
    }
}
