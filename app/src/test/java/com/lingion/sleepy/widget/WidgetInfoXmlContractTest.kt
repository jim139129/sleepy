package com.lingion.sleepy.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Widget 信息 XML / Manifest 声明契约 — issue #24 Feature 1(R1/R4/R5)。
 *
 * 纯 JVM 读源头文件断言(先例: [com.lingion.sleepy.ManifestExternalOpenFilterTest],
 * 仓库无 Robolectric, 声明式数据读源头文件等价于读打包产物)。
 *
 * 锁三层接线闭环:
 * 1. Manifest 里注册的每个 appwidget receiver 都带 meta-data 指向一个 *_widget_info.xml;
 * 2. 每个 info XML 都不声明 android:configure，添加到桌面时不弹白页;
 * 3. 每个 info XML 的 android:widgetFeatures 都含 reconfigurable，保留桌面长按编辑能力。
 */
class WidgetInfoXmlContractTest {

    private val resXmlDir: File by lazy {
        sequenceOf(
            File("app/src/main/res/xml"),
            File("src/main/res/xml")
        ).first { it.isDirectory }
    }

    private val manifest: Element by lazy {
        val f = sequenceOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml")
        ).first { it.isFile }
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement
    }

    /** 解析 manifest 的 android:name(可能是 ".widget.X" 或 "com.lingion.sleepy.widget.X")为 FQCN。 */
    private fun toFqcn(androidName: String): String {
        val pkg = "com.lingion.sleepy"
        return if (androidName.startsWith(".")) "$pkg$androidName" else androidName
    }

    /** Manifest 声明的 (receiver 全限定类名 → widget info xml 资源键, 无 @xml/ 前缀也无 .xml 后缀) */
    private val manifestReceivers: Map<String, String> by lazy {
        val out = mutableMapOf<String, String>()
        val receivers = manifest.getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as Element
            val nameAttr = receiver.getAttribute("android:name")
            if (nameAttr.isNullOrEmpty()) continue
            val metas = receiver.getElementsByTagName("meta-data")
            for (j in 0 until metas.length) {
                val meta = metas.item(j) as Element
                if (meta.getAttribute("android:name") != "android.appwidget.provider") continue
                val resource = meta.getAttribute("android:resource")
                val key = resource.removePrefix("@xml/")
                if (key.endsWith("_widget_info") || key.endsWith("_widget_info.xml")) {
                    out[toFqcn(nameAttr)] = key.removeSuffix(".xml")
                }
            }
        }
        out
    }

    /** info XML 资源键 (去 .xml 后缀) → 根元素(appwidget-provider) */
    private val infoXmls: Map<String, Element> by lazy {
        resXmlDir.listFiles { f -> f.name.endsWith("_widget_info.xml") }
            ?.associate { f -> f.name.removeSuffix(".xml") to
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).documentElement }
            ?: emptyMap()
    }

    /** 全部 receiver 短类名 ↔ ALL_WIDGET_VARIANTS 元数据一一对应(R1 列表的数据源) */
    @Test
    fun `manifest registers exactly the variants in ALL_WIDGET_VARIANTS`() {
        val expected = ALL_WIDGET_VARIANTS
            .map { it.receiverClass.name }
            .toSet()
        assertEquals(expected, manifestReceivers.keys)
    }

    @Test
    fun `every manifest widget info resource has a matching xml file`() {
        assertTrue("no widget receivers with meta-data found in manifest", manifestReceivers.isNotEmpty())
        manifestReceivers.values.forEach { key ->
            assertTrue("missing res/xml/$key.xml", key in infoXmls)
        }
    }

    /** Provider descriptions must use the long localized text, not the short picker label. */
    @Test
    fun `every provider uses its localized widget description resource`() {
        val expected = mapOf(
            "today_widget_info" to "widget_today_description",
            "today_small_widget_info" to "widget_today_small_description",
            "today_wide_widget_info" to "widget_today_wide_description",
            "twoday_widget_info" to "widget_twoday_description",
            "twoday_small_widget_info" to "widget_twoday_small_description",
            "twoday_wide_widget_info" to "widget_twoday_wide_description",
            "week_list_widget_info" to "widget_week_list_description",
            "week_list_small_widget_info" to "widget_week_list_small_description",
            "weeklist_wide_widget_info" to "widget_week_list_wide_description",
            "week_view_widget_info" to "widget_week_view_description",
            "week_view_small_widget_info" to "widget_week_view_small_description",
            "week_grid_widget_info" to "widget_week_grid_description",
            "week_grid_small_widget_info" to "widget_week_grid_small_description"
        )
        expected.forEach { (xmlName, stringName) ->
            val description = infoXmls.getValue(xmlName).getAttribute("android:description")
            assertEquals("@string/$stringName", description)
        }
    }

    /** Widget picker / OEM detail text must be localized with every shipped locale. */
    @Test
    fun `all shipped locales define every widget description string`() {
        val required = listOf(
            "widget_today_description",
            "widget_today_small_description",
            "widget_today_wide_description",
            "widget_twoday_description",
            "widget_twoday_small_description",
            "widget_twoday_wide_description",
            "widget_week_list_description",
            "widget_week_list_small_description",
            "widget_week_list_wide_description",
            "widget_week_view_description",
            "widget_week_view_small_description",
            "widget_week_grid_description",
            "widget_week_grid_small_description"
        )
        val resRoot = resXmlDir.parentFile
            ?: error("res/xml must have a parent resource directory")
        val localeDirs = resRoot.listFiles { file ->
            file.isDirectory && (file.name == "values" || file.name.startsWith("values-"))
        } ?: emptyArray()
        localeDirs.forEach { dir ->
            val strings = File(dir, "strings.xml")
            if (!strings.isFile) return@forEach
            val source = strings.readText()
            required.forEach { key ->
                assertTrue("${dir.name}/strings.xml must define $key", Regex("name=\\\"$key\\\"").containsMatchIn(source))
            }
        }
    }

    /** vivo 原子组件要求每个 receiver 都声明三件套，供智慧桌面识别和展示。 */
    @Test
    fun `every widget receiver declares vivo atomic component metadata`() {
        val receivers = manifest.getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val receiver = receivers.item(i) as Element
            val providerMeta = run {
                val metas = receiver.getElementsByTagName("meta-data")
                var provider: Element? = null
                for (j in 0 until metas.length) {
                    val meta = metas.item(j) as Element
                    if (meta.getAttribute("android:name") == "android.appwidget.provider") {
                        provider = meta
                        break
                    }
                }
                provider
            } ?: continue
            val receiverName = receiver.getAttribute("android:name")
            fun metadata(name: String): Element? {
                val metas = receiver.getElementsByTagName("meta-data")
                for (j in 0 until metas.length) {
                    val meta = metas.item(j) as Element
                    if (meta.getAttribute("android:name") == name) return meta
                }
                return null
            }
            assertTrue("$receiverName must declare vivo_widget=true", metadata("vivo_widget")?.getAttribute("android:value") == "true")
            val version = metadata("vivoWidgetVersion")?.getAttribute("android:value")?.toIntOrNull()
            assertTrue("$receiverName must declare positive vivoWidgetVersion", version != null && version > 0)
            val description = metadata("vivo.widget.description")
            assertTrue(
                "$receiverName must declare vivo.widget.description resource",
                description?.getAttribute("android:resource")?.startsWith("@string/") == true
            )
            assertTrue("$receiverName provider metadata must remain present", providerMeta.getAttribute("android:resource").isNotEmpty())
        }
    }

    /** 小米负一屏/桌面识别需要 application 级版本号，且版本只能为正整数。 */
    @Test
    fun `application declares a positive Xiaomi widget version`() {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val metas = application.getElementsByTagName("meta-data")
        var version: String? = null
        for (i in 0 until metas.length) {
            val meta = metas.item(i) as Element
            if (meta.getAttribute("android:name") == "miuiWidgetVersion") {
                version = meta.getAttribute("android:value")
                break
            }
        }
        assertTrue("application must declare a positive miuiWidgetVersion", version?.toIntOrNull()?.let { it > 0 } == true)
    }

    /** 每个 widget 都必须能被小米桌面以曝光刷新广播唤醒。 */
    @Test
    fun `every widget receiver declares Xiaomi widget metadata and refresh action`() {
        manifestReceivers.keys.forEach { fqcn ->
            val shortName = fqcn.substringAfterLast('.')
            val receiver = (0 until manifest.getElementsByTagName("receiver").length)
                .map { manifest.getElementsByTagName("receiver").item(it) as Element }
                .first { it.getAttribute("android:name").substringAfterLast('.') == shortName }
            val metas = receiver.getElementsByTagName("meta-data")
            fun metadata(name: String): Element? {
                for (i in 0 until metas.length) {
                    val meta = metas.item(i) as Element
                    if (meta.getAttribute("android:name") == name) return meta
                }
                return null
            }
            assertEquals("true", metadata("miuiWidget")?.getAttribute("android:value"))
            assertEquals("exposure", metadata("miuiWidgetRefresh")?.getAttribute("android:value"))
            val interval = metadata("miuiWidgetRefreshMinInterval")?.getAttribute("android:value")?.toLongOrNull()
            assertTrue("$shortName Xiaomi exposure interval must be at least 10 seconds", interval != null && interval >= 10_000)

            val actions = receiver.getElementsByTagName("action")
            var hasXiaomiAction = false
            for (i in 0 until actions.length) {
                val action = actions.item(i) as Element
                if (action.getAttribute("android:name") == "miui.appwidget.action.APPWIDGET_UPDATE") {
                    hasXiaomiAction = true
                    break
                }
            }
            assertTrue("$shortName must receive miui.appwidget.action.APPWIDGET_UPDATE", hasXiaomiAction)
        }
    }

    /** 小米要求初始根节点使用 background 系统 id，并填满 widget 容器。 */
    @Test
    fun `bitmap widget container has Xiaomi compatible root background`() {
        val file = sequenceOf(
            File("app/src/main/res/layout/widget_bitmap_container.xml"),
            File("src/main/res/layout/widget_bitmap_container.xml")
        ).first { it.isFile }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        assertEquals("@android:id/background", root.getAttribute("android:id"))
        assertEquals("match_parent", root.getAttribute("android:layout_width"))
        assertEquals("match_parent", root.getAttribute("android:layout_height"))
    }

    /**
     * 添加组件时不得弹出强制配置页(白屏闪烁)。
     * 所有现有变体首屏已自动绑定默认课表,无内容可让用户在首次添加时填写;
     * 长按 → 编辑入口仍走应用内 WidgetEditScreen。所以这里强制不允许
     * 任何 info XML 声明 `android:configure`。
     */
    @Test
    fun `no info xml declares android configure - add-to-home must be transparent`() {
        infoXmls.forEach { (name, root) ->
            assertEquals(
                "$name must NOT declare android:configure " +
                    "(it pops a white configure activity on add; the in-app " +
                    "WidgetEditScreen handles reconfigure instead)",
                "",
                root.getAttribute("android:configure")
            )
        }
    }

    /** R4: reconfigurable flag 是启动器显示长按"编辑"菜单的前提 */
    @Test
    fun `every info xml declares widgetFeatures reconfigurable`() {
        infoXmls.forEach { (name, root) ->
            val features = root.getAttribute("android:widgetFeatures")
            assertTrue(
                "$name must declare android:widgetFeatures containing reconfigurable, got \"$features\"",
                features.split('|', ',', ' ').contains("reconfigurable")
            )
        }
    }

    /** 全部 13 个 info XML 都在; 防止新变体漏建 xml */
    @Test
    fun `info xml count matches ALL_WIDGET_VARIANTS`() {
        assertEquals(ALL_WIDGET_VARIANTS.size, infoXmls.size)
    }

    // ---- 设计 §7/§13.3: 三档放置值精确 + minResize 全 40×40 + resizeMode ----

    /** receiver 短类名 → info XML 资源键 (manifest 单一事实来源) */
    private val keyByReceiver: Map<String, String> by lazy {
        manifestReceivers.mapKeys { it.key.substringAfterLast('.') }
    }

    private val tierByReceiver = mapOf(
        // S 档 110×110 targetCell 2×2
        "TodaySmallWidgetReceiver" to Triple("110dp", "110dp", "2x2"),
        "TwoDaySmallWidgetReceiver" to Triple("110dp", "110dp", "2x2"),
        "WeekListSmallWidgetReceiver" to Triple("110dp", "110dp", "2x2"),
        "WeekViewSmallWidgetReceiver" to Triple("110dp", "110dp", "2x2"),
        "WeekGridSmallWidgetProvider" to Triple("110dp", "110dp", "2x2"),
        // M 档 300×160 targetCell 4×2
        "TodayWideWidgetReceiver" to Triple("300dp", "160dp", "4x2"),
        "TwoDayWideWidgetReceiver" to Triple("300dp", "160dp", "4x2"),
        "WeekListWideWidgetReceiver" to Triple("300dp", "160dp", "4x2"),
        // L 档 300×250 targetCell 4×4
        "TodayWidgetReceiver" to Triple("300dp", "250dp", "4x4"),
        "TwoDayWidgetReceiver" to Triple("300dp", "250dp", "4x4"),
        "WeekListWidgetReceiver" to Triple("300dp", "250dp", "4x4"),
        "WeekViewWidgetReceiver" to Triple("300dp", "250dp", "4x4"),
        "WeekGridWidgetProvider" to Triple("300dp", "250dp", "4x4")
    )

    @Test
    fun `every receiver has a tier entry and vice versa`() {
        assertEquals(tierByReceiver.keys, keyByReceiver.keys)
    }

    @Test
    fun `placement sizes match the three tiers exactly`() {
        tierByReceiver.forEach { (receiver, tier) ->
            val (minW, minH, cell) = tier
            val (cellW, cellH) = cell.split('x')
            val root = infoXmls.getValue(keyByReceiver.getValue(receiver))
            assertEquals("$receiver minWidth", minW, root.getAttribute("android:minWidth"))
            assertEquals("$receiver minHeight", minH, root.getAttribute("android:minHeight"))
            assertEquals("$receiver targetCellWidth", cellW, root.getAttribute("android:targetCellWidth"))
            assertEquals("$receiver targetCellHeight", cellH, root.getAttribute("android:targetCellHeight"))
        }
    }

    @Test
    fun `all 13 variants unlock resize both ends with minResize 40x40`() {
        // 用户 2026-09-14 硬要求: 拖拽两端全开放 — minResize 统一 40×40, 不锁纵向
        infoXmls.forEach { (name, root) ->
            assertEquals("$name minResizeWidth", "40dp", root.getAttribute("android:minResizeWidth"))
            assertEquals("$name minResizeHeight", "40dp", root.getAttribute("android:minResizeHeight"))
            assertEquals("$name resizeMode", "horizontal|vertical", root.getAttribute("android:resizeMode"))
        }
    }

    @Test
    fun `M tier previews exist in both drawable dirs with wide aspect`() {
        // 评审 #20: 预览图 drawable 与 drawable-nodpi 两处都要有, M 档 2.7:1
        val base = sequenceOf(
            File("app/src/main/res"), File("src/main/res"), File("../app/src/main/res")
        ).first { File(it, "drawable").isDirectory }
        for (fam in listOf("today", "twoday", "weeklist")) {
            for (d in listOf("drawable", "drawable-nodpi")) {
                val f = File(base, "$d/widget_preview_${fam}_wide.png")
                assertTrue("missing ${f.path}", f.isFile && f.length() > 0)
            }
        }
    }
}
