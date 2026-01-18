package com.example.a123tome

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var nsdManager: NsdManager
    private val handler = Handler(Looper.getMainLooper())
    
    // File upload support
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val CAMERA_REQUEST_CODE = 1002
    private val GALLERY_REQUEST_CODE = 1003

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var hasResolved = false

    private val serviceType = "_123tome._tcp."
    private val discoveryTimeoutMs = 15_000L
    private var isResolving = false

    private var localServer: LocalHttpServer? = null
    private var localPort: Int = 8000
    private var localIpAddress: String = "127.0.0.1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        configureWebView(webView.settings)
        webView.webViewClient = WebViewClient()
        
        // Enable file upload support
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Cancel any existing callback
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                
                showUploadOptionsDialog()
                return true
            }
        }
        
        // Enable file download support
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                
                // Extract filename properly from Content-Disposition or URL
                var filename = extractFilename(url, contentDisposition, mimeType)
                Log.i(TAG, "Download: url=$url, contentDisposition=$contentDisposition, mimeType=$mimeType, filename=$filename")
                
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.setDescription("Downloading file...")
                request.setTitle(filename)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                
                Toast.makeText(this@MainActivity, "Download gestartet: $filename", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Download started: $filename from $url")
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                Toast.makeText(this@MainActivity, "Download fehlgeschlagen: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        
        setContentView(webView)

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        startServiceDiscovery()
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            FILE_CHOOSER_REQUEST_CODE, GALLERY_REQUEST_CODE -> {
                if (fileUploadCallback == null) return
                
                val results: Array<Uri>? = when {
                    resultCode != RESULT_OK || data == null -> null
                    data.clipData != null -> {
                        // Multiple files selected
                        val count = data.clipData!!.itemCount
                        Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    data.data != null -> {
                        // Single file selected
                        arrayOf(data.data!!)
                    }
                    else -> null
                }
                
                fileUploadCallback?.onReceiveValue(results)
                fileUploadCallback = null
            }
            CAMERA_REQUEST_CODE -> {
                if (fileUploadCallback == null) return
                
                val results: Array<Uri>? = if (resultCode == RESULT_OK && cameraImageUri != null) {
                    arrayOf(cameraImageUri!!)
                } else {
                    null
                }
                
                fileUploadCallback?.onReceiveValue(results)
                fileUploadCallback = null
                cameraImageUri = null
            }
        }
    }
    
    private fun showUploadOptionsDialog() {
        val options = arrayOf("📁 Aus Dateien", "🖼️ Aus Galerie", "📷 Foto aufnehmen")
        
        AlertDialog.Builder(this)
            .setTitle("Upload-Quelle wählen")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFilePicker()
                    1 -> openGallery()
                    2 -> openCamera()
                }
            }
            .setNegativeButton("Abbrechen") { dialog, _ ->
                dialog.dismiss()
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
            }
            .setOnCancelListener {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = null
            }
            .show()
    }
    
    private fun openFilePicker() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(Intent.createChooser(intent, "Dateien auswählen"), FILE_CHOOSER_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file picker", e)
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
        }
    }
    
    private fun openGallery() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            startActivityForResult(Intent.createChooser(intent, "Fotos auswählen"), GALLERY_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening gallery", e)
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
        }
    }
    
    private fun openCamera() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val imageFileName = "IMG_${timeStamp}.jpg"
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val imageFile = File(storageDir, imageFileName)
            
            cameraImageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", imageFile)
            } else {
                Uri.fromFile(imageFile)
            }
            
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            startActivityForResult(intent, CAMERA_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            Toast.makeText(this, "Kamera konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
        }
    }
    
    private fun extractFilename(url: String, contentDisposition: String?, mimeType: String?): String {
        var filename: String? = null
        
        // Try to extract from Content-Disposition header
        if (!contentDisposition.isNullOrEmpty()) {
            // Look for filename="..." or filename*=UTF-8''...
            val patterns = listOf(
                """filename\*=(?:UTF-8''|utf-8'')([^;"\s]+)""".toRegex(RegexOption.IGNORE_CASE),
                """filename="([^"]+)"""".toRegex(),
                """filename=([^;\s]+)""".toRegex()
            )
            for (pattern in patterns) {
                val match = pattern.find(contentDisposition)
                if (match != null) {
                    filename = try {
                        java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                    } catch (e: Exception) {
                        match.groupValues[1]
                    }
                    break
                }
            }
        }
        
        // Try to extract from URL path
        if (filename.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(url)
                val path = uri.lastPathSegment
                if (!path.isNullOrEmpty() && path != "download") {
                    filename = java.net.URLDecoder.decode(path, "UTF-8")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing URL for filename", e)
            }
        }
        
        // Fallback with timestamp
        if (filename.isNullOrEmpty()) {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
            filename = "download_${System.currentTimeMillis()}.$extension"
        }
        
        // Ensure the filename has an extension based on mime type
        if (!filename.contains('.') && !mimeType.isNullOrEmpty()) {
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            if (!extension.isNullOrEmpty()) {
                filename = "$filename.$extension"
            }
        }
        
        return filename
    }

    private fun configureWebView(webSettings: WebSettings) {
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
    }

    private fun startServiceDiscovery() {
        // Do not stop discovery up-front; only stop once resolved or we start hosting
        acquireMulticastLock()
        hasResolved = false

        resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Resolve failed: $errorCode")
                isResolving = false
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (hasResolved) return
                hasResolved = true
                isResolving = false
                handler.removeCallbacks(timeoutRunnable)
                Log.i(TAG, "Resolved service ${serviceInfo.serviceName} @ ${serviceInfo.host.hostAddress}:${serviceInfo.port}")
                stopDiscovery()
                loadUrl("http://${serviceInfo.host.hostAddress}:${serviceInfo.port}/")
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {
                Log.i(TAG, "mDNS discovery started for $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val rawType = serviceInfo.serviceType ?: return
                val name = serviceInfo.serviceName

                Log.i(TAG, "FOUND: name=$name, rawType=$rawType")

                // Normalize all possible variants
                val typeNorm = rawType
                    .replace(".local.", "")
                    .replace(".local", "")
                    .trimEnd('.')

                if (typeNorm != "_123tome._tcp") {
                    Log.i(TAG, "IGNORED: typeNorm=$typeNorm")
                    return
                }

                Log.i(TAG, "MATCHED service, resolving: $name")

                if (!isResolving) {
                    isResolving = true
                    resolveListener?.let {
                        nsdManager.resolveService(serviceInfo, it)
                    }
                }
            }




            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.w(TAG, "Service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i(TAG, "Discovery stopped: $serviceType")
                if (!hasResolved) {
                    Log.w(TAG, "Discovery stopped without resolution, starting local server")
                    startLocalServer()
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                startLocalServer()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
                stopDiscovery()
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            handler.postDelayed(timeoutRunnable, discoveryTimeoutMs)
        } catch (e: Exception) {
            Log.e(TAG, "Discovery start failed", e)
            startLocalServer()
        }
    }

    private val timeoutRunnable = Runnable {
        if (!hasResolved) {
            Log.w(TAG, "Discovery timeout reached, starting local server")
            startLocalServer()
        }
    }

    private fun startLocalServer() {
        if (hasResolved) return
        hasResolved = true
        handler.removeCallbacks(timeoutRunnable)
        stopDiscoveryOnly() // Stop discovery but keep multicast lock for NSD advertisement
        
        // Ensure multicast lock is held for NSD to work
        acquireMulticastLock()
        
        // Get the device's WiFi IP address
        localIpAddress = getWifiIpAddress() ?: "127.0.0.1"
        Log.i(TAG, "Device IP address: $localIpAddress")

        // Start NanoHTTPD on all interfaces (0.0.0.0)
        val started = tryStartServer()

        if (started) {
            val networkUrl = "http://$localIpAddress:$localPort/"
            Toast.makeText(this, "Server gestartet: $networkUrl", Toast.LENGTH_LONG).show()
            Log.i(TAG, "Server accessible at: $networkUrl")
            
            // Advertise via NSD so others can discover this phone
            registerLocalService()
            // Load using localhost for the local webview
            loadUrl("http://127.0.0.1:$localPort/")
        } else {
            Toast.makeText(this, "Server konnte nicht gestartet werden", Toast.LENGTH_LONG).show()
            // As a last resort, render an inline info page so the user sees something
            val fallbackHtml = """
                <html><body style="font-family: sans-serif; padding: 24px;">
                    <h2>WLAN Share</h2>
                    <p>Der lokale HTTP-Server konnte nicht gestartet werden.</p>
                    <p>Bitte WLAN prüfen und die App neu starten.</p>
                </body></html>
            """.trimIndent()
            loadUrl("data:text/html;charset=utf-8,$fallbackHtml")
        }
    }
    
    private fun getWifiIpAddress(): String? {
        try {
            // Method 1: Try WifiManager (deprecated but works)
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ip != "0.0.0.0") return ip
            }
            
            // Method 2: Enumerate network interfaces
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    networkInterface.inetAddresses.toList().forEach { address ->
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address", e)
        }
        return null
    }

    private fun tryStartServer(): Boolean {
        if (localServer != null) return true
        var port = localPort
        var lastError: Exception? = null
        repeat(5) {
            try {
                // Pass context and bind to all interfaces (0.0.0.0) for network access
                val server = LocalHttpServer(this, port, "0.0.0.0")
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                localServer = server
                localPort = port
                Log.i(TAG, "Local HTTP server started on 0.0.0.0:$port (accessible via $localIpAddress:$port)")
                return true
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Port $port busy, trying next...", e)
                port += 1
            }
        }
        if (lastError != null) {
            Log.e(TAG, "Failed to start local server", lastError)
        }
        return false
    }

    private fun registerLocalService() {
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "WLAN Share (${Build.MODEL})"
                serviceType = serviceType // Android NSD expects _service._tcp. (no .local)
                port = localPort
            }
            if (registrationListener == null) {
                registrationListener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                        Log.i(TAG, "NSD service registered: ${NsdServiceInfo.serviceName} @ ${NsdServiceInfo.port}")
                    }
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "NSD registration failed: $errorCode")
                    }
                    override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                        Log.i(TAG, "NSD service unregistered")
                    }
                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "NSD unregistration failed: $errorCode")
                    }
                }
            }
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NSD service", e)
        }
    }

    private fun loadUrl(url: String) {
        runOnUiThread {
            webView.loadUrl(url)
        }
    }

    private fun stopDiscoveryOnly() {
        handler.removeCallbacks(timeoutRunnable)
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (ignored: IllegalArgumentException) {
                Log.w(TAG, "stopServiceDiscovery called without matching start")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
        resolveListener = null
        // Keep multicast lock for NSD service advertisement
    }

    private fun stopDiscovery() {
        stopDiscoveryOnly()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("wlan-share-mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        multicastLock = null
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopDiscovery()
        try {
            registrationListener?.let {
                nsdManager.unregisterService(it)
            }
        } catch (_: Exception) {
        }
        localServer?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
