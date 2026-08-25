package com.playerbrowser.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.playerbrowser.app.cast.CastSessionBridge
import com.playerbrowser.app.player.DownloadCenter
import com.playerbrowser.app.ui.BrowserViewModel
import com.playerbrowser.app.ui.RootNavigation

// AppCompatActivity (a FragmentActivity) is required by the Cast
// MediaRouteButton: tapping it opens a device-picker dialog that needs the
// host's support FragmentManager. A plain ComponentActivity crashes with
// "The activity must be a subclass of FragmentActivity".
class MainActivity : AppCompatActivity() {
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

    override fun onStart() {
        super.onStart()
        // Downloads keep their bytes across a process death but nothing restarts
        // them - there is no scheduler by design. This is the cheapest point at
        // which the app is certainly in the foreground, which is what Android
        // requires before a foreground service may be started.
        DownloadCenter.resumeInterrupted(this)
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
