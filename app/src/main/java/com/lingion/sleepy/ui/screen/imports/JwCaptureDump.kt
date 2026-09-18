package com.lingion.sleepy.ui.screen.imports

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.lingion.sleepy.R
import com.lingion.sleepy.data.jw.JwSchoolInfo
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 排查全量包 dump — 把诊断会话的全量证据打成 zip,落到 Downloads/Sleepy/教务日志/。
 *
 * 设计动机: 学生手头只有"导入失败"反馈, 把诊断取证反推到我们这边。
 * 点「导出排查全量包」→ zip 存盘 → 自动拉系统分享面板 → 学生微信发回。
 *
 * 隐私: 1B 完全不脱敏 — Cookie/学号/表单值/HTML 原文全保留。学生知道他在发什么。
 *
 * 路径分流 (方案 A):
 *   API 29+ → MediaStore.Downloads RELATIVE_PATH = "Download/Sleepy/教务日志/"
 *   API 26-28 → getExternalFilesDir(DOWNLOADS) 应用专属目录,FileProvider 共享
 *
 * 内容:
 *   summary.txt     — 状态/锚点/帧路径/重试/hint/学校/版本
 *   frames/xxx.html — 每个可达 frame 的 outerHTML 原文 (FrameCaptureResult 已含)
 *   dom-inventory.txt — DOM_INVENTORY_JS 现场抓的可点元素清单
 *   netlog.txt      — JwDiagnosticSession 全程网络请求日志
 *   console.txt     — JwDiagnosticSession console 日志
 */
object JwCaptureDump {

    private const val TAG = "JwCaptureDump"
    private const val RELATIVE_DIR = "Sleepy/教务日志"
    private const val ZIP_BASE = "sleepy-jw-dump"
    private val MIME = "application/zip"

    /**
     * 入口 — 由 JwErrorDialog "导出排查全量包" 按钮回调。
     * IO 阻塞, 必须在工作线程调用。
     */
    fun exportDump(
        ctx: Context,
        school: JwSchoolInfo,
        result: FrameCaptureResult,
        domInventoryJson: String?,
    ): DumpResult {
        val stamp = JwDiagnosticSession.currentSessionId()
        val zipName = "$ZIP_BASE-$stamp.zip"
        return try {
            val zipBytes = buildZip(ctx, school, result, domInventoryJson)
            val uri = writeZip(ctx, zipName, zipBytes)
            if (uri != null) {
                DumpResult.Ok(zipName, uri)
            } else {
                DumpResult.Fail("存储失败")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "exportDump failed", e)
            DumpResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 纯函数 — 把所有证据打成 zip 字节。不触碰 Android API,测试直接传 null ctx。 */
    fun buildZip(
        ctx: Context?,
        school: JwSchoolInfo,
        result: FrameCaptureResult,
        domInventoryJson: String?,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            writeSummary(zos, school, result)
            writeFrames(zos, result)
            writeInventory(zos, domInventoryJson)
            writeText(zos, "netlog.txt", JwDiagnosticSession.exportNetlog())
            writeText(zos, "console.txt", JwDiagnosticSession.exportConsole())
        }
        return out.toByteArray()
    }

    private fun writeSummary(zos: ZipOutputStream, school: JwSchoolInfo, r: FrameCaptureResult) {
        val sb = StringBuilder()
        sb.appendLine("# Sleepy JW Diagnostic Summary")
        sb.appendLine("session=${JwDiagnosticSession.currentSessionId()}")
        sb.appendLine("appVersion=${com.lingion.sleepy.BuildConfig.VERSION_NAME} (${com.lingion.sleepy.BuildConfig.VERSION_CODE})")
        sb.appendLine("debug=${com.lingion.sleepy.BuildConfig.DEBUG}")
        sb.appendLine("school=${school.name} type=${school.type} url=${school.url}")
        sb.appendLine("status=${r.status}")
        sb.appendLine("courseCount=${r.courseCount}")
        sb.appendLine("selectedFramePath=${r.selectedFramePath?.joinToString("/") ?: "<none>"}")
        sb.appendLine("matchedAnchors=${r.matchedAnchors.joinToString(",")}")
        sb.appendLine("retryCount=${r.retryCount} maxDepthReached=${r.maxDepthReached}")
        sb.appendLine("blockedFrames=${r.blockedFrames.joinToString(";")}")
        sb.appendLine("skippedFrames=${r.skippedFrames.joinToString(",")}")
        sb.appendLine("diagnosticHint=${r.diagnosticHint}")
        writeText(zos, "summary.txt", sb.toString())
    }

    private fun writeFrames(zos: ZipOutputStream, r: FrameCaptureResult) {
        if (r.html.isNotBlank()) {
            val path = "frames/0-${(r.selectedFramePath?.lastOrNull() ?: "selected").replace(Regex("[^A-Za-z0-9_-]"), "_")}.html"
            writeText(zos, path, r.html)
        }
    }

    private fun writeInventory(zos: ZipOutputStream, json: String?) {
        val body = json?.takeIf { it.isNotBlank() } ?: "{\"url\":\"(no document)\",\"total\":0,\"items\":[]}"
        writeText(zos, "dom-inventory.txt", body)
    }

    private fun writeText(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    /** API 29+: MediaStore.Downloads RELATIVE_PATH = "Download/Sleepy/教务日志" */
    private fun writeZip(ctx: Context, name: String, bytes: ByteArray): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToMediaStoreDownloads(ctx, name, bytes)
        } else {
            writeToAppExternalDownloads(ctx, name, bytes)
        }
    }

    private fun writeToMediaStoreDownloads(ctx: Context, name: String, bytes: ByteArray): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$RELATIVE_DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val resolver = ctx.contentResolver
            val uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "writeToMediaStoreDownloads failed", e)
            null
        }
    }

    /** API 26-28: getExternalFilesDir(Download) 应用专属目录,走 FileProvider 共享 */
    private fun writeToAppExternalDownloads(ctx: Context, name: String, bytes: ByteArray): Uri? {
        return try {
            val baseDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.cacheDir
            val dir = File(baseDir, RELATIVE_DIR).apply { mkdirs() }
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(bytes) }
            androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
        } catch (e: Exception) {
            Log.e(TAG, "writeToAppExternalDownloads failed", e)
            null
        }
    }

    /** 落盘成功后由 Activity 调 — 拉系统分享面板。 */
    fun share(ctx: Context, zipName: String, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zipName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(intent, ctx.getString(R.string.jw_diag_export_chooser))
        )
    }

    sealed class DumpResult {
        data class Ok(val zipName: String, val uri: Uri) : DumpResult()
        data class Fail(val reason: String) : DumpResult()
    }
}