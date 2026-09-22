package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    /** 处理配置页事件；手动更新等待真实结果，自动更新仍接收后台广播通知。 */
    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll ->
                            design.updateProfiles()
                        is ProfilesDesign.Request.Update ->
                            design.updateProfiles(it.profile)
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported)
                                    setActive(it.profile)
                                else
                                    design.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    /**
     * 等待每份配置实际更新完成后显示结果；单份失败不阻断其余订阅。
     * 主线程协程通过 withProfile 在 IO 调度器调用服务，取消时不显示失败提示。
     * 无论查询、下载或列表刷新是否成功，均结束按钮动画。
     *
     * @param profile 单份配置；为空时更新全部已导入的非文件配置。
     */
    private suspend fun ProfilesDesign.updateProfiles(profile: Profile? = null) {
        try {
            val profiles = if (profile != null) {
                listOf(profile)
            } else {
                withProfile { queryAll() }
                    .filter { it.imported && it.type != Profile.Type.File }
            }
            for (current in profiles) {
                try {
                    withProfile { update(current.uuid) }
                    showToast(
                        getString(R.string.toast_profile_updated_complete, current.name),
                        ToastDuration.Long
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 使用 UUID 标识订阅，不额外拼接可能含凭据的 source。
                    Log.w("Manual profile update failed: uuid=${current.uuid}, type=${current.type}", e)
                    showToast(
                        getString(R.string.toast_profile_updated_failed, current.name, e.message ?: "Unknown"),
                        ToastDuration.Long
                    ) {
                        setAction(R.string.edit) {
                            startActivity(PropertiesActivity::class.intent.setUUID(current.uuid))
                        }
                    }
                }
            }
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Manual profile update list query or refresh failed: uuid=${profile?.uuid}", e)
            showExceptionToast(e)
        } finally {
            finishUpdateAll()
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll())
        }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ){
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }
}
