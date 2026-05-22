package com.playerbrowser.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.playerbrowser.app.cast.CastSessionBridge
import com.playerbrowser.app.ui.BrowserViewModel
import com.playerbrowser.app.ui.RootNavigation

class MainActivity : ComponentActivity() {
    private val viewModel: BrowserViewModel by viewModels()
    private val castBridge: CastSessionBridge by lazy {
        CastSessionBridge(this) {
            val s = viewModel.state.value
            s.currentUrl to s.currentTitle
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RootNavigation(viewModel = viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        castBridge.attach()
    }

    override fun onPause() {
        super.onPause()
        castBridge.detach()
    }
}
