from fastapi import FastAPI, File, UploadFile, Request, HTTPException
from fastapi.responses import HTMLResponse, FileResponse, StreamingResponse
from fastapi.templating import Jinja2Templates
from fastapi.staticfiles import StaticFiles
from pathlib import Path
from dataclasses import dataclass
from contextlib import suppress
import sys, os, io, zipfile, shutil
from typing import List, Optional
import threading, time, socket

try:
    from zeroconf import Zeroconf, ServiceInfo, ServiceBrowser
except ImportError:  # pragma: no cover - optional dependency
    Zeroconf = ServiceInfo = ServiceBrowser = None


def wait_for_port(host: str, port: int, timeout: float = 5.0) -> bool:
    """Wait until TCP port is open (server is up)."""
    end = time.time() + timeout
    while time.time() < end:
        with socket.socket() as s:
            s.settimeout(0.3)
            try:
                s.connect((host, port))
                return True
            except OSError:
                time.sleep(0.15)
    return False

# ---------- Paths that work in dev AND PyInstaller ----------
def resource_path(*parts: str) -> Path:
    """
    Return absolute path to bundled resources.
    - In dev: relative to this file.
    - In PyInstaller onefile: inside the temporary _MEIPASS dir.
    """
    if hasattr(sys, "_MEIPASS"):             # running from bundled EXE
        base = Path(sys._MEIPASS)            # type: ignore[attr-defined]
    else:
        base = Path(__file__).resolve().parent
    return base.joinpath(*parts)

# Where templates/static live:
# Your project layout is app/main.py + app/templates + app/static
if hasattr(sys, "_MEIPASS"):
    TEMPLATES_DIR = resource_path("app", "templates")
    STATIC_DIR    = resource_path("app", "static")
else:
    TEMPLATES_DIR = resource_path("templates")
    STATIC_DIR    = resource_path("static")

# Persistent shared folder (user profile), so the EXE has a writable place
SHARED_DIR = Path(os.environ.get("WLAN_SHARE_DIR", Path.home() / "WLAN-Share"))
SHARED_DIR.mkdir(parents=True, exist_ok=True)

MDNS_SERVICE_TYPE = "_123tome._tcp.local."
MDNS_INSTANCE_BASE = "WLAN Share"
MDNS_DISCOVERY_TIMEOUT = 10.0


@dataclass
class MdnsService:
    name: str
    host: str
    port: int


@dataclass
class MdnsRegistration:
    zeroconf: "Zeroconf"
    info: "ServiceInfo"

    def close(self) -> None:
        with suppress(Exception):
            self.zeroconf.unregister_service(self.info)
        with suppress(Exception):
            self.zeroconf.close()


def get_local_ip(fallback: str = "127.0.0.1") -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return fallback


def discover_existing_service(timeout: float = MDNS_DISCOVERY_TIMEOUT) -> Optional[MdnsService]:
    if Zeroconf is None:
        print("[mdns] zeroconf library not available, skipping mDNS discovery")
        return None

    print(f"[mdns] Starting service discovery for {MDNS_SERVICE_TYPE} (timeout: {timeout}s)")
    
    try:
        zeroconf = Zeroconf()
    except Exception as e:
        print(f"[mdns] Failed to initialize Zeroconf: {e}")
        return None
        
    found: Optional[MdnsService] = None
    event = threading.Event()

    class Listener:  # type: ignore[override]
        def add_service(self, zc, service_type, name):
            nonlocal found
            print(f"[mdns] Service found: {name} (type: {service_type})")
            info = zc.get_service_info(service_type, name)
            if info:
                addresses = info.parsed_addresses()
                print(f"[mdns] Service info: addresses={addresses}, port={info.port}")
                if addresses:
                    found = MdnsService(name=name, host=addresses[0], port=info.port)
                    event.set()
            else:
                print(f"[mdns] Could not get service info for {name}")

        def update_service(self, zc, service_type, name):
            print(f"[mdns] Service updated: {name}")

        def remove_service(self, zc, service_type, name):
            print(f"[mdns] Service removed: {name}")

    listener = Listener()
    try:
        browser = ServiceBrowser(zeroconf, MDNS_SERVICE_TYPE, listener)
        event.wait(timeout)
        with suppress(Exception):
            browser.cancel()
    except Exception as e:
        print(f"[mdns] Discovery error: {e}")
    finally:
        zeroconf.close()
    
    if found:
        print(f"[mdns] Discovery successful: {found.host}:{found.port}")
    else:
        print("[mdns] No service found within timeout")
    return found


