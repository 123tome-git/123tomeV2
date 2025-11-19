package com.example.a123tome

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var nsdManager: NsdManager
    private val handler = Handler(Looper.getMainLooper())

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        configureWebView(webView.settings)
        webView.webViewClient = WebViewClient()
        setContentView(webView)

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        startServiceDiscovery()
    }

    private fun configureWebView(webSettings: WebSettings) {
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
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
        stopDiscovery()
        Toast.makeText(this, "Kein WLAN Share Server via mDNS gefunden. Lokaler Server wird gestartet.", Toast.LENGTH_LONG).show()
        // Start NanoHTTPD on localhost
        tryStartServer()
        // Advertise via NSD so others can discover this phone
        registerLocalService()
        loadUrl("http://127.0.0.1:$localPort/")
    }

    private fun tryStartServer() {
        if (localServer != null) return
        var port = localPort
        var lastError: Exception? = null
        repeat(5) {
            try {
                val server = LocalHttpServer(port)
                // NanoHTTPD default SOCKET_READ_TIMEOUT is 5000; we don't need to set explicitly
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                localServer = server
                localPort = port
                Log.i(TAG, "Local HTTP server started on 127.0.0.1:$port")
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Port $port busy, trying next...", e)
                port += 1
            }
        }
        if (lastError != null) {
            Log.e(TAG, "Failed to start local server", lastError)
        }
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

    private fun stopDiscovery() {
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
