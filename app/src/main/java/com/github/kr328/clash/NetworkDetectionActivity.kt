package com.github.kr328.clash

import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.NetworkDetectionDesign
import com.github.kr328.clash.design.model.NetworkLatencyResult
import com.github.kr328.clash.design.model.PublicNetworkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Hosts public-IP lookup and parallel endpoint latency probes. */
class NetworkDetectionActivity : BaseActivity<NetworkDetectionDesign>() {
    /** Stable targets synchronized from the desktop network page. */
    private val latencyTargets: List<Pair<String, String>>
        get() = buildList {
            add("Google" to "https://www.google.com/generate_204")
            add("Cloudflare" to "https://www.cloudflare.com/cdn-cgi/trace")
            add("GitHub" to "https://github.com/")
            uiStore.networkDetectionTargets.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
                val parts = line.split('|', limit = 2)
                if (parts.size == 2 && parts[1].startsWith("https://")) {
                    add(parts[0].ifBlank { parts[1] } to parts[1])
                }
            }
        }

    /** Creates the page, performs its initial detection, and handles explicit refresh requests. */
    override suspend fun main() {
        val design = NetworkDetectionDesign(this)
        setContentDesign(design)
        runNetworkDetection(design)
        while (isActive) {
            select<Unit> {
                events.onReceive { }
                design.requests.onReceive { request ->
                    when (request) {
                        NetworkDetectionDesign.Request.RefreshNetworkDetection -> runNetworkDetection(design)
                    }
                }
            }
        }
    }

    /** Runs public-IP and latency requests concurrently and always restores the refresh action. */
    private suspend fun runNetworkDetection(design: NetworkDetectionDesign) {
        design.showNetworkDetectionLoading()
        try {
            withContext(Dispatchers.IO) {
                val publicNetworkInfo = async { queryPublicNetworkInfo() }
                val latencyResults = latencyTargets.map { (name, url) ->
                    async { measureNetworkLatency(name, url) }
                }
                val networkInfo = publicNetworkInfo.await()
                val results = latencyResults.awaitAll()
                withContext(Dispatchers.Main) {
                    design.showPublicNetworkInfo(networkInfo)
                    design.showNetworkLatencyResults(results)
                }
            }
        } finally {
            design.finishNetworkDetection()
        }
    }

    /** Queries multiple desktop-aligned providers in order and returns the first usable public IP. */
    private fun queryPublicNetworkInfo(): PublicNetworkInfo? {
        val providers = listOf("https://ipwho.is/", "https://api.ip.sb/geoip", "https://api.ipify.org?format=json")
        providers.forEach { providerUrl ->
            try {
                return openHttpConnection(providerUrl).useConnection { connection ->
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    if (!json.optBoolean("success", true)) {
                        throw IllegalStateException("Provider reported an unsuccessful response")
                    }
                    val location = listOf(json.optString("country"), json.optString("region"), json.optString("city"))
                        .filter(String::isNotBlank)
                        .joinToString(" · ")
                    val organization = json.optJSONObject("connection")?.optString("org").orEmpty()
                        .ifEmpty { json.optString("organization") }
                    PublicNetworkInfo(json.optString("ip"), location, organization)
                }
            } catch (exception: Exception) {
                Log.e("Network detection public IP provider failed; url=$providerUrl", exception)
            }
        }
        return null
    }

    /** Measures time through receipt of an HTTP status; failures are represented by null. */
    private fun measureNetworkLatency(name: String, url: String): NetworkLatencyResult {
        val latency = try {
            val startedAt = SystemClock.elapsedRealtime()
            openHttpConnection(url).useConnection { connection -> connection.responseCode }
            SystemClock.elapsedRealtime() - startedAt
        } catch (exception: Exception) {
            Log.e(
                "Network detection failed during latency probe; target=$name, url=$url",
                exception,
            )
            null
        }
        return NetworkLatencyResult(name, latency)
    }

    /** Creates a bounded, redirect-aware connection without retaining cookies or credentials. */
    private fun openHttpConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
    }

    /** Disconnects an HTTP connection after [block] completes or fails. */
    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }
}
