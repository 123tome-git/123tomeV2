from fastapi import FastAPI, File, UploadFile, Request, HTTPException
from fastapi.responses import HTMLResponse, FileResponse, StreamingResponse
from fastapi.templating import Jinja2Templates
from fastapi.staticfiles import StaticFiles
from pathlib import Path
import sys, os, io, zipfile, shutil
from typing import List
import threading, time, socket


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

# ---------- FastAPI ----------
app = FastAPI(title="WLAN Share", description="Simple file sharing over WLAN")

# Mount static files / templates using resolved paths
app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
templates = Jinja2Templates(directory=str(TEMPLATES_DIR))

def get_file_list():
    files = []
    if SHARED_DIR.exists():
        for file_path in SHARED_DIR.iterdir():
            if file_path.is_file():
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

# ---------- Dev entrypoint (uvicorn) ----------
if __name__ == "__main__":
    import uvicorn
    import webbrowser

    HOST, PORT = "0.0.0.0", 8000
    URL = f"http://localhost:{PORT}/"

    # Start FastAPI in a background thread
    def run_server():
        uvicorn.run(app, host=HOST, port=PORT, log_level="info")

    t = threading.Thread(target=run_server, daemon=True)
    t.start()

    # Wait until the server is reachable
    ready = wait_for_port("127.0.0.1", PORT, timeout=8.0)

    # Try to open a native GUI window using PyQt5
    try:
        from PyQt5.QtWidgets import QApplication, QMainWindow, QMessageBox
        from PyQt5.QtWebEngineWidgets import QWebEngineView
        from PyQt5.QtCore import QUrl
        import sys as qt_sys
        
        if not ready:
            time.sleep(0.8)  # tiny grace period
        
        class WLANShareWindow(QMainWindow):
            def __init__(self):
                super().__init__()
                self.setWindowTitle("WLAN Share")
                self.setGeometry(100, 100, 1000, 700)
                
                # Create web view
                self.browser = QWebEngineView()
                self.browser.setUrl(QUrl(URL))
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
            
            if not ready:
                time.sleep(0.5)
            
            class WLANShareApp:
                def __init__(self, root):
                    self.root = root
                    self.root.title("WLAN Share")
                    self.root.geometry("1000x700")
                    
                    # Info frame
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
                    
                    subtitle = tk.Label(
                        info_frame,
                        text="Dateien einfach über das lokale Netzwerk teilen",
                        font=("Arial", 12),
                        fg="#ecf0f1",
                        bg="#2c3e50"
                    )
                    subtitle.pack()
                    
                    # URL display
                    url_frame = tk.Frame(root, pady=10)
                    url_frame.pack(fill=tk.X, padx=20)
                    
                    tk.Label(
                        url_frame,
                        text="Server läuft auf:",
                        font=("Arial", 10)
                    ).pack()
                    
                    url_text = tk.Entry(
                        url_frame,
                        font=("Courier", 12, "bold"),
                        justify="center",
                        fg="#3498db"
                    )
                    url_text.insert(0, URL)
                    url_text.config(state="readonly")
                    url_text.pack(fill=tk.X, pady=5)
                    
                    # Get local IP
                    try:
                        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        s.connect(("8.8.8.8", 80))
                        local_ip = s.getsockname()[0]
                        s.close()
                        network_url = f"http://{local_ip}:{PORT}/"
                        
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
                    except:
                        pass
                    
                    # Buttons
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
                    
                    # Shared folder button
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
                    
                    # Info text
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
                    
                    info_content = f"""ℹ️ Informationen:

• Der Server läuft auf Port {PORT}
• Freigegebene Dateien befinden sich in: {SHARED_DIR}
• Andere Geräte im Netzwerk können auf den Server zugreifen
• Klicken Sie auf "Im Browser öffnen" um die Web-Oberfläche zu starten
• Das Fenster kann minimiert werden - der Server läuft weiter im Hintergrund"""
                    
                    info_text.insert("1.0", info_content)
                    info_text.config(state="disabled")
                    
                    self.root.protocol("WM_DELETE_WINDOW", self.on_closing)
                
                def open_browser(self):
                    webbrowser.open(URL)
                
                def open_shared_folder(self):
                    import subprocess
                    try:
                        subprocess.Popen(f'explorer "{SHARED_DIR}"')
                    except:
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
                webbrowser.open(URL)
            else:
                print("Server not ready yet. You can open:", URL)
            # Keep the server alive in console mode:
            t.join()

