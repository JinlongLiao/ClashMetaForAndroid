package com.github.kr328.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.design.RuntimeInsightsDesign
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/** Hosts native runtime connection and rule inspection without exposing a controller port. */
class RuntimeInsightsActivity : BaseActivity<RuntimeInsightsDesign>() {
    /** Queries the selected dataset and dispatches connection-management requests. */
    override suspend fun main() {
        val insightType = when (intent.getStringExtra(EXTRA_INSIGHT_TYPE)) {
            TYPE_RULES -> RuntimeInsightsDesign.Type.Rules
            TYPE_TRAFFIC -> RuntimeInsightsDesign.Type.Traffic
            else -> RuntimeInsightsDesign.Type.Connections
        }
        val design = RuntimeInsightsDesign(this, insightType)
        setContentDesign(design)
        refreshRuntimeInsights(design, insightType)

        while (isActive) {
            select<Unit> {
                events.onReceive { event ->
                    if (event == Event.ClashStart || event == Event.ClashStop || event == Event.ServiceRecreated) {
                        refreshRuntimeInsights(design, insightType)
                    }
                }
                design.requests.onReceive { request ->
                    when (request) {
                        RuntimeInsightsDesign.Request.Refresh -> design.showRefreshing()
                        RuntimeInsightsDesign.Request.ClearTrafficHistory -> withClash {
                            clearTrafficHistory()
                        }
                        RuntimeInsightsDesign.Request.CloseAllConnections -> withClash { closeAllConnections() }
                        is RuntimeInsightsDesign.Request.CloseConnection -> withClash {
                            closeConnection(request.id)
                        }
                    }
                    refreshRuntimeInsights(design, insightType)
                }
            }
        }
    }

    /** Fetches a fresh snapshot while keeping service failures recoverable through refresh. */
    private suspend fun refreshRuntimeInsights(
        design: RuntimeInsightsDesign,
        insightType: RuntimeInsightsDesign.Type,
    ) {
        var responsePayload: String? = null
        runCatching {
            responsePayload = withClash {
                when (insightType) {
                    RuntimeInsightsDesign.Type.Connections -> queryConnections()
                    RuntimeInsightsDesign.Type.Rules -> queryRules()
                    RuntimeInsightsDesign.Type.Traffic -> queryTrafficHistory(
                        System.currentTimeMillis() - TRAFFIC_HISTORY_WINDOW_MILLIS
                    )
                }
            }
            // Rendering is part of the response contract: malformed or cross-version Binder data
            // must enter the recoverable failure state instead of crashing or appearing empty.
            design.showRuntimeInsights(checkNotNull(responsePayload))
        }.onFailure { exception ->
            Log.e(
                "Runtime insight query failed; type=$insightType, response=$responsePayload",
                exception,
            )
            design.showQueryFailure()
        }
    }

    companion object {
        /** Intent extra selecting the runtime dataset. */
        const val EXTRA_INSIGHT_TYPE = "runtime_insight_type"

        /** Extra value selecting the active rule chain. */
        const val TYPE_RULES = "rules"

        /** Extra value selecting persisted traffic history. */
        const val TYPE_TRAFFIC = "traffic"

        /** Default history window mirrors the most useful recent PC traffic view. */
        private const val TRAFFIC_HISTORY_WINDOW_MILLIS = 24L * 60L * 60L * 1000L
    }
}