def try_direct_connection(host: str, port: int, timeout: float = 2.0) -> bool:
    """Try to connect directly to a potential server."""
    try:
        import urllib.request
        url = f"http://{host}:{port}/status"
        req = urllib.request.Request(url, method='GET')
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status == 200
    except Exception:
        return False


def scan_network_for_server(port: int = 8000, timeout: float = 1.0) -> Optional[MdnsService]:
    """Scan common local network IPs for the server as fallback."""
    print("[scan] Scanning local network for WLAN Share server...")
    local_ip = get_local_ip()
    if local_ip == "127.0.0.1":
        return None
    
    # Get network prefix (e.g., 192.168.1.)
    parts = local_ip.split('.')
    if len(parts) != 4:
        return None
    prefix = '.'.join(parts[:3]) + '.'
    
    # Common device IPs to check (skip our own IP)
    own_last_octet = int(parts[3])
    candidates = [1, 2, 100, 101, 102, 103, 104, 105, 50, 51, 52, 53, 54, 55, 
                  60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70]
    
    for last_octet in candidates:
        if last_octet == own_last_octet:
            continue
        host = f"{prefix}{last_octet}"
        if try_direct_connection(host, port, timeout):
            print(f"[scan] Found server at {host}:{port}")
            return MdnsService(name="WLAN Share (scanned)", host=host, port=port)
    
    print("[scan] No server found via network scan")
    return None


def register_mdns_service(port: int, local_ip: str) -> Optional[MdnsRegistration]:
    if Zeroconf is None:
        print("[mdns] zeroconf library not installed; skipping advertisement")
        return None
    try:
        zeroconf = Zeroconf()
        address_bytes = socket.inet_aton(local_ip)
        hostname = socket.gethostname()
        # Ensure server ends with .local. so some stacks display a nice name
        server_fqdn = f"{hostname}.local."
        info = ServiceInfo(
            MDNS_SERVICE_TYPE,
            f"123toMe-{hostname}.{MDNS_SERVICE_TYPE}",  # instance name
            addresses=[address_bytes],
            port=port,
            properties={"path": "/"},
            server=server_fqdn,
        )
        zeroconf.register_service(info)
        print(f"[mdns] Registered service {info.name} at {local_ip}:{port}")
        return MdnsRegistration(zeroconf=zeroconf, info=info)
    except Exception as exc:
        print(f"[mdns] Failed to register service: {exc}")
        return None

# ---------- FastAPI ----------
app = FastAPI(title="WLAN Share", description="Simple file sharing over WLAN")

# Mount static files / templates using resolved paths
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))

def get_file_list():
    files = []
    if SHARED_DIR.exists():
        for file_path in SHARED_DIR.iterdir():
            if file_path.is_file() and not file_path.name.startswith('.'):
                stat = file_path.stat()
                files.append({
                    "name": file_path.name,
                    "size": stat.st_size,
                    "size_mb": round(stat.st_size / (1024 * 1024), 2)
                })
    return sorted(files, key=lambda x: x["name"])

@app.get("/", response_class=HTMLResponse)
async def index(request: Request):
    files = get_file_list()
    return templates.TemplateResponse("index.html", {"request": request, "files": files})

