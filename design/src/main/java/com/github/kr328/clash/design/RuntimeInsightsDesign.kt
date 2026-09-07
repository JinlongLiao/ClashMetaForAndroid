package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Displays live connections or the active rule chain from the embedded core. */
class RuntimeInsightsDesign(
    context: Context,
    private val insightType: Type,
) : Design<RuntimeInsightsDesign.Request>(context) {
    /** Dataset rendered by this screen. */
    enum class Type {
        Connections,
        Rules,
        Traffic,
    }

    /** Actions requiring access to the remote core service. */
    sealed class Request {
        data object Refresh : Request()
        data object ClearTrafficHistory : Request()
        data object CloseAllConnections : Request()
        data class CloseConnection(val id: String) : Request()
    }

    /** Vertical content container rebuilt for every point-in-time snapshot. */
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(40))
    }

    /** Scrollable root view used by the activity. */
    override val root: View = ScrollView(context).apply {
        isFillViewport = true
        addView(content)
    }

    init {
        renderHeader()
        addStateText(R.string.loading)
    }

    /** Replaces the visible snapshot using controller-compatible JSON from the native bridge. */
    fun showRuntimeInsights(payload: String) {
        content.removeAllViews()
        renderHeader()
        val rootElement = Json.parseToJsonElement(payload)
        val items = when (insightType) {
            Type.Connections -> requireNotNull(
                (rootElement as? JsonObject)?.get("connections") as? JsonArray,
            ) { "Connections payload must contain a JSON array" }
            Type.Rules, Type.Traffic -> requireNotNull(rootElement as? JsonArray) {
                "$insightType payload must be a JSON array"
            }
        }
        addToolbar(items.isNotEmpty())
        if (items.isEmpty()) {
            addStateText(
                when (insightType) {
                    Type.Connections -> R.string.no_active_connections
                    Type.Rules -> R.string.no_rules
                    Type.Traffic -> R.string.no_traffic_history
                }
            )
        } else {
            items.forEach { element ->
                when (insightType) {
                    Type.Connections -> addConnectionCard(element.jsonObject)
                    Type.Rules -> addRuleCard(element.jsonObject)
                    Type.Traffic -> addTrafficCard(element.jsonObject)
                }
            }
        }
    }

    /** Shows a recoverable query error without discarding the refresh action. */
    fun showQueryFailure() {
        content.removeAllViews()
        renderHeader()
        addToolbar(false)
        addStateText(R.string.runtime_insights_unavailable)
    }

    /** Replaces stale content immediately so tapping refresh always has visible feedback. */
    fun showRefreshing() {
        content.removeAllViews()
        renderHeader()
        addStateText(R.string.refreshing)
    }

    /** Renders the page title and its data-source explanation. */
    private fun renderHeader() {
        addText(
            when (insightType) {
                Type.Connections -> R.string.connections
                Type.Rules -> R.string.rules
                Type.Traffic -> R.string.traffic_history
            },
            28f,
            Typeface.BOLD,
        )
        addText(
            when (insightType) {
                Type.Connections -> R.string.connections_snapshot_summary
                Type.Rules -> R.string.rules_snapshot_summary
                Type.Traffic -> R.string.traffic_history_range_summary
            },
            14f,
            Typeface.NORMAL,
        ).apply {
            alpha = 0.68f
            setPadding(0, dp(6), 0, dp(14))
        }
    }

    /** Renders refresh and destructive connection-management actions without crowding. */
    private fun addToolbar(hasItems: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.refresh)
            minHeight = dp(48)
            setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { requests.trySend(Request.Refresh) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        if (insightType == Type.Connections && hasItems) {
            row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.close_all_connections)
                minHeight = dp(48)
                setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorError))
                setOnClickListener { confirmCloseAllConnections() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        if (insightType == Type.Traffic && hasItems) {
            row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.clear_traffic_history)
                minHeight = dp(48)
                setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorError))
                setOnClickListener { confirmClearTrafficHistory() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    /** Renders a connection card with endpoint, process, chain and traffic counters. */
    private fun addConnectionCard(item: JsonObject) {
        val metadata = item["metadata"]?.jsonObject ?: JsonObject(emptyMap())
        val id = item.text("id")
        val host = metadata.text("host").ifEmpty { metadata.text("destinationIP") }
        val process = metadata.text("process").ifEmpty { context.getString(R.string.unknown) }
        val chains = item["chains"]?.jsonArray?.joinToString(" → ") { it.jsonPrimitive.content }.orEmpty()
        addCard(
            title = host.ifEmpty { context.getString(R.string.unknown) },
            summary = "$process\n$chains\n↑ ${item.text("upload")}  ↓ ${item.text("download")}",
            actionText = context.getString(R.string.close_connection),
        ) {
            AlertDialog.Builder(context)
                .setTitle(host)
                .setMessage(R.string.close_connection_confirmation)
                .setPositiveButton(R.string.ok) { _, _ -> requests.trySend(Request.CloseConnection(id)) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** Renders an ordered rule card with policy target and optional hit statistics. */
    private fun addRuleCard(item: JsonObject) {
        val hits = item["extra"]?.jsonObject?.text("hitCount").orEmpty()
        val hitText = if (hits.isEmpty()) "" else " · ${context.getString(R.string.rule_hit_count, hits)}"
        addCard(
            title = "#${item.text("index")} · ${item.text("type")}",
            summary = "${item.text("payload")}\n→ ${item.text("proxy")}$hitText",
        )
    }

    /** Renders one chronological traffic bucket using human-readable byte totals. */
    private fun addTrafficCard(item: JsonObject) {
        val bucket = item.text("bucket").toLongOrNull() ?: 0L
        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
            .format(java.util.Date(bucket))
        addCard(
            title = time,
            summary = context.getString(
                R.string.traffic_history_bucket,
                formatBytes(item.text("upload").toLongOrNull() ?: 0L),
                formatBytes(item.text("download").toLongOrNull() ?: 0L),
            ),
        )
    }

    /** Formats persisted byte counts without depending on the packed live-traffic representation. */
    private fun formatBytes(value: Long): String {
        return when {
            value >= 1024L * 1024L * 1024L -> String.format("%.2f GiB", value / 1024.0 / 1024.0 / 1024.0)
            value >= 1024L * 1024L -> String.format("%.2f MiB", value / 1024.0 / 1024.0)
            value >= 1024L -> String.format("%.2f KiB", value / 1024.0)
            else -> "$value B"
        }
    }

    /** Adds one elevated, rounded information card matching the dashboard visual language. */
    private fun addCard(
        title: String,
        summary: String,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = summary
                textSize = 13f
                alpha = 0.72f
                setPadding(0, dp(7), 0, 0)
            })
            if (actionText != null && onAction != null) {
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = actionText
                    minHeight = dp(48)
                    setOnClickListener { onAction() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                })
            }
        }
        content.addView(MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = context.resolveThemedColor(com.google.android.material.R.attr.colorControlHighlight)
            addView(body)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    /** Adds a localized state label to the empty page. */
    private fun addStateText(textResource: Int) {
        addText(textResource, 15f, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            alpha = 0.65f
            setPadding(0, dp(48), 0, dp(48))
        }
    }

    /** Adds a localized text view and returns it for contextual styling. */
    private fun addText(textResource: Int, size: Float, style: Int): TextView {
        return TextView(context).apply {
            setText(textResource)
            textSize = size
            setTypeface(typeface, style)
            content.addView(this)
        }
    }

    /** Confirms the only bulk destructive action exposed by this screen. */
    private fun confirmCloseAllConnections() {
        AlertDialog.Builder(context)
            .setTitle(R.string.close_all_connections)
            .setMessage(R.string.close_all_connections_confirmation)
            .setPositiveButton(R.string.ok) { _, _ -> requests.trySend(Request.CloseAllConnections) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Confirms deletion because cleared traffic buckets cannot be reconstructed. */
    private fun confirmClearTrafficHistory() {
        AlertDialog.Builder(context)
            .setTitle(R.string.clear_traffic_history)
            .setMessage(R.string.clear_traffic_history_confirmation)
            .setPositiveButton(R.string.ok) { _, _ -> requests.trySend(Request.ClearTrafficHistory) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Reads an optional primitive JSON field without failing the whole snapshot. */
    private fun JsonObject.text(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    /** Converts density-independent pixels using the current display density. */
    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
