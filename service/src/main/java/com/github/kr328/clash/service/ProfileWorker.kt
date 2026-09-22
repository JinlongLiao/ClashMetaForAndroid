package com.github.kr328.clash.service

import android.app.PendingIntent
import android.app.Notification
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.compat.getColorCompat
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.compat.startForegroundCompat
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.id.UndefinedIds
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.util.sendProfileUpdateCompleted
import com.github.kr328.clash.service.util.sendProfileUpdateFailed
import kotlinx.coroutines.*
import java.util.*

class ProfileWorker : BaseService() {
    private val service: ProfileWorker
        get() = this

    /** 协调服务请求与任务完成，所有任务结束前保留前台服务。 */
    private val profileUpdateTaskTracker = ProfileUpdateTaskTracker()

    /** 创建通知渠道并进入前台；任务结束由计数决定，不再使用十秒延时轮询。 */
    override fun onCreate() {
        super.onCreate()

        createChannels()

        foreground()
    }

    override fun onDestroy() {
        stopForeground(true)

        super.onDestroy()
    }

    /** 先登记任务再异步执行，页面退出不影响任务；最后一个任务完成后停止服务。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val uuid = intent?.uuid
        val hasTask = when (intent?.action) {
            Intents.ACTION_PROFILE_REQUEST_UPDATE -> uuid != null
            Intents.ACTION_PROFILE_SCHEDULE_UPDATES -> true
            else -> false
        }
        profileUpdateTaskTracker.registerProfileUpdateRequest(startId, hasTask) { stopSelfResult(it) }
        if (!hasTask) {
            return START_NOT_STICKY
        }
        launch {
            try {
                if (intent?.action == Intents.ACTION_PROFILE_REQUEST_UPDATE) {
                    updateImportedProfile(requireNotNull(uuid))
                } else {
                    ProfileReceiver.rescheduleAll(service)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Profile worker failed: action=${intent?.action}, uuid=$uuid, startId=$startId", e)
                uuid?.let { sendProfileUpdateFailed(it, e.message ?: "Unknown") }
            } finally {
                profileUpdateTaskTracker.finishProfileUpdateTask { stopSelfResult(it) }
            }
        }

        return START_NOT_STICKY
    }

    /** 下载校验完成后报告结果；配置已删除也报告失败，避免可见页面永久等待。 */
    private suspend fun updateImportedProfile(uuid: UUID) {
        val imported = ImportedDao().queryByUUID(uuid)
        if (imported == null) {
            sendProfileUpdateFailed(uuid, "Profile no longer exists")
            return
        }

        try {
            processing(imported.name) {
                ProfileProcessor.update(this, imported.uuid, null)
            }

            ProfileReceiver.scheduleNext(this, imported)
            completed(imported.uuid, imported.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Profile download, validation or scheduling failed: uuid=$uuid, type=${imported.type}", e)
            failed(imported.uuid, imported.name, e.message ?: "Unknown")
        }
    }

    private fun createChannels() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(
                    SERVICE_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_service_status)).build(),
                NotificationChannelCompat.Builder(
                    STATUS_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_LOW
                ).setName(getString(R.string.profile_process_status)).build(),
                NotificationChannelCompat.Builder(
                    RESULT_CHANNEL,
                    NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName(getString(R.string.profile_process_result)).build()
            )
        )
    }

    private fun foreground() {
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setContentTitle(getString(R.string.profile_updater))
            .setContentText(getString(R.string.running))
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForegroundCompat(R.id.nf_profile_worker, notification)
    }

    /** 可选进度通知不能阻断下载；前台服务必需的通知仍由 foreground 强制建立。 */
    private suspend inline fun processing(name: String, block: () -> Unit) {
        val id = UndefinedIds.next()

        val notification = NotificationCompat.Builder(this, STATUS_CHANNEL)
            .setContentTitle(getString(R.string.profile_updating))
            .setContentText(name)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(STATUS_CHANNEL)
            .build()

        notifyProfileResult(id, notification)
        try {
            block()
        } finally {
            withContext(NonCancellable) {
                try {
                    NotificationManagerCompat.from(applicationContext).cancel(id)
                } catch (e: RuntimeException) {
                    Log.w("Profile notification cancellation failed: notificationId=$id", e)
                }
            }
        }
    }

    private fun resultBuilder(id: Int, uuid: UUID): NotificationCompat.Builder {
        val intent = PendingIntent.getActivity(
            this,
            id,
            Intent().setComponent(Components.PROPERTIES_ACTIVITY).setUUID(uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT)
        )

        return NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setColor(getColorCompat(R.color.color_clash))
            .setSmallIcon(R.drawable.ic_logo_service)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setGroup(RESULT_CHANNEL)
    }

    /** 先广播结果，附加系统通知失败时不改变已经完成的更新状态。 */
    private fun completed(uuid: UUID, name: String) {
        sendProfileUpdateCompleted(uuid)
        val id = UndefinedIds.next()

        val notification = resultBuilder(id, uuid)
            .setContentTitle(getString(R.string.update_successfully))
            .setContentText(getString(R.string.format_update_complete, name))
            .build()

        notifyProfileResult(id, notification)
    }

    /** 先通知可见页面失败，再尝试发布系统通知，避免电视通知限制吞掉结果。 */
    private fun failed(uuid: UUID, name: String, reason: String) {
        sendProfileUpdateFailed(uuid, reason)
        val id = UndefinedIds.next()

        val content = getString(R.string.format_update_failure, name, reason)

        val notification = resultBuilder(id, uuid)
            .setContentTitle(getString(R.string.update_failure))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .build()

        notifyProfileResult(id, notification)
    }

    /** 可选通知仅用于展示；设备拒绝通知时记录完整异常，继续实际更新流程。 */
    private fun notifyProfileResult(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: RuntimeException) {
            Log.w("Profile notification rejected: notificationId=$id", e)
        }
    }

    companion object {
        private const val SERVICE_CHANNEL = "profile_service_channel"
        private const val STATUS_CHANNEL = "profile_status_channel"
        private const val RESULT_CHANNEL = "profile_result_channel"
    }

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }
}
