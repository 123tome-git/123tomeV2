package com.example.a123tome

import fi.iki.elonen.NanoHTTPD

class LocalHttpServer(port: Int) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val html = """
            <!doctype html>
            <html lang="de">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1"/>
              <title>WLAN Share (Android)</title>
              <style>
                body { font-family: system-ui, Roboto, Arial, sans-serif; padding: 24px; }
                .card { max-width: 720px; margin: 0 auto; background:#f7fafc; border-radius:12px; padding:24px; box-shadow:0 6px 18px rgba(0,0,0,.06); }
                h1 { margin: 0 0 8px; }
                .muted { color:#4a5568; }
                code { background:#edf2f7; padding:2px 6px; border-radius:6px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>📁 WLAN Share (Lokaler Android Server)</h1>
                <p class="muted">Kein vorhandener Server wurde im Netzwerk gefunden. Dieses Gerät hostet nun einen lokalen HTTP‑Server.</p>
                <p class="muted">Andere Geräte im gleichen Netzwerk können diesen Dienst via mDNS <code>_123tome._tcp.local.</code> entdecken.</p>
                <p>Diese Minimal-Seite dient als Platzhalter. Die vollständige Web‑Oberfläche kann vom Desktop‑Server geladen werden, wenn dieser verfügbar ist.</p>
              </div>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}