@app.get("/status")
async def status():
    """Health check endpoint for service discovery."""
    return {"status": "ok", "service": "WLAN Share"}

@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    if not file.filename:
        raise HTTPException(status_code=400, detail="No file selected")
    filename = file.filename.replace("/", "_").replace("\\", "_")
    file_path = SHARED_DIR / filename
    try:
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        return {"message": f"File '{filename}' uploaded successfully", "filename": filename}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error uploading file: {str(e)}")

@app.get("/download/{filename}")
async def download_file(filename: str):
    file_path = SHARED_DIR / filename
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(path=file_path, filename=filename, media_type='application/octet-stream')

@app.get("/zip")
async def download_all_as_zip():
    files = [p for p in SHARED_DIR.glob("*") if p.is_file()]
    if not files:
        raise HTTPException(status_code=404, detail="No files to download")
    zip_buffer = io.BytesIO()
    with zipfile.ZipFile(zip_buffer, 'w', zipfile.ZIP_DEFLATED) as zip_file:
        for file_path in files:
            zip_file.write(file_path, file_path.name)
    zip_buffer.seek(0)
    return StreamingResponse(
        io.BytesIO(zip_buffer.read()),
        media_type="application/zip",
        headers={"Content-Disposition": "attachment; filename=shared_files.zip"}
    )

