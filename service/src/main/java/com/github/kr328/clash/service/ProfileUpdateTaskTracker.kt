package com.github.kr328.clash.service

/**
 * 协调主线程接收服务请求与工作线程完成任务；所有状态由实例锁保护。
 * 空闲回调在锁内执行，避免任务刚入队便被旧任务的退出请求停止。
 * 回调必须快速返回，不能等待任务或发起网络操作。
 */
internal class ProfileUpdateTaskTracker {
    /** 尚未完成的任务数，初始为零，由 registerProfileUpdateRequest/finishProfileUpdateTask 成对维护。 */
    private var activeTaskCount = 0

    /** 最近收到的 Android 服务启动标识，用于 stopSelfResult 防止误停新请求。 */
    private var latestStartId = 0

    /**
     * 在启动异步任务前登记请求；无效请求不计数，但仍更新服务启动标识。
     * @param startId Android 提供的当前启动标识。
     * @param hasTask 当前请求是否具有可执行任务。
     * @param stopIfIdle 空闲时尝试停止对应服务实例的回调。
     */
    @Synchronized
    fun registerProfileUpdateRequest(startId: Int, hasTask: Boolean, stopIfIdle: (Int) -> Unit) {
        latestStartId = startId
        if (hasTask) {
            activeTaskCount++
        } else if (activeTaskCount == 0) {
            stopIfIdle(latestStartId)
        }
    }

    /**
     * 在任务 finally 中注销；成功、失败均须且只能调用一次。
     * @param stopIfIdle 最后一个任务完成后调用，参数为最近启动标识。
     * @throws IllegalStateException 无对应任务却重复结束时抛出，暴露生命周期错误。
     */
    @Synchronized
    fun finishProfileUpdateTask(stopIfIdle: (Int) -> Unit) {
        check(activeTaskCount > 0) { "No active profile update task" }
        activeTaskCount--
        if (activeTaskCount == 0) {
            stopIfIdle(latestStartId)
        }
    }
}
