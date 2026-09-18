package com.lingion.sleepy.ui.screen.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 排查全量包 dump 契约 — zip 组装 + 落 MediaStore Downloads。
 *
 * 触发: 教务导入报错弹窗点"导出排查全量包"
 * 隐私: 1B 完全不脱敏 —— Cookie/学号/表单值全保留,排查价值最大
 * 动线: 2A 存完自动弹分享面板
 * 路径: Downloads/Sleepy/教务日志/ (API 29+), < cache/ 给 API 26-28
 */
class JwCaptureDumpContractTest {

    private val source: String = sequenceOf(
        File("app/src/main/java/com/lingion/sleepy/ui/screen/imports/JwCaptureDump.kt"),
        File("src/main/java/com/lingion/sleepy/ui/screen/imports/JwCaptureDump.kt"),
    ).firstOrNull { it.isFile }?.readText() ?: error("Unable to load JwCaptureDump.kt source")

    private val loginScreen: String = sequenceOf(
        File("app/src/main/java/com/lingion/sleepy/ui/screen/imports/JwWebViewLoginScreen.kt"),
        File("src/main/java/com/lingion/sleepy/ui/screen/imports/JwWebViewLoginScreen.kt"),
    ).firstOrNull { it.isFile }?.readText() ?: error("Unable to load JwWebViewLoginScreen.kt source")

    @Test
    fun dump_creates_zip_with_five_files() {
        // zip 必须含: summary.txt, frames/, dom-inventory.txt, netlog.txt, console.txt
        assertTrue("必须有生成 zip 的方法", Regex("""fun\s+(createDump|exportDump|generateDump|buildDump)\s*\(""").containsMatchIn(source))
        assertTrue("zip 必须含 summary.txt", source.contains("summary.txt"))
        assertTrue("zip 必须含 dom-inventory.txt", source.contains("dom-inventory.txt"))
        assertTrue("zip 必须含 netlog.txt", source.contains("netlog.txt"))
        assertTrue("zip 必须含 console.txt", source.contains("console.txt"))
        assertTrue("zip 必须含 frames/ 目录", source.contains("frames") || source.contains("FrameSnapshot"))
    }

    @Test
    fun dump_uses_media_store_downloads_path() {
        // API 29+: MediaStore.Downloads RELATIVE_PATH = "Download/Sleepy/教务日志"
        //           API 26-28: getExternalFilesDir(Download) 应用专属回退 (方案 A)
        assertTrue("必须用 MediaStore Downloads API (29+)", source.contains("MediaStore.Downloads"))
        assertTrue("必须用 RELATIVE_PATH 存到 Downloads/Sleepy/", source.contains("RELATIVE_PATH"))
        assertTrue("API 26-28 必须有 getExternalFilesDir 回退 (方案 A)", source.contains("getExternalFilesDir"))
        assertTrue("必须判 SDK 版本分流通路", source.contains("VERSION_CODES.Q") || source.contains("Build.VERSION.SDK_INT"))
    }

    @Test
    fun dump_includes_frame_html_files() {
        // frames/ 目录必须含选中 frame 的 outerHTML 原文
        assertTrue("必须写 frames/ 前缀路径", source.contains("frames/"))
        assertTrue("必须落 outerHTML 原文 (r.html)", source.contains("r.html"))
    }

    @Test
    fun dump_includes_dom_inventory() {
        // 可点元素清单: a/button/input/select/[onclick] — DOM_INVENTORY_JS 采集,
        // 点导出时在 WebView 上 evaluateJavascript 现抓 (页面还在, 失败弹窗不关页)
        assertTrue(
            "LoginScreen 必须有 DOM_INVENTORY_JS 常量",
            loginScreen.contains("DOM_INVENTORY_JS")
        )
        val jsStart = loginScreen.indexOf("const val DOM_INVENTORY_JS")
        assertTrue("DOM_INVENTORY_JS 常量缺失", jsStart >= 0)
        val js = loginScreen.substring(jsStart, jsStart + 4000)
        for (tag in listOf("a", "button", "input", "select")) {
            assertTrue("inventory 必须遍历 <$tag>", js.contains("'$tag'") || js.contains("\"$tag\""))
        }
    }

    @Test
    fun dump_triggers_share_intent_after_save() {
        // 存完后自动弹分享面板
        assertTrue("必须触发分享 Intent", source.contains("ACTION_SEND") || source.contains("share"))
    }

    @Test
    fun dump_no_redaction_1b() {
        // 1B 完全不脱敏: Cookie/学号/表单值全保留
        assertFalse("禁止脱敏函数(1B 不脱敏)", Regex("""fun\s+sanitize|REDACTED""").containsMatchIn(source))
    }

    @Test
    fun dump_summary_includes_capture_result_fields() {
        // summary.txt 必须含 FrameCaptureResult 关键字段: 状态/锚点/帧路径/重试/hint
        assertTrue("summary 必须含 status", source.contains("status"))
        assertTrue("summary 必须含 matchedAnchors", source.contains("matchedAnchors"))
        assertTrue("summary 必须含 selectedFramePath", source.contains("selectedFramePath"))
        assertTrue("summary 必须含 diagnosticHint", source.contains("diagnosticHint"))
        assertTrue("summary 必须含 retryCount", source.contains("retryCount"))
    }
}
