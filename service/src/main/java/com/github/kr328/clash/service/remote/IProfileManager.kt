package com.github.kr328.clash.service.remote

import com.github.kr328.clash.service.model.Profile
import com.github.kr328.kaidl.BinderInterface
import java.util.*

@BinderInterface
interface IProfileManager {
    suspend fun create(type: Profile.Type, name: String, source: String = "", ageSecretKey: String? = null): UUID
    suspend fun clone(uuid: UUID): UUID
    suspend fun commit(uuid: UUID, callback: IFetchObserver? = null)
    suspend fun release(uuid: UUID)
    suspend fun delete(uuid: UUID)
    suspend fun patch(uuid: UUID, name: String, source: String, interval: Long, ageSecretKey: String?)
    /**
     * 下载、校验并保存已导入配置，完成后返回；失败通过异常返回调用方。
     * 不修改编辑中的草稿，重复调用会重新下载，更新操作在服务端串行执行。
     *
     * @param uuid 已导入配置的唯一标识。
     */
    suspend fun update(uuid: UUID)
    suspend fun queryByUUID(uuid: UUID): Profile?
    suspend fun queryAll(): List<Profile>
    suspend fun queryActive(): Profile?
    suspend fun setActive(profile: Profile)
}
