package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import com.github.kr328.clash.design.databinding.DesignNetworkDetectionBinding
import com.github.kr328.clash.design.model.NetworkLatencyResult
import com.github.kr328.clash.design.model.PublicNetworkInfo
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

/** Displays public-network identity and endpoint latency in compact information cards. */
class NetworkDetectionDesign(context: Context) : Design<NetworkDetectionDesign.Request>(context) {
    /** User actions emitted to the hosting activity. */
    enum class Request {
        /** Runs all public-IP and latency probes again. */
        RefreshNetworkDetection,
    }

    /** View binding retained for incremental result updates. */
    private val binding = DesignNetworkDetectionBinding.inflate(context.layoutInflater, context.root, false)

    /** Root view installed by the hosting activity. */
    override val root: View
        get() = binding.root

    init {
        binding.surface = surface
        binding.activityBarLayout.applyFrom(context)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
        binding.refreshButton.setOnClickListener {
            requests.trySend(Request.RefreshNetworkDetection)
        }
    }

    /** Resets visible values and prevents duplicate requests while probes are running. */
    fun showNetworkDetectionLoading() {
        binding.refreshButton.isEnabled = false
        binding.loadingView.visibility = View.VISIBLE
        binding.publicIpView.text = "—"
        binding.locationView.setText(R.string.network_detection_waiting)
        binding.organizationView.text = ""
        showNetworkLatencyResults(emptyList())
    }

    /** Displays public network identity or a provider failure without hiding latency results. */
    fun showPublicNetworkInfo(networkInfo: PublicNetworkInfo?) {
        if (networkInfo == null) {
            binding.locationView.setText(R.string.public_network_unavailable)
            return
        }
        binding.publicIpView.text = networkInfo.ip
        binding.locationView.text = networkInfo.location
        binding.organizationView.text = networkInfo.organization
    }

    /** Displays each target result and the average of successful probes. */
    fun showNetworkLatencyResults(results: List<NetworkLatencyResult>) {
        val views = listOf(binding.googleLatencyView, binding.cloudflareLatencyView, binding.githubLatencyView)
        val targetNames = listOf("Google", "Cloudflare", "GitHub")
        views.forEachIndexed { index, view ->
            val result = results.getOrNull(index)
            val value = result?.latencyMillis?.let { "$it ms" } ?: "—"
            view.text = context.getString(R.string.network_latency_result, targetNames[index], value)
            view.setTextColor(resolveLatencyColor(result?.latencyMillis))
        }
        val successful = results.mapNotNull(NetworkLatencyResult::latencyMillis)
        binding.averageLatencyView.text = if (successful.isEmpty()) {
            "—"
        } else {
            context.getString(R.string.average_latency_result, successful.average().toLong())
        }
    }

    /** Re-enables refresh after every probe has reached a terminal state. */
    fun finishNetworkDetection() {
        binding.refreshButton.isEnabled = true
        binding.loadingView.visibility = View.GONE
    }

    /** Returns the semantic success, warning or error color for one latency value. */
    private fun resolveLatencyColor(latencyMillis: Long?): Int {
        val color = when {
            latencyMillis == null -> R.color.network_latency_neutral
            latencyMillis < 100L -> R.color.network_latency_success
            latencyMillis < 300L -> R.color.network_latency_warning
            else -> R.color.network_latency_error
        }
        return ContextCompat.getColor(context, color)
    }
}
