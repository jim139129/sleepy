package com.lingion.sleepy.ui.screen.imports

import android.os.SystemClock
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 诊断会话 — 边加载边记 WebView 期间的网络请求 + console 输出 + 导航 URL。
 *
 * 设计动机: 用户点"导入此页"失败时,App 必须已经掌握本次会话的全量证据。学生不可能
 * 帮我们"再访问一次把请求记下来",失败就是失败,要的就是当下快照。
 *
 * 线程模型: shouldInterceptRequest 在 chromium background thread 调用,
 * onConsoleMessage 在 WebChromeClient 主线程调用,导航 URL 在主线程。
 * 必须线程安全——使用 ConcurrentLinkedDeque (lock-free) + 写后定长截断。
 *
 * 容量策略: 500 条/通道。教务页整页加载 ~100-300 条请求 (含 iframe/CSS/字体),500
 * 足够覆盖完整学期的登录链。超出后头丢弃 (addFirst 后降序,然后 removeLast 截断)——
 * 失败时首屏 + 主请求链最关键,过早加载的小图标请求可弃。
 */
object JwDiagnosticSession {

    private const val RING_LIMIT = 500

    private data class RequestLog(
        val ts: Long,
        val method: String,
        val url: String,
        val status: Int,
        val mime: String?,
    )

    private data class ConsoleLog(
        val ts: Long,
        val level: String,
        val message: String,
        val sourceId: String?,
    )

    private val requests = ConcurrentLinkedDeque<RequestLog>()
    private val consoles = ConcurrentLinkedDeque<ConsoleLog>()
    private val startTimeMs = SystemClock.elapsedRealtime()
    private var sessionId: String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun resetSession() {
        requests.clear()
        consoles.clear()
        sessionId = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    }

    fun currentSessionId(): String = sessionId

    /** WebViewClient.shouldInterceptRequest 回调里调。status/mime 由 WebResourceResponse 推断。 */
    fun recordRequest(request: WebResourceRequest, response: WebResourceResponse?) {
        val log = RequestLog(
            ts = SystemClock.elapsedRealtime() - startTimeMs,
            method = request.method ?: "GET",
            url = request.url?.toString() ?: "",
            status = response?.let { inferStatus(it) } ?: 0,
            mime = response?.mimeType,
        )
        requests.addFirst(log)
        while (requests.size > RING_LIMIT) requests.pollLast()
    }

    /** status 是 WebView 不直接暴露的——只能从 response.statusCode 或推断。优先 statusCode。 */
    private fun inferStatus(r: WebResourceResponse): Int = try {
        // WebResourceResponse 自 API 23 起有 statusCode 字段,但 plugin/main 文档标记
        // deprecated——fallback 到 0 表示"未提供"。
        r.statusCode
    } catch (e: Throwable) {
        0
    }

    /** WebChromeClient.onConsoleMessage 回调里调。 */
    fun recordConsole(msg: ConsoleMessage?) {
        if (msg == null) return
        val log = ConsoleLog(
            ts = SystemClock.elapsedRealtime() - startTimeMs,
            level = msg.messageLevel().name,
            message = msg.message() ?: "",
            sourceId = msg.sourceId(),
        )
        consoles.addFirst(log)
        while (consoles.size > RING_LIMIT) consoles.pollLast()
    }

    /** dump 阶段由 JwCaptureDump 调用,生成 netlog.txt 用的文本。 */
    fun exportNetlog(): String = buildString {
        appendLine("# Session: $sessionId")
        appendLine("# Total: ${requests.size} requests")
        appendLine("# Format: +offsetMs METHOD status mime url")
        appendLine()
        requests.toList().asReversed().forEach { r ->
            appendLine("+${r.ts}ms ${r.method} ${r.status} ${r.mime ?: "-"} ${r.url}")
        }
    }

    fun exportConsole(): String = buildString {
        appendLine("# Session: $sessionId")
        appendLine("# Total: ${consoles.size} console messages")
        appendLine("# Format: +offsetMs LEVEL sourceId:line:col  message")
        appendLine()
        consoles.toList().asReversed().forEach { c ->
            appendLine("+${c.ts}ms ${c.level} ${c.sourceId ?: "-"}: ${c.message}")
        }
    }
}