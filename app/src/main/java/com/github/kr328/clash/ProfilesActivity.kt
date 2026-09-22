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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    /** 当前可见页面等待结果的 UUID，仅由主线程读写；退出页面时清除显示状态。 */
    private val pendingProfileUpdates = mutableSetOf<UUID>()

    /** 页面只提交更新任务，下载由服务执行；结果广播驱动提示与刷新状态。 */
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
                        Event.ActivityStop -> {
                            pendingProfileUpdates.clear()
                            design.finishUpdateAll()
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
     * 提交全部目标后立即继续页面事件循环，下载期间仍可编辑和切换配置。
     * 不可取消区间仅覆盖查询及服务启动，不包含下载；退出页面不会截断一半的提交。
     * 每份启动失败单独反馈；提交成功的请求等待完成/失败广播后结束动画。
     *
     * @param profile 单份配置；为空时更新全部已导入的非文件配置。
     */
    private suspend fun ProfilesDesign.updateProfiles(profile: Profile? = null) = withContext(NonCancellable) {
        try {
            val profiles = if (profile != null) {
                listOf(profile)
            } else {
                withProfile { queryAll() }
                    .filter { it.imported && it.type != Profile.Type.File }
            }
            pendingProfileUpdates.addAll(profiles.map { it.uuid })
            for (current in profiles) {
                try {
                    withProfile { update(current.uuid) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    pendingProfileUpdates.remove(current.uuid)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Manual profile update list query or refresh failed: uuid=${profile?.uuid}", e)
            showExceptionToast(e)
        } finally {
            if (pendingProfileUpdates.isEmpty()) {
                finishUpdateAll()
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll())
        }
    }

    /** 收到实际更新结果后释放等待状态，自动更新的广播也沿用原有提示。 */
    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            finishProfileUpdate(uuid)
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
    /** 更新失败也必须释放等待状态，允许用户修正配置并再次更新。 */
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            finishProfileUpdate(uuid)
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

    /** 在主线程移除已结束的请求，全部结果到达后停止动画。 */
    private fun finishProfileUpdate(uuid: UUID) {
        pendingProfileUpdates.remove(uuid)
        if (pendingProfileUpdates.isEmpty()) {
            design?.finishUpdateAll()
        }
    }
}
