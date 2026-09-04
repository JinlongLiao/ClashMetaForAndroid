package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.core.util.trafficTotal
import com.github.kr328.clash.core.util.trafficDownload
import com.github.kr328.clash.core.util.trafficUpload
import com.github.kr328.clash.design.databinding.DesignAboutBinding
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainDesign(
    context: Context,
    private val useSemanticStatusTheme: Boolean,
) : Design<MainDesign.Request>(context) {
    enum class Request {
        ToggleStatus,
        OpenProxy,
        OpenProfiles,
        OpenProviders,
        OpenConnections,
        OpenRules,
        OpenTraffic,
        OpenLogs,
        OpenSettings,
        OpenHelp,
        OpenAbout,
    }

    private val binding = DesignMainBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    suspend fun setProfileName(name: String?) {
        withContext(Dispatchers.Main) {
            binding.profileName = name
        }
    }

    suspend fun setClashRunning(running: Boolean) {
        withContext(Dispatchers.Main) {
            binding.clashRunning = running
            if (!useSemanticStatusTheme) {
                binding.statusCard.setCardBackgroundColor(
                    if (running) binding.colorClashStarted else binding.colorClashStopped,
                )
            }
        }
    }

    suspend fun setForwarded(value: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = value.trafficTotal()
        }
    }

    /** Updates the status card with current upload/download rates and cumulative traffic. */
    suspend fun setTraffic(now: Long, total: Long) {
        withContext(Dispatchers.Main) {
            binding.forwarded = total.trafficTotal()
            binding.trafficUpload = now.trafficUpload()
            binding.trafficDownload = now.trafficDownload()
        }
    }

    suspend fun setMode(mode: TunnelState.Mode) {
        withContext(Dispatchers.Main) {
            binding.mode = when (mode) {
                TunnelState.Mode.Direct -> context.getString(R.string.direct_mode)
                TunnelState.Mode.Global -> context.getString(R.string.global_mode)
                TunnelState.Mode.Rule -> context.getString(R.string.rule_mode)
                else -> context.getString(R.string.rule_mode)
            }
        }
    }

    suspend fun setHasProviders(has: Boolean) {
        withContext(Dispatchers.Main) {
            binding.hasProviders = has
        }
    }

    suspend fun showAbout(versionName: String) {
        withContext(Dispatchers.Main) {
            val binding = DesignAboutBinding.inflate(context.layoutInflater).apply {
                this.versionName = versionName
            }

            AlertDialog.Builder(context)
                .setView(binding.root)
                .show()
        }
    }

    init {
        binding.self = this
        binding.forwarded = 0L.trafficTotal()
        binding.trafficUpload = 0L.trafficUpload()
        binding.trafficDownload = 0L.trafficDownload()

        binding.colorClashStarted = context.resolveThemedColor(com.google.android.material.R.attr.colorPrimary)
        binding.colorClashStopped = context.resolveThemedColor(R.attr.colorClashStopped)
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    /**
     * Keeps secondary destinations available without expanding the home-page vertical hierarchy.
     * Provider is shown only when the running profile exposes provider data.
     *
     * @param anchor view used to position the popup menu.
     */
    fun showMoreMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            if (binding.hasProviders == true) {
                menu.add(R.string.providers).setOnMenuItemClickListener {
                    request(Request.OpenProviders)
                    true
                }
            }
            menu.add(R.string.help).setOnMenuItemClickListener {
                request(Request.OpenHelp)
                true
            }
            menu.add(R.string.about).setOnMenuItemClickListener {
                request(Request.OpenAbout)
                true
            }
            show()
        }
    }
}
