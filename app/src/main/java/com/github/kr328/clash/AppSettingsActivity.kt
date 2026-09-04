package com.github.kr328.clash

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.github.kr328.clash.common.util.componentName
import com.github.kr328.clash.design.AppSettingsDesign
import com.github.kr328.clash.design.model.Behavior
import com.github.kr328.clash.design.store.UiStore.Companion.mainActivityAlias
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.ApplicationObserver
import com.github.kr328.clash.design.util.showExceptionToast
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import java.io.ByteArrayOutputStream
import java.io.File

class AppSettingsActivity : BaseActivity<AppSettingsDesign>(), Behavior {
    override suspend fun main() {
        val themeRepository = RemoteThemeRepository(this)
        val design = AppSettingsDesign(
            this,
            uiStore,
            ServiceStore(this),
            this,
            clashRunning,
            ::onHideIconChange,
            withContext(Dispatchers.IO) { themeRepository.queryCachedThemes() },
            ::previewCustomBackgroundOpacities,
        )

        setContentDesign(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ClashStart, Event.ClashStop, Event.ServiceRecreated ->
                            recreate()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        AppSettingsDesign.Request.ReCreateAllActivities -> {
                            ApplicationObserver.createdActivities.forEach { activity ->
                                activity.recreate()
                            }
                        }
                        AppSettingsDesign.Request.ApplyApplicationLanguage -> {
                            applySelectedApplicationLanguage()
                        }
                        AppSettingsDesign.Request.SelectCustomBackground -> {
                            val selectedImage = startActivityForResult(
                                ActivityResultContracts.OpenDocument(),
                                arrayOf("image/*"),
                            )
                            if (selectedImage != null) {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        copyCustomBackgroundImage(selectedImage)
                                    }
                                }.onSuccess { imagePath ->
                                    uiStore.customBackgroundImagePath = imagePath
                                    ApplicationObserver.createdActivities.forEach { activity -> activity.recreate() }
                                }.onFailure { error ->
                                    design.showExceptionToast(error.message ?: "Unable to use selected image")
                                }
                            }
                        }
                        AppSettingsDesign.Request.ClearCustomBackground -> {
                            val image = File(uiStore.customBackgroundImagePath)
                            uiStore.customBackgroundImagePath = ""
                            withContext(Dispatchers.IO) {
                                if (image.isFile) {
                                    image.delete()
                                }
                            }
                            ApplicationObserver.createdActivities.forEach { activity -> activity.recreate() }
                        }
                        AppSettingsDesign.Request.RefreshRemoteThemes -> {
                            runCatching {
                                withContext(Dispatchers.IO) { themeRepository.refreshThemes() }
                            }.onSuccess {
                                recreate()
                            }.onFailure { error ->
                                design.showExceptionToast(error.message ?: getString(com.github.kr328.clash.design.R.string.remote_themes_refresh_failed))
                            }
                        }
                        is AppSettingsDesign.Request.SelectRemoteTheme -> {
                            val backgroundImagePath = runCatching {
                                withContext(Dispatchers.IO) { themeRepository.cacheThemeBackground(it.theme) }
                            }.getOrNull().orEmpty()
                            uiStore.remoteThemeKey = it.theme.key
                            uiStore.remoteThemePrimaryColor = it.theme.primaryColor ?: 0
                            uiStore.remoteThemeSecondaryColor = it.theme.secondaryColor ?: 0
                            uiStore.remoteThemeBackgroundColor = it.theme.backgroundColor ?: 0
                            uiStore.remoteThemeSurfaceColor = it.theme.surfaceColor ?: 0
                            uiStore.remoteThemeOnSurfaceColor = it.theme.onSurfaceColor ?: 0
                            uiStore.remoteThemeOutlineColor = it.theme.outlineColor ?: 0
                            uiStore.remoteThemeBackgroundImagePath = backgroundImagePath
                            uiStore.remoteThemeAccentGradient = it.theme.accentGradientColors.joinToString(",")
                            uiStore.remoteThemeSurfaceOpacityPermille =
                                ((it.theme.surfaceOpacity ?: 1f) * 1000).toInt()
                            ApplicationObserver.createdActivities.forEach { activity -> activity.recreate() }
                        }
                    }
                }
            }
        }
    }

    /**
     * Copies one system-picker result into private storage after bounded image validation.
     *
     * The source URI is read only during this call, so no broad storage permission or durable
     * provider grant is needed. Existing custom content is replaced only after validation passes.
     *
     * @param sourceUri image URI returned by Android's document provider.
     * @return absolute path of the stable application-private copy.
     * @throws IllegalArgumentException when the payload is too large or is not a supported image.
     */
    private fun copyCustomBackgroundImage(sourceUri: Uri): String {
        val bytes = contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Unable to open selected image" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                total += count
                require(total <= MAX_CUSTOM_BACKGROUND_BYTES) { "Selected image is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Selected file is not a supported image" }
        require(bounds.outWidth <= MAX_CUSTOM_BACKGROUND_DIMENSION &&
            bounds.outHeight <= MAX_CUSTOM_BACKGROUND_DIMENSION) {
            "Selected image dimensions are too large"
        }
        val targetDirectory = File(filesDir, "custom-background").apply { mkdirs() }
        return File(targetDirectory, "background.image").apply { writeBytes(bytes) }.absolutePath
    }

    /** Applies the persisted locale through AppCompat so Android 12 and older are supported. */
    private fun applySelectedApplicationLanguage() {
        val locales = if (uiStore.applicationLanguageTag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(uiStore.applicationLanguageTag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    override var autoRestart: Boolean
        get() {
            val status = packageManager.getComponentEnabledSetting(
                RestartReceiver::class.componentName
            )

            return status == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        set(value) {
            val status = if (value)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                RestartReceiver::class.componentName,
                status,
                PackageManager.DONT_KILL_APP,
            )
        }

    private fun onHideIconChange(hide: Boolean) {
        val newState = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        packageManager.setComponentEnabledSetting(
            mainActivityAlias,
            newState,
            PackageManager.DONT_KILL_APP
        )
        }

    companion object {
        /** Maximum encoded image size accepted from the system picker. */
        private const val MAX_CUSTOM_BACKGROUND_BYTES = 12L * 1024L * 1024L

        /** Maximum source width or height accepted before decoding on page creation. */
        private const val MAX_CUSTOM_BACKGROUND_DIMENSION = 12_000
    }
}
