package com.example.a123tome

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalHttpServer(
    private val context: Context,
    port: Int,
    hostname: String = "0.0.0.0"
) : NanoHTTPD(hostname, port) {

    private val TAG = "LocalHttpServer"
    private val sharedDir: File by lazy {
        File(context.filesDir, "WLAN-Share").apply { mkdirs() }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                method == Method.GET && uri == "/" -> serveMainPage()
                method == Method.GET && uri == "/status" -> serveStatus()
                method == Method.GET && uri == "/api/files" -> serveFileList()
                method == Method.GET && uri == "/api/config" -> serveConfig()
                method == Method.GET && uri == "/zip" -> serveZip()
                method == Method.GET && uri.startsWith("/download/") -> serveDownload(uri)
                method == Method.POST && uri == "/upload" -> handleUpload(session)
                method == Method.DELETE && uri.startsWith("/delete/") -> handleDelete(uri)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun serveStatus(): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok","port":${listeningPort}}""")
    }

    private fun serveConfig(): Response {
        // Phone server doesn't have BASE_DIR capability
        return newFixedLengthResponse(
            Response.Status.OK, 
            "application/json", 
            """{"shared_dir":"${sharedDir.absolutePath}","base_dir":null,"base_dir_readonly":false,"base_dir_available":false}"""
        )
    }

    private fun serveFileList(): Response {
        val files = sharedDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
        val jsonArray = JSONArray()
        files.sortedBy { it.name }.forEach { file ->
            val obj = JSONObject()
            obj.put("name", file.name)
            obj.put("size", file.length())
            obj.put("size_mb", String.format("%.2f", file.length() / (1024.0 * 1024.0)))
            jsonArray.put(obj)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    private fun serveDownload(uri: String): Response {
        val filename = uri.removePrefix("/download/")
        val decodedFilename = java.net.URLDecoder.decode(filename, "UTF-8")
        val file = File(sharedDir, decodedFilename)
        
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        val fis = FileInputStream(file)
        return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", fis, file.length()).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        }
    }

    private fun serveZip(): Response {
        val files = sharedDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
        if (files.isEmpty()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No files to download")
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            files.forEach { file ->
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        val zipBytes = baos.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "application/zip", ByteArrayInputStream(zipBytes), zipBytes.size.toLong()).apply {
            addHeader("Content-Disposition", "attachment; filename=\"shared_files.zip\"")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)

        val uploadedFile = files["file"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "application/json", """{"error":"No file uploaded"}"""
        )

        val tempFile = File(uploadedFile)
        val params = session.parameters
        val originalFilename = params["file"]?.firstOrNull() 
            ?: session.headers["content-disposition"]?.let { 
                Regex("filename=\"?([^\"]+)\"?").find(it)?.groupValues?.get(1) 
            }
            ?: "uploaded_${System.currentTimeMillis()}"

        val safeFilename = originalFilename.replace("/", "_").replace("\\", "_")
        val destFile = File(sharedDir, safeFilename)
        
        tempFile.copyTo(destFile, overwrite = true)
        tempFile.delete()

        Log.i(TAG, "Uploaded file: $safeFilename")
        return newFixedLengthResponse(
            Response.Status.OK, "application/json", 
            """{"message":"File uploaded successfully","filename":"$safeFilename"}"""
        )
    }

    private fun handleDelete(uri: String): Response {
        val filename = uri.removePrefix("/delete/")
        val decodedFilename = java.net.URLDecoder.decode(filename, "UTF-8")
        val file = File(sharedDir, decodedFilename)

        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"File not found"}""")
        }

        file.delete()
        Log.i(TAG, "Deleted file: $decodedFilename")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"message":"File deleted successfully"}""")
    }

    private fun serveMainPage(): Response {
        val files = sharedDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
        
        val fileCardsHtml = if (files.isNotEmpty()) {
            files.joinToString("\n") { file ->
                val sizeMb = String.format("%.2f", file.length() / (1024.0 * 1024.0))
                val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8")
                """
                <div class="file-card">
                    <div class="file-info">
                        <div class="file-icon">📄</div>
                        <div class="file-details">
                            <div class="file-name">${escapeHtml(file.name)}</div>
                            <div class="file-size">$sizeMb MB</div>
                        </div>
                    </div>
                    <div class="file-actions">
                        <a href="/download/$encodedName" class="download-btn" data-filename="${escapeHtml(file.name)}">⬇ Download</a>
                        <button type="button" class="delete-btn" data-filename="${escapeHtml(file.name)}">🗑</button>
                    </div>
                </div>
                """
            }
        } else ""

        val filesSection = if (files.isNotEmpty()) {
            """<div class="files-grid">$fileCardsHtml</div>"""
        } else {
            """<div class="empty-state"><div class="empty-icon">📂</div><h3>Keine Dateien vorhanden</h3><p>Dateien hochladen um zu beginnen</p></div>"""
        }

        val zipButton = if (files.isNotEmpty()) """<a href="/zip" class="zip-download-btn">📦 Alles als ZIP</a>""" else ""

        val html = """
<!DOCTYPE html>
<html lang="de">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>WLAN Share</title>
    <style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.6; color: #333; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); min-height: 100vh; overflow-x: hidden; }
.container { max-width: 1200px; margin: 0 auto; padding: 20px; width: 100%; }
header { text-align: center; margin-bottom: 20px; color: white; }
header h1 { font-size: 2rem; margin-bottom: 8px; text-shadow: 2px 2px 4px rgba(0,0,0,0.3); }
header p { font-size: 1rem; opacity: 0.9; }
section { background: white; border-radius: 15px; padding: 20px; margin-bottom: 20px; box-shadow: 0 10px 30px rgba(0,0,0,0.1); overflow: hidden; }

/* Mode toggle */
.mode-toggle-container { display: flex; justify-content: center; margin-bottom: 20px; gap: 0; }
.mode-toggle-btn { padding: 12px 16px; border: 2px solid rgba(255,255,255,0.5); background: rgba(255,255,255,0.1); color: white; font-weight: 600; cursor: pointer; transition: all 0.3s ease; font-size: 13px; -webkit-tap-highlight-color: transparent; }
.mode-toggle-btn:first-child { border-radius: 8px 0 0 8px; border-right: none; }
.mode-toggle-btn:last-child { border-radius: 0 8px 8px 0; }
.mode-toggle-btn.active { background: white; color: #667eea; }
.mode-toggle-btn:not(.active):active { background: rgba(255,255,255,0.2); }

/* Shared space hidden class */
.shared-space.hidden { display: none; }

/* Desktop folders browser */
.folder-browser { display: none; }
.folder-browser.active { display: block; }

.breadcrumb { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 12px; background: #f8f9fa; border-radius: 8px; margin-bottom: 12px; font-size: 13px; }
.breadcrumb-item { color: #667eea; cursor: pointer; text-decoration: none; -webkit-tap-highlight-color: transparent; }
.breadcrumb-item:active { opacity: 0.7; }
.breadcrumb-separator { color: #9ca3af; }
.breadcrumb-current { color: #374151; font-weight: 500; }

.folder-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 12px; margin-bottom: 16px; }
.folder-card { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 16px 12px; background: white; border-radius: 12px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); cursor: pointer; transition: all 0.2s ease; border: 1px solid #e5e7eb; -webkit-tap-highlight-color: transparent; }
.folder-card:active { transform: scale(0.98); border-color: #667eea; }
.folder-icon { font-size: 32px; }
.folder-name { font-size: 12px; font-weight: 500; color: #374151; text-align: center; word-break: break-word; max-width: 100%; }

.browser-file-card { display: flex; align-items: center; justify-content: space-between; padding: 12px; background: white; border-radius: 10px; box-shadow: 0 2px 8px rgba(0,0,0,0.05); border: 1px solid #e5e7eb; margin-bottom: 8px; }
.browser-file-info { display: flex; align-items: center; gap: 10px; flex: 1; min-width: 0; }
.browser-file-icon { width: 32px; height: 32px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); border-radius: 6px; display: flex; align-items: center; justify-content: center; color: white; font-size: 14px; flex-shrink: 0; }
.browser-file-details { flex: 1; min-width: 0; }
.browser-file-name { font-weight: 500; color: #374151; word-break: break-word; font-size: 13px; }
.browser-file-meta { font-size: 11px; color: #9ca3af; }
.browser-file-actions { display: flex; gap: 6px; }
.browser-action-btn { padding: 8px 10px; border-radius: 6px; border: none; cursor: pointer; font-size: 12px; font-weight: 500; transition: all 0.2s; -webkit-tap-highlight-color: transparent; }
.browser-download-btn { background: linear-gradient(135deg, #10b981 0%, #059669 100%); color: white; }
.browser-delete-btn { background: #fee2e2; color: #dc2626; }
.browser-download-btn:active, .browser-delete-btn:active { opacity: 0.8; transform: scale(0.95); }

.browser-upload-area { background: linear-gradient(135deg, #f0f4ff 0%, #faf5ff 100%); border: 2px dashed #667eea; border-radius: 12px; padding: 20px; text-align: center; margin-bottom: 16px; cursor: pointer; transition: all 0.3s ease; -webkit-tap-highlight-color: transparent; }
.browser-upload-area:active { border-color: #764ba2; background: #f3e8ff; }
.browser-upload-area.dragover { border-color: #764ba2; background: #f3e8ff; }
.browser-upload-btn { display: inline-block; padding: 8px 16px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border: none; border-radius: 8px; font-weight: 600; cursor: pointer; margin-top: 10px; font-size: 13px; }

.readonly-notice { background: #fef3c7; border: 1px solid #f59e0b; border-radius: 8px; padding: 10px 14px; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; color: #92400e; font-size: 13px; }

.no-basedir-notice { background: #f3f4f6; border-radius: 12px; padding: 30px 20px; text-align: center; color: #6b7280; }
.no-basedir-notice h3 { margin-bottom: 8px; color: #374151; font-size: 16px; }
.no-basedir-notice p { font-size: 13px; }

.empty-folder { background: #f9fafb; border-radius: 12px; padding: 30px 20px; text-align: center; color: #9ca3af; }

.browser-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 10px; }
.browser-path-title { font-size: 14px; font-weight: 600; color: #374151; }
.create-folder-btn { padding: 8px 12px; background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%); color: white; border: none; border-radius: 6px; cursor: pointer; font-weight: 500; font-size: 12px; -webkit-tap-highlight-color: transparent; }
.create-folder-btn:active { opacity: 0.8; transform: scale(0.95); }

/* Modal */
.modal-overlay { display: none; position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); z-index: 1000; justify-content: center; align-items: center; padding: 20px; }
.modal-overlay.active { display: flex; }
.modal { background: white; border-radius: 12px; padding: 20px; width: 100%; max-width: 350px; box-shadow: 0 20px 40px rgba(0,0,0,0.2); }
.modal h3 { margin-bottom: 14px; color: #374151; font-size: 16px; }
.modal input { width: 100%; padding: 12px; border: 1px solid #d1d5db; border-radius: 8px; font-size: 14px; margin-bottom: 14px; }
.modal-buttons { display: flex; justify-content: flex-end; gap: 10px; }
.modal-btn { padding: 10px 16px; border-radius: 8px; border: none; cursor: pointer; font-weight: 500; font-size: 13px; -webkit-tap-highlight-color: transparent; }
.modal-btn-cancel { background: #f3f4f6; color: #374151; }
.modal-btn-create { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; }
.modal-btn:active { opacity: 0.8; }

/* Existing styles */
.upload-section { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; }
.upload-area { text-align: center; padding: 30px 15px; border: 3px dashed rgba(255,255,255,0.5); border-radius: 15px; transition: all 0.3s ease; margin-bottom: 15px; }
.upload-area.dragover { border-color: rgba(255,255,255,0.8); background: rgba(255,255,255,0.1); }
.upload-icon { font-size: 2.5rem; margin-bottom: 10px; }
.select-files-btn { background: rgba(255,255,255,0.2); color: white; border: 2px solid rgba(255,255,255,0.5); padding: 12px 24px; border-radius: 25px; cursor: pointer; font-size: 1rem; font-weight: 600; transition: all 0.3s ease; -webkit-tap-highlight-color: transparent; }
.select-files-btn:active { background: rgba(255,255,255,0.4); transform: scale(0.98); }
.progress-container { margin: 15px 0; }
.progress-bar { width: 100%; height: 20px; background: rgba(255,255,255,0.2); border-radius: 10px; overflow: hidden; margin-bottom: 10px; }
.progress-fill { height: 100%; background: linear-gradient(90deg, #48bb78, #38a169); width: 0%; transition: width 0.3s ease; }
.progress-text { text-align: center; font-weight: 600; }
.selected-files { background: rgba(255,255,255,0.1); border-radius: 10px; padding: 15px; margin-top: 15px; }
.upload-btn { background: rgba(255,255,255,0.2); color: white; border: 2px solid rgba(255,255,255,0.5); padding: 12px 20px; border-radius: 25px; cursor: pointer; font-size: 1rem; font-weight: 600; width: 100%; margin-top: 10px; -webkit-tap-highlight-color: transparent; }
.upload-btn:active { background: rgba(255,255,255,0.4); }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-wrap: wrap; gap: 10px; }
.zip-download-btn { background: linear-gradient(135deg, #4facfe 0%, #00f2fe 100%); color: white; text-decoration: none; padding: 10px 20px; border-radius: 25px; font-weight: 600; transition: all 0.3s ease; display: inline-block; -webkit-tap-highlight-color: transparent; }
.files-grid { display: grid; grid-template-columns: 1fr; gap: 15px; }
.file-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 12px; padding: 15px; transition: all 0.3s ease; }
.file-info { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.file-icon { font-size: 1.8rem; flex-shrink: 0; }
.file-details { flex: 1; min-width: 0; }
.file-name { font-weight: 600; color: #2d3748; margin-bottom: 4px; word-break: break-word; font-size: 0.95rem; }
.file-size { color: #718096; font-size: 0.85rem; }
.file-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.download-btn, .delete-btn { padding: 10px 16px; border-radius: 8px; text-decoration: none; font-size: 0.9rem; font-weight: 500; border: none; cursor: pointer; -webkit-tap-highlight-color: transparent; flex: 1; text-align: center; min-width: 100px; }
.download-btn { background: linear-gradient(135deg, #48bb78 0%, #38a169 100%); color: white; }
.delete-btn { background: linear-gradient(135deg, #f56565 0%, #e53e3e 100%); color: white; }
.download-btn:active, .delete-btn:active { opacity: 0.8; transform: scale(0.98); }
.empty-state { text-align: center; padding: 40px 20px; color: #718096; }
.empty-icon { font-size: 3rem; margin-bottom: 15px; }
.sync-text-section { padding: 20px; }
.sync-text-section h3 { margin-bottom: 12px; color: #2d3748; font-weight: 600; font-size: 1.1rem; }
.sync-text-container { display: flex; flex-direction: column; gap: 10px; width: 100%; }
.sync-text-row { display: flex; gap: 8px; width: 100%; }
.text-input-wrapper { flex: 1; position: relative; min-width: 0; }
.sync-text-field { width: 100%; padding: 12px 50px 12px 12px; border: 2px solid #e2e8f0; border-radius: 12px; font-size: 16px; font-family: inherit; resize: none; transition: all 0.3s ease; line-height: 1.4; background: #ffffff; min-height: 45px; -webkit-appearance: none; }
.sync-text-field:focus { outline: none; border-color: #667eea; box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1); }
.text-field-actions { position: absolute; right: 6px; top: 50%; transform: translateY(-50%); display: flex; align-items: center; gap: 4px; }
.status-indicator { font-size: 0.9rem; font-weight: bold; color: #9ca3af; }
.send-btn { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border: none; width: 34px; height: 34px; border-radius: 8px; cursor: pointer; display: flex; align-items: center; justify-content: center; -webkit-tap-highlight-color: transparent; flex-shrink: 0; }
.send-btn:active { opacity: 0.8; transform: scale(0.95); }
.sync-buttons { display: flex; gap: 8px; }
.expand-btn, .copy-btn { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; border: none; width: 44px; height: 44px; border-radius: 10px; cursor: pointer; display: flex; align-items: center; justify-content: center; -webkit-tap-highlight-color: transparent; flex-shrink: 0; }
.expand-btn:active, .copy-btn:active { opacity: 0.8; transform: scale(0.95); }
.copy-btn.success { background: linear-gradient(135deg, #48bb78 0%, #38a169 100%); }
@media (min-width: 600px) {
    .container { padding: 30px; }
    header h1 { font-size: 2.5rem; }
    .files-grid { grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); }
    .sync-text-container { flex-direction: row; align-items: flex-start; }
    .sync-text-row { flex: 1; }
    .sync-buttons { flex-direction: column; }
    .folder-grid { grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); }
}
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>📁 WLAN Share</h1>
            <p>Dateien einfach über das lokale Netzwerk teilen</p>
        </header>

        <!-- Mode Toggle -->
        <div class="mode-toggle-container">
            <button class="mode-toggle-btn active" id="sharedModeBtn">📁 Freigegebene Dateien</button>
            <button class="mode-toggle-btn" id="browserModeBtn">🖥️ Desktop-Ordner</button>
        </div>

        <!-- SHARED SPACE MODE -->
        <div id="sharedSpace" class="shared-space">
            <section class="upload-section">
                <div class="upload-area" id="uploadArea">
                    <div class="upload-icon">📤</div>
                    <h3>Dateien hochladen</h3>
                    <p>Tippen zum Auswählen</p>
                    <input type="file" id="fileInput" multiple accept="*/*">
                    <button class="select-files-btn" id="selectBtn">Dateien auswählen</button>
                </div>
                <div class="progress-container" id="progressContainer" style="display: none;">
                    <div class="progress-bar"><div class="progress-fill" id="progressFill"></div></div>
                    <div class="progress-text" id="progressText">0%</div>
                </div>
                <div class="selected-files" id="selectedFiles" style="display: none;">
                    <h4>Ausgewählte Dateien:</h4>
                    <div class="file-list" id="fileList"></div>
                    <button class="upload-btn" id="uploadBtn">⬆ Hochladen</button>
                </div>
            </section>

            <section class="sync-text-section">
                <h3>📋 Sync-Text</h3>
                <div class="sync-text-container">
                    <div class="sync-text-row">
                        <div class="text-input-wrapper">
                            <textarea id="syncText" class="sync-text-field" placeholder="Text hier eingeben..." rows="1"></textarea>
                            <div class="text-field-actions">
                                <span class="status-indicator" id="statusIndicator"></span>
                                <button class="send-btn" id="sendBtn" title="Send">
                                    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                                        <line x1="22" y1="2" x2="11" y2="13"></line>
                                        <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
                                    </svg>
                                </button>
                            </div>
                        </div>
                    </div>
                    <div class="sync-buttons">
                        <button class="expand-btn" id="expandBtn" title="Expand/Collapse">
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                                <polyline points="7 13 12 18 17 13"></polyline>
                                <polyline points="7 6 12 11 17 6"></polyline>
                            </svg>
                        </button>
                        <button class="copy-btn" id="copyBtn" title="Copy to clipboard">
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                            </svg>
                        </button>
                    </div>
                </div>
            </section>

            <section class="file-list-section">
                <div class="section-header">
                    <h2>📂 Dateien (${files.size})</h2>
                    $zipButton
                </div>
                $filesSection
            </section>
        </div>

        <!-- DESKTOP FOLDERS MODE -->
        <div id="folderBrowser" class="folder-browser">
            <section>
                <div id="browserContent">
                    <div class="no-basedir-notice" id="noBaseDirNotice">
                        <h3>📂 Desktop-Ordner nicht verfügbar</h3>
                        <p>Dieser Modus ist nur verfügbar, wenn Sie mit einem Desktop-Server verbunden sind.</p>
                        <p style="margin-top:10px;font-size:12px;color:#9ca3af;">Der Server wird vom Handy gehostet.</p>
                    </div>
                </div>
            </section>
        </div>
    </div>

    <!-- Create Folder Modal -->
    <div class="modal-overlay" id="createFolderModal">
        <div class="modal">
            <h3>📁 Neuer Ordner</h3>
            <input type="text" id="newFolderName" placeholder="Ordnername eingeben...">
            <div class="modal-buttons">
                <button class="modal-btn modal-btn-cancel" id="modalCancelBtn">Abbrechen</button>
                <button class="modal-btn modal-btn-create" id="modalCreateBtn">Erstellen</button>
            </div>
        </div>
    </div>

    <script>
        // ========== MODE MANAGEMENT ==========
        let currentMode = 'shared';
        let browserConfig = null;
        let browserCurrentPath = '';
        let browserLastTreeData = null;
        let desktopServerUrl = null; // Will be set if connected to desktop
        
        const sharedModeBtn = document.getElementById('sharedModeBtn');
        const browserModeBtn = document.getElementById('browserModeBtn');
        const sharedSpace = document.getElementById('sharedSpace');
        const folderBrowser = document.getElementById('folderBrowser');
        
        sharedModeBtn.addEventListener('click', function() { switchMode('shared'); });
        browserModeBtn.addEventListener('click', function() { switchMode('browser'); });
        
        function switchMode(mode) {
            currentMode = mode;
            sharedModeBtn.classList.toggle('active', mode === 'shared');
            browserModeBtn.classList.toggle('active', mode === 'browser');
            sharedSpace.classList.toggle('hidden', mode === 'browser');
            folderBrowser.classList.toggle('active', mode === 'browser');
            
            if (mode === 'browser') {
                loadBrowserConfig();
            }
        }
        
        async function loadBrowserConfig() {
            // When phone is hosting, desktop folders aren't available
            // This notice is already shown in the HTML
            // If we had a desktop server URL, we would fetch from it here
            if (desktopServerUrl) {
                try {
                    const response = await fetch(desktopServerUrl + '/api/config');
                    if (response.ok) {
                        browserConfig = await response.json();
                        if (browserConfig.base_dir_available) {
                            document.getElementById('noBaseDirNotice').style.display = 'none';
                            browserCurrentPath = '';
                            await loadFolderTree();
                        }
                    }
                } catch (error) {
                    console.error('Error loading config:', error);
                }
            }
        }
        
        async function loadFolderTree(path) {
            if (!desktopServerUrl) return;
            
            path = path || '';
            browserCurrentPath = path;
            
            try {
                const response = await fetch(desktopServerUrl + '/api/tree?path=' + encodeURIComponent(path));
                if (!response.ok) throw new Error('Failed to load folder');
                
                const data = await response.json();
                browserLastTreeData = data;
                renderFolderTree(data);
            } catch (error) {
                console.error('Error loading folder tree:', error);
                document.getElementById('browserContent').innerHTML = '<div class="empty-folder"><p>❌ Fehler beim Laden</p></div>';
            }
        }
        
        function renderFolderTree(data) {
            const currentPath = data.currentPath;
            const parentPath = data.parentPath;
            const items = data.items;
            const readonly = data.readonly;
            
            let breadcrumbHTML = '<span class="breadcrumb-item" onclick="loadFolderTree(\\'\\')">🏠 Root</span>';
            
            if (currentPath) {
                const parts = currentPath.split('/').filter(function(p) { return p; });
                let pathSoFar = '';
                
                for (let i = 0; i < parts.length; i++) {
                    pathSoFar += (i > 0 ? '/' : '') + parts[i];
                    breadcrumbHTML += '<span class="breadcrumb-separator">/</span>';
                    
                    if (i === parts.length - 1) {
                        breadcrumbHTML += '<span class="breadcrumb-current">' + parts[i] + '</span>';
                    } else {
                        breadcrumbHTML += '<span class="breadcrumb-item" onclick="loadFolderTree(\\'' + pathSoFar + '\\')">' + parts[i] + '</span>';
                    }
                }
            }
            
            const folders = items.filter(function(i) { return i.type === 'dir'; });
            const files = items.filter(function(i) { return i.type === 'file'; });
            
            let html = '<nav class="breadcrumb">' + breadcrumbHTML + '</nav>';
            
            if (readonly) {
                html += '<div class="readonly-notice"><span>🔒</span><span>Nur-Lesen-Modus</span></div>';
            }
            
            if (!readonly) {
                html += '<div class="browser-upload-area" id="browserUploadArea"><div>📤 Dateien hochladen</div><small>Tippen zum Auswählen</small><input type="file" id="browserFileInput" multiple style="display:none;"></div>';
            }
            
            html += '<div class="browser-toolbar"><span class="browser-path-title">' + folders.length + ' Ordner, ' + files.length + ' Dateien</span>';
            if (!readonly) {
                html += '<button class="create-folder-btn" id="createFolderBtn">📁 Neu</button>';
            }
            html += '</div>';
            
            if (folders.length > 0) {
                html += '<div class="folder-grid">';
                for (let i = 0; i < folders.length; i++) {
                    const folder = folders[i];
                    const folderPath = currentPath ? currentPath + '/' + folder.name : folder.name;
                    html += '<div class="folder-card" data-path="' + folderPath + '"><div class="folder-icon">📁</div><div class="folder-name">' + folder.name + '</div></div>';
                }
                html += '</div>';
            }
            
            if (files.length > 0) {
                html += '<div class="browser-files-list">';
                for (let i = 0; i < files.length; i++) {
                    const file = files[i];
                    const sizeMB = file.size_mb !== null ? file.size_mb + ' MB' : '';
                    html += '<div class="browser-file-card"><div class="browser-file-info"><div class="browser-file-icon">📄</div><div class="browser-file-details"><div class="browser-file-name">' + file.name + '</div><div class="browser-file-meta">' + sizeMB + '</div></div></div><div class="browser-file-actions"><button class="browser-action-btn browser-download-btn" data-path="' + currentPath + '" data-name="' + file.name + '">⬇️</button>';
                    if (!readonly) {
                        html += '<button class="browser-action-btn browser-delete-btn" data-path="' + currentPath + '" data-name="' + file.name + '">🗑️</button>';
                    }
                    html += '</div></div>';
                }
                html += '</div>';
            }
            
            if (folders.length === 0 && files.length === 0) {
                html += '<div class="empty-folder"><p>📂 Leerer Ordner</p></div>';
            }
            
            document.getElementById('browserContent').innerHTML = html;
            
            // Setup event listeners
            setupBrowserListeners(readonly);
        }
        
        function setupBrowserListeners(readonly) {
            // Folder click
            document.querySelectorAll('.folder-card').forEach(function(card) {
                card.addEventListener('click', function() {
                    loadFolderTree(this.getAttribute('data-path'));
                });
            });
            
            // Download click
            document.querySelectorAll('.browser-download-btn').forEach(function(btn) {
                btn.addEventListener('click', function(e) {
                    e.stopPropagation();
                    browserDownload(this.getAttribute('data-path'), this.getAttribute('data-name'));
                });
            });
            
            // Delete click
            document.querySelectorAll('.browser-delete-btn').forEach(function(btn) {
                btn.addEventListener('click', function(e) {
                    e.stopPropagation();
                    browserDelete(this.getAttribute('data-path'), this.getAttribute('data-name'));
                });
            });
            
            // Upload area
            const uploadArea = document.getElementById('browserUploadArea');
            const fileInput = document.getElementById('browserFileInput');
            if (uploadArea && fileInput) {
                uploadArea.addEventListener('click', function() { fileInput.click(); });
                fileInput.addEventListener('change', function() { handleBrowserUpload(this.files); });
            }
            
            // Create folder
            const createBtn = document.getElementById('createFolderBtn');
            if (createBtn) {
                createBtn.addEventListener('click', openCreateFolderModal);
            }
        }
        
        async function handleBrowserUpload(files) {
            if (!files || files.length === 0 || !desktopServerUrl) return;
            
            for (let i = 0; i < files.length; i++) {
                const formData = new FormData();
                formData.append('file', files[i]);
                
                try {
                    await fetch(desktopServerUrl + '/browse/upload?path=' + encodeURIComponent(browserCurrentPath), {
                        method: 'POST',
                        body: formData
                    });
                } catch (error) {
                    console.error('Upload error:', error);
                }
            }
            
            await loadFolderTree(browserCurrentPath);
        }
        
        function browserDownload(path, name) {
            if (!desktopServerUrl) return;
            const url = desktopServerUrl + '/browse/download?path=' + encodeURIComponent(path) + '&name=' + encodeURIComponent(name);
            window.location.href = url;
        }
        
        async function browserDelete(path, name) {
            if (!desktopServerUrl) return;
            if (!confirm('Datei "' + name + '" löschen?')) return;
            
            try {
                const response = await fetch(desktopServerUrl + '/browse/delete?path=' + encodeURIComponent(path) + '&name=' + encodeURIComponent(name), {
                    method: 'DELETE'
                });
                
                if (response.ok) {
                    await loadFolderTree(browserCurrentPath);
                } else {
                    const error = await response.json();
                    alert('Fehler: ' + error.detail);
                }
            } catch (error) {
                console.error('Delete error:', error);
                alert('Fehler beim Löschen');
            }
        }
        
        // Modal
        const modal = document.getElementById('createFolderModal');
        const modalInput = document.getElementById('newFolderName');
        const modalCancelBtn = document.getElementById('modalCancelBtn');
        const modalCreateBtn = document.getElementById('modalCreateBtn');
        
        function openCreateFolderModal() {
            modal.classList.add('active');
            modalInput.value = '';
            modalInput.focus();
        }
        
        function closeCreateFolderModal() {
            modal.classList.remove('active');
        }
        
        modalCancelBtn.addEventListener('click', closeCreateFolderModal);
        modalCreateBtn.addEventListener('click', createFolder);
        modalInput.addEventListener('keydown', function(e) {
            if (e.key === 'Enter') createFolder();
            else if (e.key === 'Escape') closeCreateFolderModal();
        });
        
        async function createFolder() {
            const name = modalInput.value.trim();
            if (!name || !desktopServerUrl) return;
            
            try {
                const response = await fetch(desktopServerUrl + '/browse/mkdir?path=' + encodeURIComponent(browserCurrentPath) + '&name=' + encodeURIComponent(name), {
                    method: 'POST'
                });
                
                if (response.ok) {
                    closeCreateFolderModal();
                    await loadFolderTree(browserCurrentPath);
                } else {
                    const error = await response.json();
                    alert('Fehler: ' + error.detail);
                }
            } catch (error) {
                console.error('Create folder error:', error);
                alert('Fehler beim Erstellen');
            }
        }
        
        // ========== SHARED SPACE FUNCTIONALITY ==========
        let selectedFiles = [];
        const fileInput = document.getElementById('fileInput');
        const selectBtn = document.getElementById('selectBtn');
        const uploadArea = document.getElementById('uploadArea');
        const selectedFilesDiv = document.getElementById('selectedFiles');
        const fileListDiv = document.getElementById('fileList');
        const progressContainer = document.getElementById('progressContainer');
        const progressFill = document.getElementById('progressFill');
        const progressText = document.getElementById('progressText');
        const uploadBtn = document.getElementById('uploadBtn');

        fileInput.style.cssText = 'position:absolute;width:1px;height:1px;opacity:0;overflow:hidden;';

        selectBtn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            fileInput.click();
        });

        uploadArea.addEventListener('click', function(e) {
            if (e.target === selectBtn || e.target.closest('.select-files-btn')) return;
            fileInput.click();
        });

        uploadBtn.addEventListener('click', function(e) {
            e.preventDefault();
            uploadFiles();
        });

        uploadArea.addEventListener('dragover', function(e) { e.preventDefault(); this.classList.add('dragover'); });
        uploadArea.addEventListener('dragleave', function() { this.classList.remove('dragover'); });
        uploadArea.addEventListener('drop', function(e) {
            e.preventDefault();
            this.classList.remove('dragover');
            selectedFiles = Array.from(e.dataTransfer.files);
            displaySelectedFiles();
        });

        fileInput.addEventListener('change', function() {
            selectedFiles = Array.from(this.files);
            displaySelectedFiles();
        });

        function displaySelectedFiles() {
            if (selectedFiles.length === 0) return;
            selectedFilesDiv.style.display = 'block';
            fileListDiv.innerHTML = '';
            selectedFiles.forEach(function(file) {
                const div = document.createElement('div');
                div.textContent = file.name;
                div.style.padding = '5px 0';
                fileListDiv.appendChild(div);
            });
        }

        async function uploadFiles() {
            if (selectedFiles.length === 0) {
                alert('Bitte wählen Sie zuerst Dateien aus.');
                return;
            }
            progressContainer.style.display = 'block';
            uploadBtn.disabled = true;
            
            for (let i = 0; i < selectedFiles.length; i++) {
                const formData = new FormData();
                formData.append('file', selectedFiles[i], selectedFiles[i].name);
                try {
                    const response = await fetch('/upload', { method: 'POST', body: formData });
                    if (!response.ok) throw new Error('Upload failed');
                    const progress = Math.round(((i + 1) / selectedFiles.length) * 100);
                    progressFill.style.width = progress + '%';
                    progressText.textContent = progress + '%';
                } catch (error) {
                    console.error('Upload error:', error);
                    alert('Fehler beim Hochladen: ' + error.message);
                }
            }
            setTimeout(function() { location.reload(); }, 1000);
        }

        async function deleteFile(filename) {
            if (!confirm('Datei "' + filename + '" löschen?')) return;
            try {
                const response = await fetch('/delete/' + encodeURIComponent(filename), { method: 'DELETE' });
                if (!response.ok) throw new Error('Delete failed');
                location.reload();
            } catch (error) {
                console.error('Delete error:', error);
                alert('Fehler beim Löschen: ' + error.message);
            }
        }
        
        document.addEventListener('click', function(e) {
            const deleteBtn = e.target.closest('.delete-btn');
            if (deleteBtn) {
                e.preventDefault();
                e.stopPropagation();
                const filename = deleteBtn.getAttribute('data-filename');
                if (filename) {
                    deleteFile(filename);
                }
            }
        }, true);

        // Sync text
        const syncText = document.getElementById('syncText');
        const statusIndicator = document.getElementById('statusIndicator');
        const sendBtn = document.getElementById('sendBtn');
        const expandBtn = document.getElementById('expandBtn');
        const copyBtn = document.getElementById('copyBtn');
        let lastSentText = '', lastSentTimestamp = 0, manuallyExpanded = false, lastKnownContent = '', lastKnownTimestamp = 0;

        function adjustHeight() {
            if (!manuallyExpanded) {
                syncText.style.height = 'auto';
                syncText.style.height = Math.min(syncText.scrollHeight, 150) + 'px';
            }
        }

        function updateStatus(status) {
            if (status === 'sent') { statusIndicator.innerHTML = '✓'; statusIndicator.style.color = '#667eea'; }
            else if (status === 'delivered') { statusIndicator.innerHTML = '✓✓'; statusIndicator.style.color = '#48bb78'; }
            else { statusIndicator.innerHTML = ''; }
        }

        syncText.addEventListener('input', adjustHeight);
        syncText.addEventListener('keydown', async function(e) {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); await sendSyncText(); }
        });

        sendBtn.addEventListener('click', async function(e) {
            e.preventDefault();
            await sendSyncText();
            if (manuallyExpanded) toggleExpand();
        });

        async function sendSyncText() {
            const text = syncText.value.trim();
            if (text === '') return;
            lastSentText = text;
            lastSentTimestamp = Date.now();
            updateStatus('sent');
            try {
                const payload = JSON.stringify({ text: text, timestamp: lastSentTimestamp });
                const blob = new Blob([payload], { type: 'application/json' });
                const formData = new FormData();
                formData.append('file', blob, '.clipboard-sync.txt');
                await fetch('/upload', { method: 'POST', body: formData });
                setTimeout(function() { if (lastKnownTimestamp >= lastSentTimestamp) updateStatus('delivered'); }, 1500);
            } catch (error) { console.error('Sync error:', error); updateStatus(''); }
        }

        expandBtn.addEventListener('click', function(e) {
            e.preventDefault();
            toggleExpand();
        });

        function toggleExpand() {
            manuallyExpanded = !manuallyExpanded;
            if (manuallyExpanded) { syncText.style.height = '150px'; expandBtn.classList.add('expanded'); }
            else { syncText.style.height = '45px'; expandBtn.classList.remove('expanded'); }
        }

        copyBtn.addEventListener('click', function(e) {
            e.preventDefault();
            copyLastText();
        });

        function copyLastText() {
            const textToCopy = syncText.value.trim() || lastSentText;
            if (!textToCopy) return;
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(textToCopy).then(showCopySuccess).catch(function() { fallbackCopy(textToCopy); });
            } else {
                fallbackCopy(textToCopy);
            }
        }

        function fallbackCopy(text) {
            const textarea = document.createElement('textarea');
            textarea.value = text;
            textarea.style.cssText = 'position:fixed;left:-9999px;top:-9999px;';
            document.body.appendChild(textarea);
            textarea.focus();
            textarea.select();
            try { document.execCommand('copy') ? showCopySuccess() : showCopyError(); }
            catch (e) { showCopyError(); }
            document.body.removeChild(textarea);
        }

        function showCopySuccess() { copyBtn.classList.add('success'); setTimeout(function() { copyBtn.classList.remove('success'); }, 1500); }
        function showCopyError() { copyBtn.style.background = 'linear-gradient(135deg, #f56565 0%, #e53e3e 100%)'; setTimeout(function() { copyBtn.style.background = ''; }, 1500); }

        async function pollSyncText() {
            try {
                const response = await fetch('/download/.clipboard-sync.txt');
                if (response.ok) {
                    const jsonText = await response.text();
                    try {
                        const data = JSON.parse(jsonText);
                        const text = data.text || jsonText;
                        const timestamp = data.timestamp || Date.now();
                        if (text !== lastKnownContent) {
                            lastKnownContent = text;
                            lastKnownTimestamp = timestamp;
                            syncText.value = text;
                            lastSentText = text;
                            adjustHeight();
                            if (lastSentTimestamp > 0 && timestamp === lastSentTimestamp) { updateStatus('delivered'); }
                            else if (timestamp !== lastSentTimestamp) { updateStatus(''); }
                        }
                    } catch (e) {
                        if (jsonText !== lastKnownContent) { lastKnownContent = jsonText; syncText.value = jsonText; adjustHeight(); }
                    }
                }
            } catch (error) { /* ignore */ }
        }
        setInterval(pollSyncText, 1000);
        pollSyncText();

        // Live file synchronization
        let lastFileCount = ${files.size};
        let lastFileNames = ${if (files.isEmpty()) "[]" else files.map { "\"${escapeJs(it.name)}\"" }.joinToString(",", "[", "]")};
        
        async function pollFileChanges() {
            if (currentMode !== 'shared') return;
            
            try {
                const response = await fetch('/api/files');
                if (response.ok) {
                    const files = await response.json();
                    const currentNames = files.map(function(f) { return f.name; }).sort();
                    const previousNames = lastFileNames.slice().sort();
                    
                    if (files.length !== lastFileCount || JSON.stringify(currentNames) !== JSON.stringify(previousNames)) {
                        console.log('[sync] File list changed, reloading...');
                        location.reload();
                    }
                }
            } catch (error) { /* ignore */ }
        }
        
        setInterval(pollFileChanges, 2000);
    </script>
</body>
</html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    }

    private fun escapeJs(text: String): String {
        return text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }
}
