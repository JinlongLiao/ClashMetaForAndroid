package com.github.kr328.clash.core

import android.os.SystemClock
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.core.util.parseInetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

object Clash {
    /**
     * Assigns a monotonically increasing identifier to each diagnostic native call.
     * An unmatched begin record means the process stopped before that JNI call returned.
     */
    private val nativeCallSequence = AtomicLong(0)

    enum class OverrideSlot {
        Persist, Session
    }

    private val ConfigurationOverrideJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun reset() {
        traceNativeCall("reset") {
            Bridge.nativeReset()
        }
    }

    fun forceGc() {
        traceNativeCall("forceGc") {
            Bridge.nativeForceGc()
        }
    }

    fun suspendCore(suspended: Boolean) {
        traceNativeCall("suspendCore(suspended=$suspended)") {
            Bridge.nativeSuspend(suspended)
        }
    }

    fun queryTunnelState(): TunnelState {
        val json = Bridge.nativeQueryTunnelState()

        return Json.decodeFromString(TunnelState.serializer(), json)
    }

    fun queryTrafficNow(): Traffic {
        return traceNativeCall("queryTrafficNow") {
            Bridge.nativeQueryTrafficNow()
        }
    }

    fun queryTrafficTotal(): Traffic {
        return traceNativeCall("queryTrafficTotal") {
            Bridge.nativeQueryTrafficTotal()
        }
    }

    /** Returns the current connection snapshot as the native controller-compatible JSON payload. */
    fun queryConnections(): String {
        return Bridge.nativeQueryConnections()
    }

    /** Closes one active connection identified by its tracker ID. */
    fun closeConnection(id: String) {
        Bridge.nativeCloseConnection(id)
    }

    /** Closes every active connection managed by the embedded core. */
    fun closeAllConnections() {
        Bridge.nativeCloseAllConnections()
    }

    /** Returns the ordered active rule chain as JSON without exposing a controller socket. */
    fun queryRules(): String {
        return Bridge.nativeQueryRules()
    }

    fun notifyDnsChanged(dns: List<String>) {
        traceNativeCall("notifyDnsChanged(count=${dns.size})") {
            Bridge.nativeNotifyDnsChanged(dns.toSet().joinToString(separator = ","))
        }
    }

    fun notifyTimeZoneChanged(name: String, offset: Int) {
        traceNativeCall("notifyTimeZoneChanged") {
            Bridge.nativeNotifyTimeZoneChanged(name, offset)
        }
    }

    fun notifyInstalledAppsChanged(uids: List<Pair<Int, String>>) {
        val uidList = uids.joinToString(separator = ",") { "${it.first}:${it.second}" }

        traceNativeCall("notifyInstalledAppsChanged(count=${uids.size})") {
            Bridge.nativeNotifyInstalledAppChanged(uidList)
        }
    }

