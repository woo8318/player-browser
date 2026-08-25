package com.playerbrowser.app.player

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.playerbrowser.app.R

/**
 * Foreground service that runs the video downloads owned by [DownloadCenter].
 *
 * Media3 requires downloads to live in a service so they survive the browser
 * screen going away mid-download, and Android requires that service to be
 * foreground with a visible notification (hence `dataSync` in the manifest).
 *
 * [getScheduler] returns null on purpose: a `PlatformScheduler` would let
 * downloads resume automatically after a reboot, but it needs RECEIVE_BOOT_COMPLETED
 * and a JobScheduler job. Not worth the extra permission — a download interrupted
 * by a reboot stays queued and resumes when the user next opens the list.
 *
 * The notification is hand-built rather than delegated to Media3's helper so this
 * file depends only on androidx.core, which the project already ships.
 */
@UnstableApi
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    UPDATE_INTERVAL_MS,
    CHANNEL_ID,
    R.string.download_channel_name,
    /* channelDescriptionResourceId = */ 0
) {

    override fun getDownloadManager(): DownloadManager = DownloadCenter.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        val active = downloads.filter { it.state == Download.STATE_DOWNLOADING }
        val percent = active
            .map { if (it.percentDownloaded.isFinite()) it.percentDownloaded.toInt() else 0 }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()
            ?: 0
        // Length is unknown until the first response lands, and for HLS until the
        // playlist is parsed — show an indeterminate bar rather than a fake 0%.
        val indeterminate = active.isEmpty() ||
            active.any { !it.percentDownloaded.isFinite() || it.contentLength <= 0L }

        val text = when {
            active.isEmpty() && downloads.isNotEmpty() -> "대기 중 ${downloads.size}개"
            active.size > 1 -> "${active.size}개 내려받는 중"
            else -> active.firstOrNull()?.request?.uri?.lastPathSegment ?: "준비 중"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("영상 다운로드")
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setProgress(100, percent, indeterminate)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 4801
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val CHANNEL_ID = "video_downloads"
    }
}
