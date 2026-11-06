package io.github.tancheng33.tingo

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限最小化的守卫测试。
 *
 * 规格要求：不申请全盘存储读写、后台定位、通讯录。这类权限最容易在"顺手接一个库"
 * 的时候被悄悄加回来，所以用测试把它钉住，而不是靠 code review 的记忆。
 */
class ManifestPermissionTest {

    private val manifest: String by lazy {
        File("src/main/AndroidManifest.xml").readText()
    }

    @Test
    fun noBroadStoragePermissions() {
        assertFalse(manifest.contains("WRITE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))
    }

    @Test
    fun noBackgroundLocationOrContacts() {
        assertFalse(manifest.contains("ACCESS_BACKGROUND_LOCATION"))
        assertFalse(manifest.contains("ACCESS_FINE_LOCATION"))
        assertFalse(manifest.contains("READ_CONTACTS"))
    }

    @Test
    fun recordsAudioButNothingElseYet() {
        // 录音功能已实现，所以 RECORD_AUDIO 是必要权限；
        // 前台服务与通知权限在录音保活（任务 2.3）里再加，现在不该提前声明。
        assertTrue(manifest.contains("RECORD_AUDIO"))
        // INTERNET 只用于下载模型；转写与纪要都在本机推理
        assertTrue(manifest.contains("INTERNET"))
        assertFalse(manifest.contains("POST_NOTIFICATIONS"))
        assertFalse(manifest.contains("FOREGROUND_SERVICE"))
    }

    @Test
    fun usesAppPrivateStorageOnly() {
        // 会议数据落在应用专属目录，靠 getExternalFilesDir 而不是写在公共根目录
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }
}
