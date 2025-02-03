package org.linphone.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.linphone.R
import org.linphone.ui.GenericActivity
class SplashActivity : GenericActivity() {

    private var navigatedToDefaultFragment = false
    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen()
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_splash)
        // Hide the status and navigation bars
        hideSystemBars()
        lifecycleScope.launch {
            delay(500) // 3-second delay
            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(intent)
            finish() // Close the splash activity
        }
    }
    private val destinationListener = object : NavController.OnDestinationChangedListener {
        override fun onDestinationChanged(
            controller: NavController,
            destination: NavDestination,
            arguments: Bundle?
        ) {
            navigatedToDefaultFragment = true
            controller.removeOnDestinationChangedListener(this)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    fun findNavController(): NavController {
        return findNavController(R.id.main_nav_container)
    }
}
