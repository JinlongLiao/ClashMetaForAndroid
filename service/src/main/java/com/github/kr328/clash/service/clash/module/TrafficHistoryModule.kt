package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.util.trafficDownloadBytes
import com.github.kr328.clash.core.util.trafficUploadBytes
import com.github.kr328.clash.service.data.TrafficHistoryStore
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/** Samples cumulative core counters and stores positive deltas in bounded history buckets. */
class TrafficHistoryModule(service: Service) : Module<Unit>(service) {
    private val store = TrafficHistoryStore(service)

    /**
     * Samples cumulative counters every ten seconds.
     *
     * The initial snapshot belongs to the current core runtime and is persisted immediately so
     * the history page is useful before another traffic change occurs. If the core resets its
     * counters while the service remains alive, the current value becomes the first delta of the
     * new counter generation instead of being discarded as a negative delta.
     */
    override suspend fun run() {
        try {
            coroutineScope {
                // Core configuration and the TUN device are initialized concurrently with modules.
                // Delay the first native query until the runtime is fully started; querying during
                // that startup window can abort the Go runtime and terminate the VPN process.
                var previous: Long? = null
                for (ignored in ticker(TimeUnit.SECONDS.toMillis(10))) {
                    val current = Clash.queryTrafficTotal()
                    val currentUpload = current.trafficUploadBytes()
                    val currentDownload = current.trafficDownloadBytes()
                    val previousUpload = previous?.trafficUploadBytes() ?: currentUpload
                    val previousDownload = previous?.trafficDownloadBytes() ?: currentDownload
                    ensureCurrentTrafficBucket()
                    recordTrafficSnapshot(
                        if (currentUpload >= previousUpload) currentUpload - previousUpload else currentUpload,
                        if (currentDownload >= previousDownload) currentDownload - previousDownload else currentDownload,
                    )
                    previous = current
                }
            }
        } finally {
            store.close()
        }
    }

    /** Persists one derived snapshot without allowing history failures to stop the VPN runtime. */
    private fun recordTrafficSnapshot(upload: Long, download: Long) {
        try {
            store.recordTrafficDelta(System.currentTimeMillis(), upload, download)
        } catch (exception: Exception) {
            Log.e(
                "Traffic history persistence failed; upload=$upload, download=$download",
                exception,
            )
        }
    }

    /** Creates an empty current bucket so a zero-traffic core still exposes sampling evidence. */
    private fun ensureCurrentTrafficBucket() {
        try {
            store.ensureTrafficBucket(System.currentTimeMillis())
        } catch (exception: Exception) {
            Log.e("Traffic history bucket initialization failed", exception)
        }
    }
}
