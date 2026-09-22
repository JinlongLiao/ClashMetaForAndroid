package com.github.kr328.clash

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.pendingIntentFlags
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.service.ProfileReceiver
import com.github.kr328.clash.service.ProfileWorker
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

/** 验证手动更新直接交给服务，并保留已有自动更新闹钟，覆盖新旧 Android 行为。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], manifest = Config.NONE, application = Application::class)
class ProfileUpdateDispatchTest {
    /** 只构造调度所需的订阅模型，不访问数据库或真实网络。 */
    private val imported = Imported(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "test", Profile.Type.Url, "https://example.invalid/profile", 900_000,
        0, 0, 0, 0, 0,
    )

    /** 旧实现依赖广播转发；新实现直接指定前台服务及目标 UUID。 */
    @Test
    fun manualUpdateStartsWorkerWithoutBroadcast() {
        val application = RuntimeEnvironment.getApplication()
        Global.init(application)
        ProfileReceiver.schedule(application, imported)
        val applicationShadow = shadowOf(application)
        val startedService = applicationShadow.nextStartedService
        assertEquals(ProfileWorker::class.java.name, startedService.component?.className)
        assertEquals(Intents.ACTION_PROFILE_REQUEST_UPDATE, startedService.action)
        assertEquals(imported.uuid, startedService.uuid)
        assertTrue(applicationShadow.broadcastIntents.isEmpty())
    }

    /** 在下载成功并重新调度前，原有自动更新机会不能因手动请求而被取消。 */
    @Test
    fun manualUpdatePreservesExistingAutomaticAlarm() {
        val application = RuntimeEnvironment.getApplication()
        Global.init(application)
        val alarmManager = application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduledIntent = PendingIntent.getBroadcast(
            application, 0,
            Intent(Intents.ACTION_PROFILE_REQUEST_UPDATE)
                .setComponent(ProfileReceiver::class.componentName).setUUID(imported.uuid),
            pendingIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT),
        )
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 60_000, scheduledIntent)
        ProfileReceiver.schedule(application, imported)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
        assertEquals(scheduledIntent, requireNotNull(shadowOf(alarmManager).nextScheduledAlarm).operation)
    }
}