    fun startTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        markSocket: (Int) -> Boolean,
        querySocketUid: (protocol: Int, source: InetSocketAddress, target: InetSocketAddress) -> Int
    ) {
        traceNativeCall("startTun(stack=$stack)") {
            Bridge.nativeStartTun(fd, stack, gateway, portal, dns, object : TunInterface {
                override fun markSocket(fd: Int) {
                    markSocket(fd)
                }

                override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                    return querySocketUid(
                        protocol,
                        parseInetSocketAddress(source),
                        parseInetSocketAddress(target)
                    )
                }
            })
        }
    }

    fun stopTun() {
        traceNativeCall("stopTun") {
            Bridge.nativeStopTun()
        }
    }

    fun startHttp(listenAt: String): String? {
        return Bridge.nativeStartHttp(listenAt)
    }

    fun stopHttp() {
        Bridge.nativeStopHttp()
    }

    fun queryGroupNames(excludeNotSelectable: Boolean): List<String> {
        val names = Json.Default.decodeFromString(
            JsonArray.serializer(),
            Bridge.nativeQueryGroupNames(excludeNotSelectable)
        )

        return names.map {
            require(it.jsonPrimitive.isString)

            it.jsonPrimitive.content
        }
    }

    fun queryGroup(name: String, sort: ProxySort): ProxyGroup {
        return Bridge.nativeQueryGroup(name, sort.name)
            ?.let { Json.Default.decodeFromString(ProxyGroup.serializer(), it) }
            ?: ProxyGroup("Unknown", emptyList(), "")
    }

    fun healthCheck(name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeHealthCheck(this, name)
        }
    }

    fun healthCheckAll() {
        traceNativeCall("healthCheckAll") {
            Bridge.nativeHealthCheckAll()
        }
    }

    fun patchSelector(selector: String, name: String): Boolean {
        return traceNativeCall("patchSelector") {
            Bridge.nativePatchSelector(selector, name)
        }
    }

    /**
     * Records the boundary of a JNI call without logging request content or credentials.
     * Native aborts cannot be caught by Kotlin, so the last begin record intentionally remains
     * unmatched and identifies the operation that was executing when the process terminated.
     *
     * @param operation stable operation name and non-sensitive diagnostic dimensions.
     * @param block native bridge invocation to execute synchronously.
     * @return the value returned by the native bridge.
     * @throws Throwable preserves the original native bridge failure after logging its stack.
     */
    private inline fun <T> traceNativeCall(operation: String, block: () -> T): T {
        val sequence = nativeCallSequence.incrementAndGet()
        val uptimeMillis = SystemClock.elapsedRealtime()
        val threadName = Thread.currentThread().name

        Log.d(
            "NativeCall begin: sequence=$sequence, operation=$operation, " +
                "uptimeMillis=$uptimeMillis, thread=$threadName"
        )

        return try {
            block().also {
                Log.d(
                    "NativeCall success: sequence=$sequence, operation=$operation, " +
                        "elapsedMillis=${SystemClock.elapsedRealtime() - uptimeMillis}, " +
                        "thread=$threadName"
                )
            }
        } catch (exception: Throwable) {
            Log.e(
                "NativeCall failed: sequence=$sequence, operation=$operation, " +
                    "elapsedMillis=${SystemClock.elapsedRealtime() - uptimeMillis}, " +
                    "thread=$threadName",
                exception
            )
            throw exception
        }
    }

    fun fetchAndValid(
        path: File,
        url: String,
        force: Boolean,
        reportStatus: (FetchStatus) -> Unit
    ): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeFetchAndValid(
                object : FetchCallback {
                    override fun report(statusJson: String) {
                        reportStatus(
                            Json.Default.decodeFromString(
                                FetchStatus.serializer(),
                                statusJson
                            )
                        )
                    }

                    override fun complete(error: String?) {
                        if (error != null)
                            completeExceptionally(ClashException(error))
                        else
                            complete(Unit)
                    }
                },
                path.absolutePath,
                url,
                force
            )
        }
    }

    fun load(path: File): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeLoad(this, path.absolutePath)
        }
    }

    fun queryProviders(): List<Provider> {
        val providers =
            Json.Default.decodeFromString(JsonArray.serializer(), Bridge.nativeQueryProviders())

        return List(providers.size) {
            Json.Default.decodeFromJsonElement(Provider.serializer(), providers[it])
        }
    }

    fun updateProvider(type: Provider.Type, name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeUpdateProvider(this, type.toString(), name)
        }
    }

    fun queryOverride(slot: OverrideSlot): ConfigurationOverride {
        return try {
            ConfigurationOverrideJson.decodeFromString(
                ConfigurationOverride.serializer(),
                Bridge.nativeReadOverride(slot.ordinal)
            )
        } catch (e: Exception) {
            ConfigurationOverride()
        }
    }

    fun patchOverride(slot: OverrideSlot, configuration: ConfigurationOverride) {
        Bridge.nativeWriteOverride(
            slot.ordinal,
            ConfigurationOverrideJson.encodeToString(
                ConfigurationOverride.serializer(),
                configuration
            )
        )
    }

    fun clearOverride(slot: OverrideSlot) {
        Bridge.nativeClearOverride(slot.ordinal)
    }

    fun queryConfiguration(): UiConfiguration {
        return Json.Default.decodeFromString(
            UiConfiguration.serializer(),
            Bridge.nativeQueryConfiguration()
        )
    }

    fun subscribeLogcat(): ReceiveChannel<LogMessage> {
        return Channel<LogMessage>(32).apply {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    trySend(Json.decodeFromString(LogMessage.serializer(), jsonPayload))
                }
            })
        }
    }

    fun setAgeSecretKey(key: String?) {
        Bridge.nativeSetAgeSecretKey(key)
    }

    fun genX25519KeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenX25519KeyPair()))
    }

    fun genHybridKeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenHybridKeyPair()))
    }

    fun veritySecretKeys(vararg secretKeys: String): Boolean {
        return Bridge.nativeVeritySecretKeys(secretKeys.firstOrNull() ?: "")
    }

    fun toPublicKeys(vararg secretKeys: String): List<String> {
        return Bridge.nativeToPublicKeys(secretKeys.firstOrNull() ?: "")
            ?.let { Json.Default.decodeFromString(ListSerializer(String.serializer()), it) }
            ?: emptyList()
    }

    fun verityPublicKeys(vararg publicKeys: String): Boolean {
        return Bridge.nativeVerityPublicKeys(publicKeys.firstOrNull() ?: "")
    }

    private fun parseAgeKeyPair(value: String): AgeKeyPair {
        return Json.Default.decodeFromString(AgeKeyPair.serializer(), value)
    }
}
