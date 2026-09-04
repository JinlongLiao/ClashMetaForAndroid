package com.github.kr328.clash.design.preference

import android.view.View
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.PreferenceThemeModeBinding
import com.github.kr328.clash.design.model.DarkMode
import com.github.kr328.clash.design.util.layoutInflater
import kotlin.reflect.KMutableProperty0

/**
 * Adds the system, dark and light theme choices used by the desktop client.
 *
 * @param value persistent theme-mode property.
 * @param onThemeModeChanged callback invoked after persistence.
 */
fun PreferenceScreen.themeMode(
    value: KMutableProperty0<DarkMode>,
    onThemeModeChanged: () -> Unit,
) {
    val binding = PreferenceThemeModeBinding.inflate(context.layoutInflater, root, false)
    val checkedButton = when (value.get()) {
        DarkMode.Auto -> R.id.theme_mode_system
        DarkMode.ForceDark -> R.id.theme_mode_dark
        DarkMode.ForceLight -> R.id.theme_mode_light
    }
    binding.themeModeGroup.check(checkedButton)
    binding.themeModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
        if (!isChecked) {
            return@addOnButtonCheckedListener
        }
        value.set(
            when (checkedId) {
                R.id.theme_mode_dark -> DarkMode.ForceDark
                R.id.theme_mode_light -> DarkMode.ForceLight
                else -> DarkMode.Auto
            }
        )
        onThemeModeChanged()
    }
    addElement(object : Preference {
        /** Root view owned by this preference. */
        override val view: View = binding.root
    })
}
