package com.frameender.pocketstash

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.frameender.pocketstash.ui.nav.AppNav
import com.frameender.pocketstash.ui.theme.Ink
import com.frameender.pocketstash.ui.theme.PocketStashTheme

class MainActivity : ComponentActivity() {
    companion object {
        /** Intent extra naming a screen to open, e.g. "updates" from the update notification. */
        const val EXTRA_OPEN = "open"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val app = this.container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            PocketStashTheme {
                val settings by app.settings.collectAsState()
                Box(Modifier.fillMaxSize().background(Ink.Bg)) {
                    val s = settings
                    if (s != null) {
                        // Decide the start screen once, from the first settings read.
                        val configured = remember { s.isConfigured }
                        key(configured) { AppNav(configured) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(EXTRA_OPEN)?.let { container.pendingRoute.value = it }
    }
}
