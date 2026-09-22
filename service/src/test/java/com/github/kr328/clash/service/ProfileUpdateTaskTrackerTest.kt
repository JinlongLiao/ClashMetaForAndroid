package com.github.kr328.clash.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 验证并发完成、新增请求及无效请求都不会导致前台更新服务提前退出。 */
class ProfileUpdateTaskTrackerTest {
    /** 老任务结束时仍有新任务，直到最后一个任务完成才停止最新 startId。 */
    @Test
    fun waitsForAllTasksBeforeStoppingLatestStart() {
        val tracker = ProfileUpdateTaskTracker()
        val stops = mutableListOf<Int>()
        tracker.registerProfileUpdateRequest(1, true) { stops.add(it) }
        tracker.registerProfileUpdateRequest(2, true) { stops.add(it) }
        tracker.finishProfileUpdateTask { stops.add(it) }
        assertTrue(stops.isEmpty())
        tracker.registerProfileUpdateRequest(3, true) { stops.add(it) }
        tracker.finishProfileUpdateTask { stops.add(it) }
        assertTrue(stops.isEmpty())
        tracker.finishProfileUpdateTask { stops.add(it) }
        assertEquals(listOf(3), stops)
    }

    /** 无效 Intent 不停止正在下载的服务，但必须更新后续退出所用的启动标识。 */
    @Test
    fun invalidRequestDoesNotInterruptActiveTask() {
        val tracker = ProfileUpdateTaskTracker()
        val stops = mutableListOf<Int>()
        tracker.registerProfileUpdateRequest(1, true) { stops.add(it) }
        tracker.registerProfileUpdateRequest(2, false) { stops.add(it) }
        assertTrue(stops.isEmpty())
        tracker.finishProfileUpdateTask { stops.add(it) }
        assertEquals(listOf(2), stops)
    }

    /** 没有任务时立即退出，不保留空转的前台通知。 */
    @Test
    fun invalidIdleRequestStopsImmediately() {
        val tracker = ProfileUpdateTaskTracker()
        val stops = mutableListOf<Int>()
        tracker.registerProfileUpdateRequest(4, false) { stops.add(it) }
        assertEquals(listOf(4), stops)
    }

    /** 多线程同时完成任务只产生一次退出请求，且不会丢失计数。 */
    @Test
    fun concurrentCompletionStopsExactlyOnce() {
        val tracker = ProfileUpdateTaskTracker()
        val stops = mutableListOf<Int>()
        val executor = Executors.newFixedThreadPool(4)
        val completed = CountDownLatch(100)
        try {
            for (id in 1..100) {
                tracker.registerProfileUpdateRequest(id, true) { stops.add(it) }
            }
            repeat(100) {
                executor.submit {
                    try {
                        tracker.finishProfileUpdateTask { stops.add(it) }
                    } finally {
                        completed.countDown()
                    }
                }
            }
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(100), stops)
        } finally {
            executor.shutdownNow()
        }
    }
}
