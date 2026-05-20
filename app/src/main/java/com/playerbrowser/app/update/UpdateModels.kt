package com.playerbrowser.app.update

import java.io.File

data class UpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkSize: Long,
    val htmlUrl: String
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val info: UpdateInfo, val progress: Float, val received: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File, val info: UpdateInfo) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
