package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.model.ThemePalette
import com.github.kr328.clash.design.model.RemoteTheme
import com.github.kr328.clash.design.preference.*
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import com.github.kr328.clash.service.store.ServiceStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

class AppSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    behavior: Behavior,
    running: Boolean,
    onHideIconChange: (hide: Boolean) -> Unit,
    private val remoteThemes: List<RemoteTheme>,
    private val onCustomBackgroundOpacityPreview: (imagePermille: Int, surfacePermille: Int) -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    sealed class Request {
        data object ReCreateAllActivities : Request()
        data object ApplyApplicationLanguage : Request()
        data object RefreshRemoteThemes : Request()
        data object SelectCustomBackground : Request()
        data object ClearCustomBackground : Request()
        data class SelectRemoteTheme(val theme: RemoteTheme) : Request()
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.behavior)

            switch(
                value = behavior::autoRestart,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.auto_restart,
                summary = R.string.allow_clash_auto_restart,
            )

            category(R.string.interface_)

            themeMode(uiStore::darkMode) {
                requests.trySend(Request.ReCreateAllActivities)
            }

            clickable(
                title = R.string.theme_palette,
                icon = R.drawable.ic_baseline_brightness_4,
                summary = R.string.theme_palette_summary,
            ) {
                clicked {
                    showThemePaletteDialog(uiStore)
                }
            }

            clickable(
                title = R.string.remote_themes,
                icon = R.drawable.ic_baseline_brightness_4,
                summary = if (remoteThemes.isEmpty()) {
                    R.string.remote_themes_empty_summary
                } else {
                    R.string.remote_themes_summary
                },
            ) {
                clicked {
                    showRemoteThemeDialog(uiStore)
                }
            }

            clickable(
                title = R.string.custom_background,
                icon = R.drawable.ic_outline_folder,
                summary = if (uiStore.customBackgroundImagePath.isEmpty()) {
                    R.string.custom_background_summary
                } else {
                    R.string.custom_background_enabled_summary
                },
            ) {
                clicked {
                    showCustomBackgroundDialog(uiStore)
                }
            }

            clickable(
                title = R.string.custom_background_transparency,
                icon = R.drawable.ic_baseline_brightness_4,
            ) {
                enabled = uiStore.customBackgroundImagePath.isNotEmpty()
                summary = context.getString(
                    R.string.custom_background_transparency_value,
                    uiStore.customBackgroundImageOpacityPermille / 10,
                    uiStore.customBackgroundSurfaceOpacityPermille / 10,
                )
                clicked {
                    showCustomBackgroundOpacityDialog(uiStore)
                }
            }

            clickable(
                title = R.string.application_language,
                icon = R.drawable.ic_baseline_settings,
                summary = R.string.application_language_summary,
            ) {
                clicked {
                    showApplicationLanguageDialog(uiStore)
                }
            }

            category(R.string.network)

            editableText(
                value = uiStore::networkDetectionTargets,
                adapter = object : NullableTextAdapter<String> {
                    override fun from(value: String): String = value

                    override fun to(text: String?): String = text.orEmpty()
                },
                title = R.string.custom_latency_targets,
                icon = R.drawable.ic_baseline_dns,
                placeholder = R.string.custom_latency_targets_summary,
            )

            switch(
                value = uiStore::hideAppIcon,
                icon = R.drawable.ic_baseline_hide,
                title = R.string.hide_app_icon_title,
                summary = R.string.hide_app_icon_desc,
            ) {
                listener = OnChangedListener {
                    onHideIconChange(uiStore::hideAppIcon.get())
                }
            }

            switch(
                value = uiStore::hideFromRecents,
                icon = R.drawable.ic_baseline_stack,
                title = R.string.hide_from_recents_title,
                summary = R.string.hide_from_recents_desc,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            category(R.string.service)

            switch(
                value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary
            ) {
                enabled = !running
            }
        }

        binding.content.addView(screen.root)
    }

    /**
     * Displays every locale declared by the application and persists the selected BCP 47 tag.
     * The empty tag deliberately represents the system default and is passed unchanged to AppCompat.
     *
     * @param uiStore persistent UI preferences shared with the activity.
     */
    private fun showApplicationLanguageDialog(uiStore: UiStore) {
        val languageTags = arrayOf("", "en", "zh", "zh-HK", "zh-TW", "ja-JP", "ko-KR", "ru", "vi")
        val languageLabels = arrayOf(
            context.getString(R.string.application_language_system),
            "English",
            "简体中文",
            "繁體中文（香港）",
            "繁體中文（台灣）",
            "日本語",
            "한국어",
            "Русский",
            "Tiếng Việt",
        )
        val selectedIndex = languageTags.indexOf(uiStore.applicationLanguageTag).coerceAtLeast(0)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.application_language)
            .setSingleChoiceItems(languageLabels, selectedIndex) { dialog, selected ->
                uiStore.applicationLanguageTag = languageTags[selected]
                dialog.dismiss()
                requests.trySend(Request.ApplyApplicationLanguage)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Lets the user replace or clear the application-private custom background. */
    private fun showCustomBackgroundDialog(uiStore: UiStore) {
        val actions = if (uiStore.customBackgroundImagePath.isEmpty()) {
            arrayOf(context.getString(R.string.custom_background_select))
        } else {
            arrayOf(
                context.getString(R.string.custom_background_replace),
                context.getString(R.string.custom_background_clear),
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.custom_background)
            .setItems(actions) { dialog, selected ->
                dialog.dismiss()
                if (uiStore.customBackgroundImagePath.isEmpty() || selected == 0) {
                    requests.trySend(Request.SelectCustomBackground)
                } else {
                    requests.trySend(Request.ClearCustomBackground)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Applies both sliders directly to the real settings page while the dialog remains open.
     * Dismissing without confirmation restores the original values; confirmation persists both values together.
     */
    private fun showCustomBackgroundOpacityDialog(uiStore: UiStore) {
        val originalImageOpacityPermille = uiStore.customBackgroundImageOpacityPermille
        val originalSurfaceOpacityPermille = uiStore.customBackgroundSurfaceOpacityPermille
        var confirmed = false
        val imageOpacityLabel = TextView(context).apply {
            textSize = 18f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        val imageOpacitySlider = Slider(context).apply {
            valueFrom = MIN_CUSTOM_BACKGROUND_IMAGE_OPACITY_PERCENT.toFloat()
            valueTo = MAX_CUSTOM_BACKGROUND_OPACITY_PERCENT.toFloat()
            stepSize = 1f
            value = (uiStore.customBackgroundImageOpacityPermille / 10f)
                .coerceIn(valueFrom, valueTo)
        }
        val cardOpacityLabel = TextView(context).apply {
            textSize = 18f
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        val cardOpacitySlider = Slider(context).apply {
            valueFrom = MIN_CUSTOM_BACKGROUND_OPACITY_PERCENT.toFloat()
            valueTo = MAX_CUSTOM_BACKGROUND_OPACITY_PERCENT.toFloat()
            stepSize = 1f
            value = (uiStore.customBackgroundSurfaceOpacityPermille / 10f)
                .coerceIn(valueFrom, valueTo)
        }
        fun updateTransparencyPreview() {
            val imageOpacityPercent = imageOpacitySlider.value.toInt()
            val cardOpacityPercent = cardOpacitySlider.value.toInt()
            imageOpacityLabel.text = context.getString(
                R.string.custom_background_image_opacity_percent,
                imageOpacityPercent,
            )
            cardOpacityLabel.text = context.getString(
                R.string.custom_background_opacity_percent,
                cardOpacityPercent,
            )
            onCustomBackgroundOpacityPreview(
                imageOpacityPercent * 10,
                cardOpacityPercent * 10,
            )
        }
        imageOpacitySlider.addOnChangeListener { _, _, _ -> updateTransparencyPreview() }
        cardOpacitySlider.addOnChangeListener { _, _, _ -> updateTransparencyPreview() }
        updateTransparencyPreview()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * resources.displayMetrics.density).toInt()
            val vertical = (12 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
            addView(imageOpacityLabel)
            addView(imageOpacitySlider)
            addView(cardOpacityLabel)
            addView(cardOpacitySlider)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.custom_background_transparency)
            .setMessage(R.string.custom_background_transparency_live_summary)
            .setView(content)
            .setPositiveButton(R.string.ok) { _, _ ->
                confirmed = true
                uiStore.customBackgroundImageOpacityPermille = imageOpacitySlider.value.toInt() * 10
                uiStore.customBackgroundSurfaceOpacityPermille = cardOpacitySlider.value.toInt() * 10
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.window?.setDimAmount(0f)
        dialog.setOnDismissListener {
            if (!confirmed) {
                onCustomBackgroundOpacityPreview(
                    originalImageOpacityPermille,
                    originalSurfaceOpacityPermille,
                )
            }
        }
    }

    companion object {
        /** Lowest supported card opacity, allowing the selected image to remain clearly visible. */
        private const val MIN_CUSTOM_BACKGROUND_OPACITY_PERCENT = 10

        /** Lowest image opacity, retaining a faint visual reference to the selected background. */
        private const val MIN_CUSTOM_BACKGROUND_IMAGE_OPACITY_PERCENT = 0

        /** Fully opaque upper bound of the background-card slider. */
        private const val MAX_CUSTOM_BACKGROUND_OPACITY_PERCENT = 100
    }

    /**
     * Displays native palette choices and recreates activities immediately after selection.
     *
     * @param uiStore persistent UI preferences shared with the activity.
     */
    private fun showThemePaletteDialog(uiStore: UiStore) {
        val palettes = ThemePalette.values()
        val labels = arrayOf(
            context.getString(R.string.theme_palette_blue),
            context.getString(R.string.theme_palette_indigo),
            context.getString(R.string.theme_palette_purple),
            context.getString(R.string.theme_palette_teal),
            context.getString(R.string.theme_palette_orange_red),
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.theme_palette)
            .setSingleChoiceItems(labels, uiStore.themePalette.ordinal) { dialog, selected ->
                uiStore.themePalette = palettes[selected]
                uiStore.remoteThemeKey = ""
                dialog.dismiss()
                requests.trySend(Request.ReCreateAllActivities)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Displays cached theme-hub entries and keeps refresh as an explicit network action. */
    private fun showRemoteThemeDialog(uiStore: UiStore) {
        val labels = remoteThemes.map { theme -> theme.label }.toTypedArray()
        val selectedIndex = remoteThemes.indexOfFirst { theme -> theme.key == uiStore.remoteThemeKey }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.remote_themes)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.refresh) { _, _ -> requests.trySend(Request.RefreshRemoteThemes) }
        if (labels.isEmpty()) {
            builder.setMessage(R.string.remote_themes_empty_summary)
        } else {
            builder.setSingleChoiceItems(labels, selectedIndex) { dialog, selected ->
                requests.trySend(Request.SelectRemoteTheme(remoteThemes[selected]))
                dialog.dismiss()
            }
        }
        builder.show()
    }
}