@app.delete("/delete/{filename}")
async def delete_file(filename: str):
    file_path = SHARED_DIR / filename
    if not file_path.exists():
        raise HTTPException(status_code=404, detail="File not found")
    try:
        file_path.unlink()
        return {"message": f"File '{filename}' deleted successfully"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Error deleting file: {str(e)}")

# ---------- Clipboard monitoring for Windows ----------
def monitor_clipboard():
    """Monitor Windows clipboard and sync to file."""
    try:
        import win32clipboard
        import win32con
        import json
        
        print("[clipboard] Starting clipboard monitor...")
        last_clipboard = ""
        clipboard_file = SHARED_DIR / ".clipboard-sync.txt"
        
        # Wait a bit for the server to start
        time.sleep(2)
        
        while True:
            try:
                time.sleep(0.3)  # Check every 300ms
                
                # Try to open clipboard
                try:
                    win32clipboard.OpenClipboard()
                    clipboard_opened = True
                except:
                    clipboard_opened = False
                    time.sleep(0.2)
                    continue
                
                try:
                    if win32clipboard.IsClipboardFormatAvailable(win32con.CF_UNICODETEXT):
                        clipboard_text = win32clipboard.GetClipboardData(win32con.CF_UNICODETEXT)
                        
                        # Only update if clipboard changed and has content
                        if clipboard_text and clipboard_text.strip() and clipboard_text != last_clipboard:
                            last_clipboard = clipboard_text
                            
                            # Write to sync file with timestamp
                            try:
                                payload = json.dumps({
                                    "text": clipboard_text,
                                    "timestamp": int(time.time() * 1000)
                                })
                                with open(clipboard_file, 'w', encoding='utf-8') as f:
                                    f.write(payload)
                                print(f"[clipboard] Synced: {clipboard_text[:50]}...")
                            except Exception as e:
                                print(f"[clipboard] Error writing to file: {e}")
                finally:
                    if clipboard_opened:
                        try:
                            win32clipboard.CloseClipboard()
                        except:
                            pass
                    
            except Exception as e:
                # Clipboard might be locked by another process, just continue
                time.sleep(0.2)
                
    except ImportError as e:
        print(f"[clipboard] pywin32 not available ({e}), clipboard sync disabled")
        print("[clipboard] Install with: pip install pywin32")
    except Exception as e:
        print(f"[clipboard] Monitor error: {e}")

# ---------- Dev entrypoint (uvicorn) ----------
if __name__ == "__main__":
    import uvicorn
    import webbrowser
    import atexit

    HOST, PORT = "0.0.0.0", 8000

    print("=" * 50)
    print("WLAN Share - Starting...")
    print("=" * 50)
    
    # Scan network for existing server
    discovered = scan_network_for_server(PORT)
    
    is_hosting = discovered is None
    target_host = "127.0.0.1" if is_hosting else discovered.host
    target_port = PORT if is_hosting else discovered.port
    target_url = f"http://{target_host}:{target_port}/"
    network_url: Optional[str] = None
    mdns_registration: Optional[MdnsRegistration] = None

    server_thread: Optional[threading.Thread] = None

    if is_hosting:
        print("[discovery] No existing WLAN Share service found. Hosting locally.")

        clipboard_thread = threading.Thread(target=monitor_clipboard, daemon=True)
        clipboard_thread.start()

        def run_server():
            uvicorn.run(app, host=HOST, port=PORT, log_level="info")

        server_thread = threading.Thread(target=run_server, daemon=True)
        server_thread.start()

        ready = wait_for_port("127.0.0.1", PORT, timeout=8.0)
        if not ready:
            time.sleep(0.8)
        local_ip = get_local_ip()
        network_url = f"http://{local_ip}:{PORT}/"
        mdns_registration = register_mdns_service(PORT, local_ip)
        if mdns_registration:
            atexit.register(mdns_registration.close)
    else:
        print(f"[mdns] Found existing WLAN Share server at {target_url} ({discovered.name})")
        ready = wait_for_port(discovered.host, discovered.port, timeout=8.0)
        if not ready:
            time.sleep(0.8)
        network_url = target_url

    # Try to open a native GUI window using PyQt5
    try:
        from PyQt5.QtWidgets import QApplication, QMainWindow, QMessageBox
        from PyQt5.QtWebEngineWidgets import QWebEngineView
        from PyQt5.QtCore import QUrl
        import sys as qt_sys
        
        class WLANShareWindow(QMainWindow):
            def __init__(self):
                super().__init__()
                self.setWindowTitle("WLAN Share")
                self.setGeometry(100, 100, 1000, 700)
                
                self.browser = QWebEngineView()
                self.browser.setUrl(QUrl(target_url))
                self.setCentralWidget(self.browser)
            
            def closeEvent(self, event):
                reply = QMessageBox.question(
                    self, 'Beenden',
                    'Möchten Sie WLAN Share wirklich beenden?',
                    QMessageBox.Yes | QMessageBox.No,
                    QMessageBox.No
                )
                if reply == QMessageBox.Yes:
                    event.accept()
                else:
                    event.ignore()
        
        app_gui = QApplication(qt_sys.argv)
        window = WLANShareWindow()
        window.show()
        qt_sys.exit(app_gui.exec_())
        
    except ImportError as e:
        print(f"[info] PyQt5 not available ({e}). Trying alternative...")
        # Fallback: Try Tkinter with embedded browser
        try:
            import tkinter as tk
            from tkinter import messagebox
            import webbrowser
            
            class WLANShareApp:
                def __init__(self, root):
                    self.root = root
                    self.root.title("WLAN Share")
                    self.root.geometry("1000x700")
                    
                    info_frame = tk.Frame(root, bg="#2c3e50", pady=20)
                    info_frame.pack(fill=tk.X)
                    
                    title = tk.Label(
                        info_frame,
                        text="📁 WLAN Share",
                        font=("Arial", 24, "bold"),
                        fg="white",
                        bg="#2c3e50"
                    )
                    title.pack()
                    
                    subtitle_text = "Dateien einfach über das lokale Netzwerk teilen"
                    tk.Label(
                        info_frame,
                        text=subtitle_text,
                        font=("Arial", 12),
                        fg="#ecf0f1",
                        bg="#2c3e50"
                    ).pack()
                    
                    url_frame = tk.Frame(root, pady=10)
                    url_frame.pack(fill=tk.X, padx=20)
                    
                    tk.Label(
                        url_frame,
                        text="Verbunden mit:",
                        font=("Arial", 10)
                    ).pack()
                    
                    url_text = tk.Entry(
                        url_frame,
                        font=("Courier", 12, "bold"),
                        justify="center",
                        fg="#3498db"
                    )
                    url_text.insert(0, target_url)
                    url_text.config(state="readonly")
                    url_text.pack(fill=tk.X, pady=5)
                    
                    if network_url and network_url != target_url:
                        tk.Label(
                            url_frame,
                            text="Von anderen Geräten erreichbar unter:",
                            font=("Arial", 10)
                        ).pack(pady=(10, 0))
                        
                        network_text = tk.Entry(
                            url_frame,
                            font=("Courier", 12, "bold"),
                            justify="center",
                            fg="#27ae60"
                        )
                        network_text.insert(0, network_url)
                        network_text.config(state="readonly")
                        network_text.pack(fill=tk.X, pady=5)
                    
                    button_frame = tk.Frame(root, pady=20)
                    button_frame.pack()
                    
                    open_btn = tk.Button(
                        button_frame,
                        text="🌐 Im Browser öffnen",
                        font=("Arial", 14, "bold"),
                        bg="#3498db",
                        fg="white",
                        padx=30,
                        pady=15,
                        command=self.open_browser,
                        cursor="hand2"
                    )
                    open_btn.pack(pady=10)
                    
                    if is_hosting:
                        folder_btn = tk.Button(
                            button_frame,
                            text="📂 Shared-Ordner öffnen",
                            font=("Arial", 12),
                            bg="#27ae60",
                            fg="white",
                            padx=20,
                            pady=10,
                            command=self.open_shared_folder,
                            cursor="hand2"
                        )
                        folder_btn.pack(pady=5)
                    
                    info_text = tk.Text(
                        root,
                        height=8,
                        wrap=tk.WORD,
                        font=("Arial", 10),
                        bg="#ecf0f1",
                        relief=tk.FLAT,
                        padx=20,
                        pady=10
                    )
                    info_text.pack(fill=tk.BOTH, expand=True, padx=20, pady=10)
                    
                    if is_hosting:
                        info_content = f"""ℹ️ Informationen:

• Der Server läuft auf Port {PORT}
• Freigegebene Dateien befinden sich in: {SHARED_DIR}
• Andere Geräte im Netzwerk finden den Dienst via {MDNS_SERVICE_TYPE}
• Klicken Sie auf "Im Browser öffnen" um die Web-Oberfläche zu starten
• Das Fenster kann minimiert werden - der Server läuft weiter im Hintergrund"""
                    else:
                        info_content = f"""ℹ️ Informationen:

• Bestehender WLAN Share Server gefunden unter: {target_url}
• Dieses Gerät hostet keinen eigenen Server
• Der Dienst wurde via mDNS ({MDNS_SERVICE_TYPE.strip('.')}) entdeckt
• Sie können das Fenster schließen, um lediglich den Browser zu nutzen"""
                    
                    info_text.insert("1.0", info_content)
                    info_text.config(state="disabled")
                    
                    self.root.protocol("WM_DELETE_WINDOW", self.on_closing)
                
                def open_browser(self):
                    webbrowser.open(target_url)
                
                def open_shared_folder(self):
                    import subprocess
                    try:
                        subprocess.Popen(f'explorer "{SHARED_DIR}"')
                    except Exception:
                        pass
                
                def on_closing(self):
                    if messagebox.askokcancel("Beenden", "WLAN Share beenden?"):
                        self.root.destroy()
            
            root = tk.Tk()
            app_tk = WLANShareApp(root)
            root.mainloop()
            
        except Exception as e:
            print(f"[error] GUI initialization failed ({e}). Opening browser instead.")
            if ready:
                webbrowser.open(target_url)
            else:
                print("Server nicht erreichbar. Manuell öffnen:", target_url)
            if server_thread:
                server_thread.join()

