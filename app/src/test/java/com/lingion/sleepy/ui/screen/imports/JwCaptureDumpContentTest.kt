package com.lingion.sleepy.ui.screen.imports

import com.lingion.sleepy.data.jw.JwSchoolInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 验证 buildZip 产出确实含五文件 + summary 字段 (不只是契约层)。
 *
 * 是源码扫描测试的补充 — 契约测试锁"必有这些代码",内容测试锁"代码运行起来确实产生这些文件"。
 */
class JwCaptureDumpContentTest {

    private fun stubSchool() = JwSchoolInfo(
        sortKey = "test/xju_post",
        name = "新疆大学",
        url = "https://yjspy.xju.edu.cn",
        type = "xju_post",
    )

    private fun stubResult() = FrameCaptureResult(
        selectedFramePath = listOf("(top)", "PageFrame"),
        html = "<html><body>孙冬璞 高级算法</body></html>",
        matchedAnchors = listOf("_dgdata"),
        courseCount = 12,
        status = FrameCaptureStatus.WRONG_PAGE,
        blockedFrames = emptyList(),
        retryCount = 3,
        maxDepthReached = 2,
        skippedFrames = emptyList(),
        diagnosticHint = "当前页面未检测到课表容器",
    )

    @Test
    fun buildZip_contains_all_five_files() {
        val bytes = JwCaptureDump.buildZip(
            ctx = androidContext(),
            school = stubSchool(),
            result = stubResult(),
            domInventoryJson = "{\"url\":\"https://yjspy.xju.edu.cn\",\"total\":3,\"items\":[]}",
        )
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names += e.name
                zis.closeEntry()
            }
        }
        assertTrue("must contain summary.txt", names.contains("summary.txt"))
        assertTrue("must contain netlog.txt", names.contains("netlog.txt"))
        assertTrue("must contain console.txt", names.contains("console.txt"))
        assertTrue("must contain dom-inventory.txt", names.contains("dom-inventory.txt"))
        assertTrue("must contain a frames/ html entry", names.any { it.startsWith("frames/") && it.endsWith(".html") })
    }

    @Test
    fun buildZip_summary_includes_capture_fields() {
        val bytes = JwCaptureDump.buildZip(
            ctx = androidContext(),
            school = stubSchool(),
            result = stubResult(),
            domInventoryJson = null,
        )
        val summaryText = readEntry(bytes, "summary.txt")
        for (key in listOf("status=", "matchedAnchors=", "selectedFramePath=",
            "retryCount=", "diagnosticHint=", "school=", "appVersion=", "WRONG_PAGE")) {
            assertTrue("summary.txt missing $key", summaryText.contains(key))
        }
        assertTrue("matchedAnchors must contain _dgdata", summaryText.contains("_dgdata"))
        assertTrue("selectedFramePath must contain PageFrame", summaryText.contains("PageFrame"))
    }

    @Test
    fun buildZip_dom_inventory_writes_null_fallback() {
        val bytes = JwCaptureDump.buildZip(
            ctx = androidContext(),
            school = stubSchool(),
            result = stubResult(),
            domInventoryJson = null,
        )
        val inventory = readEntry(bytes, "dom-inventory.txt")
        assertTrue("dom-inventory fallback must be valid JSON", inventory.contains("\"total\":0"))
    }

    @Test
    fun buildZip_includes_selected_frame_html() {
        val bytes = JwCaptureDump.buildZip(
            ctx = androidContext(),
            school = stubSchool(),
            result = stubResult(),
            domInventoryJson = null,
        )
        val html = readEntryStartsWith(bytes, "frames/")
        assertNotNull("must have a frames/ html entry", html)
        assertTrue("frame html must contain original outerHTML", html!!.contains("孙冬璞"))
        assertTrue("frame html must contain container content", html.contains("高级算法"))
    }

    private fun readEntry(bytes: ByteArray, name: String): String =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == name) return zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
            error("entry not found: $name")
        }

    @Test
    fun buildZip_contains_desktop_level_diagnostic_sections_and_manifest() {
        val bytes = JwCaptureDump.buildZip(
            ctx = androidContext(),
            school = stubSchool(),
            result = stubResult(),
            domInventoryJson = "{}",
            cookiesFull = "sid=full-cookie-value; uid=202501",
            storageJson = "{\"localStorage\":{\"token\":\"full-token\"}}",
            linksJson = "{\"links\":[\"https://xju.edu.cn/schedule\"],\"selects\":[{\"opts\":[{\"v\":\"2025-2026\"}]}]}",
        )
        val names = zipNames(bytes)
        assertTrue("desktop parity: INDEX.txt", names.contains("INDEX.txt"))
        assertTrue("desktop parity: full cookies", names.contains("cookies-full.txt"))
        assertTrue("desktop parity: storage", names.contains("5-storage/storage.json"))
        assertTrue("desktop parity: links/selects", names.contains("2-inline/links.json"))
        assertTrue("device environment", names.contains("env/device.txt"))
        assertTrue("manifest must list all files", readEntry(bytes, "INDEX.txt").contains("cookies-full.txt"))
        assertTrue("cookie value must remain unredacted", readEntry(bytes, "cookies-full.txt").contains("full-cookie-value"))
        assertTrue("storage value must remain unredacted", readEntry(bytes, "5-storage/storage.json").contains("full-token"))
    }

    @Test
    fun buildZip_writes_all_frame_snapshots_when_available() {
        val result = stubResult().copy(allFrames = listOf(
            "(top)" to "<html>top</html>",
            "(top)_PageFrame" to "<html>孙冬璞 frame</html>",
        ))
        val names = zipNames(JwCaptureDump.buildZip(null, stubSchool(), result, null))
        assertTrue("all frame html must be exported", names.count { it.startsWith("frames/") } >= 2)
    }

    private fun zipNames(bytes: ByteArray): Set<String> = buildSet {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                add(e.name)
                zis.closeEntry()
            }
        }
    }

    private fun readEntryStartsWith(bytes: ByteArray, prefix: String): String? {
        val zis = ZipInputStream(ByteArrayInputStream(bytes))
        try {
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name.startsWith(prefix) && e.name.endsWith(".html")) {
                    return zis.readBytes().toString(Charsets.UTF_8)
                }
                zis.closeEntry()
            }
            return null
        } finally {
            zis.close()
        }
    }

    /** buildZip 不消费 ctx (纯逻辑), 直接传 null。 */
    private fun androidContext(): android.content.Context? = null
}