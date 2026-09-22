package com.github.kr328.clash.util

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.github.kr328.clash.common.log.Log

/**
 * 依次尝试文档、通用文件和图库选择器，兼容未安装 DocumentsUI 的电视。
 * 用户取消时立即返回，不再弹出其他选择器；仅无法启动或权限拒绝时尝试下一项。
 * 不申请广泛存储权限，返回 URI 由调用方即时读取并复制到私有目录。
 *
 * @param launchImagePicker 启动给定 Intent 并等待选择结果，取消时返回 null。
 * @return 用户选择的 URI，或者用户取消时的 null。
 * @throws ActivityNotFoundException 所有选择器都无法启动时抛出，由页面展示安装指引。
 */
internal suspend fun selectBackgroundImage(
    launchImagePicker: suspend (Intent) -> Uri?,
): Uri? {
    val pickerIntents = listOf(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
        Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
        Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"),
    )
    for (pickerIntent in pickerIntents) {
        pickerIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            return launchImagePicker(pickerIntent)
        } catch (e: ActivityNotFoundException) {
            Log.w("Background image picker unavailable: action=${pickerIntent.action}", e)
        } catch (e: SecurityException) {
            Log.w("Background image picker denied: action=${pickerIntent.action}", e)
        }
    }
    throw ActivityNotFoundException("No accessible image picker")
}
