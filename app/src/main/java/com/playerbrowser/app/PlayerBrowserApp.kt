package com.playerbrowser.app

import android.app.Application
import com.playerbrowser.app.data.BrowserRepository

class PlayerBrowserApp : Application() {
    val repository: BrowserRepository by lazy { BrowserRepository.get(this) }
}
