package com.github.kr328.clash.util

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 验证电视选择器缺失、用户取消和协程取消时的行为，避免反复弹窗或吞掉错误。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], manifest = Config.NONE, application = Application::class)
class BackgroundImagePickerTest {
    /** 正常手机仍优先使用文档选择器，保留 MIME 及临时读取权限。 */
    @Test
    fun documentPickerReturnsImageWithoutFallback() = runBlocking {
        val selectedImage = Uri.parse("content://images/1")
        var launches = 0
        val result = selectBackgroundImage { intent ->
            launches++
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertEquals("image/*", intent.type)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            selectedImage
        }
        assertEquals(selectedImage, result)
        assertEquals(1, launches)
    }

    /** DocumentsUI 缺失时使用通用文件选择器，成功后不再打开图库。 */
    @Test
    fun missingDocumentsUiFallsBackToContentPicker() = runBlocking {
        val actions = mutableListOf<String?>()
        val selectedImage = Uri.parse("content://images/2")
        val result = selectBackgroundImage { intent ->
            actions.add(intent.action)
            if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                throw ActivityNotFoundException()
            }
            selectedImage
        }
        assertEquals(selectedImage, result)
        assertEquals(listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT), actions)
    }

    /** 文档与文件入口被电视拒绝时仍可使用图库。 */
    @Test
    fun deniedFilePickersFallBackToGallery() = runBlocking {
        val selectedImage = Uri.parse("content://images/3")
        var launches = 0
        val result = selectBackgroundImage { intent ->
            launches++
            if (intent.action != Intent.ACTION_PICK) {
                throw SecurityException("Picker unavailable to this application")
            }
            selectedImage
        }
        assertEquals(selectedImage, result)
        assertEquals(3, launches)
    }

    /** 用户取消是正常终态，不把取消当作缺失选择器。 */
    @Test
    fun userCancellationDoesNotLaunchAnotherPicker() = runBlocking {
        var launches = 0
        assertNull(selectBackgroundImage { launches++; null })
        assertEquals(1, launches)
    }

    /** 所有入口都缺失时交由页面给出安装文件管理器提示。 */
    @Test
    fun missingAllPickersReportsRecoverableFailure() = runBlocking {
        var launches = 0
        try {
            selectBackgroundImage { launches++; throw ActivityNotFoundException() }
            fail("Expected unavailable picker result")
        } catch (expected: ActivityNotFoundException) {
            assertEquals(3, launches)
        }
    }

    /** 页面销毁不能继续弹出后备窗口，也不能被包装为图片读取失败。 */
    @Test
    fun coroutineCancellationPropagatesImmediately() = runBlocking {
        var launches = 0
        try {
            selectBackgroundImage { launches++; throw CancellationException("Page destroyed") }
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals(1, launches)
        }
    }
}
