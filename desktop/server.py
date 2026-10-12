"""
AirBridge Server - Handles HTTP API endpoints and UDP low-latency audio stream.
"""

import cgi
import io
import json
import os
import re
import socket
import subprocess
import threading
import time
from http.server import HTTPServer, BaseHTTPRequestHandler
from typing import Callable, Optional

from audio_bridge import AudioBridge
from whisper_client import WhisperClient


class AirBridgeRequestHandler(BaseHTTPRequestHandler):
    # Overridden in server instance
    audio_bridge: AudioBridge = None
    whisper_client: WhisperClient = None
    save_directory: str = os.path.expanduser("~/Pictures/AirBridge")
    on_image_callback: Optional[Callable[[str], None]] = None
    on_transcription_callback: Optional[Callable[[str, bool, bool], None]] = None

    def log_message(self, format, *args):
        # Suppress noisy HTTP request logging
        return

    def _send_json(self, status_code: int, data: dict):
        response_bytes = json.dumps(data).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response_bytes)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(response_bytes)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path == "/api/ping":
            data = {
                "status": "ok",
                "server_name": socket.gethostname(),
                "virtual_mic": self.audio_bridge.source_name if self.audio_bridge else "N/A",
                "whisper_available": self.whisper_client.is_available() if self.whisper_client else False
            }
            self._send_json(200, data)
        else:
            self.send_error(404, "Not Found")

    def do_POST(self):
        if self.path == "/api/transcription":
            content_length = int(self.headers.get("Content-Length", 0))
            post_data = self.rfile.read(content_length).decode("utf-8")
            try:
                payload = json.loads(post_data)
                text = payload.get("text", "")
                type_into_window = payload.get("type_into_window", False)
                copy_clipboard = payload.get("copy_clipboard", True)

                if self.on_transcription_callback and text:
                    self.on_transcription_callback(text, type_into_window, copy_clipboard)

                self._send_json(200, {"status": "ok"})
            except Exception as e:
                self._send_json(400, {"status": "error", "message": str(e)})

        elif self.path == "/api/upload":
            self._handle_file_upload()

        elif self.path == "/api/transcribe_audio":
            self._handle_transcribe_audio()

        else:
            self.send_error(404, "Not Found")

    def _handle_file_upload(self):
        try:
            content_type = self.headers.get("Content-Type", "")
            if not content_type.startswith("multipart/form-data"):
                self._send_json(400, {"status": "error", "message": "Expected multipart/form-data"})
                return

            boundary = content_type.split("boundary=")[-1].strip()
            content_length = int(self.headers.get("Content-Length", 0))
            raw_data = self.rfile.read(content_length)

            os.makedirs(self.save_directory, exist_ok=True)

            boundary_bytes = ("--" + boundary).encode()
            parts = raw_data.split(boundary_bytes)

            saved_file_path = None

            for part in parts:
                if b'name="file"' in part:
                    # Extract filename if available
                    match = re.search(rb'filename="([^"]+)"', part)
                    if match:
                        filename = match.group(1).decode("utf-8", errors="ignore")
                    else:
                        filename = f"image_{int(time.time())}.jpg"

                    # Sanitize filename
                    filename = os.path.basename(filename)
                    if not filename:
                        filename = f"image_{int(time.time())}.jpg"

                    # Header/body separator
                    header_end = part.find(b"\r\n\r\n")
                    if header_end != -1:
                        file_data = part[header_end + 4:]
                        # Trim trailing \r\n
                        if file_data.endswith(b"\r\n"):
                            file_data = file_data[:-2]

                        timestamp_str = time.strftime("%Y%m%d_%H%M%S")
                        target_name = f"{timestamp_str}_{filename}"
                        target_path = os.path.join(self.save_directory, target_name)

                        with open(target_path, "wb") as f:
                            f.write(file_data)

                        saved_file_path = target_path
                        print(f"[Server] Saved uploaded image to: {target_path}")
                        break

            if saved_file_path:
                if self.on_image_callback:
                    self.on_image_callback(saved_file_path)
                self._send_json(200, {"status": "ok", "path": saved_file_path})
            else:
                self._send_json(400, {"status": "error", "message": "No file content parsed"})
        except Exception as e:
            print(f"[Server] Upload handling error: {e}")
            self._send_json(500, {"status": "error", "message": str(e)})

    def _handle_transcribe_audio(self):
        try:
            content_type = self.headers.get("Content-Type", "")
            if not content_type.startswith("multipart/form-data"):
                self._send_json(400, {"status": "error", "message": "Expected multipart/form-data"})
                return

            boundary = content_type.split("boundary=")[-1].strip()
            content_length = int(self.headers.get("Content-Length", 0))
            raw_data = self.rfile.read(content_length)

            boundary_bytes = ("--" + boundary).encode()
            parts = raw_data.split(boundary_bytes)

            audio_file_path = None
            prompt = ""

            temp_dir = "/tmp/airbridge_audio"
            os.makedirs(temp_dir, exist_ok=True)

            for part in parts:
                if b'name="prompt"' in part:
                    header_end = part.find(b"\r\n\r\n")
                    if header_end != -1:
                        prompt_bytes = part[header_end + 4:].rstrip(b"\r\n")
                        prompt = prompt_bytes.decode("utf-8", errors="ignore")

                if b'name="file"' in part:
                    header_end = part.find(b"\r\n\r\n")
                    if header_end != -1:
                        file_data = part[header_end + 4:].rstrip(b"\r\n")
                        target_path = os.path.join(temp_dir, f"transcribe_{uuid_hex()}.wav")
                        with open(target_path, "wb") as f:
                            f.write(file_data)
                        audio_file_path = target_path

            if audio_file_path and self.whisper_client:
                transcription = self.whisper_client.transcribe_file(audio_file_path, prompt=prompt)
                try:
                    os.remove(audio_file_path)
                except Exception:
                    pass

                if transcription and self.on_transcription_callback:
                    # Notify desktop UI of transcription
                    self.on_transcription_callback(transcription, False, True)

                self._send_json(200, {"status": "ok", "text": transcription})
            else:
                self._send_json(400, {"status": "error", "message": "Failed to parse audio file"})
        except Exception as e:
            print(f"[Server] Transcription error: {e}")
            self._send_json(500, {"status": "error", "message": str(e)})


def uuid_hex() -> str:
    import uuid
    return uuid.uuid4().hex


class ServerManager:
    def __init__(
        self,
        audio_bridge: AudioBridge,
        whisper_client: WhisperClient,
        http_port: int = 8765,
        udp_audio_port: int = 8766
    ):
        self.audio_bridge = audio_bridge
        self.whisper_client = whisper_client
        self.http_port = http_port
        self.udp_audio_port = udp_audio_port

        self.httpd: Optional[HTTPServer] = None
        self.udp_socket: Optional[socket.socket] = None

        self._running = False
        self._http_thread: Optional[threading.Thread] = None
        self._udp_thread: Optional[threading.Thread] = None

        self.on_image_received: Optional[Callable[[str], None]] = None
        self.on_transcription_received: Optional[Callable[[str, bool, bool], None]] = None
        self.on_phone_connected: Optional[Callable[[str], None]] = None

    def start(self):
        if self._running:
            return
        self._running = True

        # Configure RequestHandler class variables
        AirBridgeRequestHandler.audio_bridge = self.audio_bridge
        AirBridgeRequestHandler.whisper_client = self.whisper_client
        AirBridgeRequestHandler.on_image_callback = self._on_image_internal
        AirBridgeRequestHandler.on_transcription_callback = self._on_transcription_internal

        # Start HTTP Server
        self.httpd = HTTPServer(("", self.http_port), AirBridgeRequestHandler)
        self._http_thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self._http_thread.start()
        print(f"[Server] HTTP Server listening on port :{self.http_port}")

        # Start UDP Audio Receiver
        self._udp_thread = threading.Thread(target=self._udp_audio_loop, daemon=True)
        self._udp_thread.start()
        print(f"[Server] UDP Audio Receiver listening on port :{self.udp_audio_port}")

    def _on_image_internal(self, file_path: str):
        if self.on_image_received:
            self.on_image_received(file_path)

    def _on_transcription_internal(self, text: str, type_into_window: bool, copy_clipboard: bool):
        # Auto-type via xdotool if requested
        if type_into_window and text:
            try:
                subprocess.Popen(["xdotool", "type", "--delay", "10", text])
            except Exception as e:
                print(f"[Server] xdotool typing error: {e}")

        if self.on_transcription_received:
            self.on_transcription_received(text, type_into_window, copy_clipboard)

    def _udp_audio_loop(self):
        self.udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.udp_socket.bind(("", self.udp_audio_port))
        self.udp_socket.settimeout(1.0)

        last_sender = None

        while self._running:
            try:
                data, addr = self.udp_socket.recvfrom(4096)
                if addr[0] != last_sender:
                    last_sender = addr[0]
                    if self.on_phone_connected:
                        self.on_phone_connected(last_sender)

                # Feed PCM data to virtual mic
                self.audio_bridge.feed_pcm(data)
            except socket.timeout:
                continue
            except Exception as e:
                if self._running:
                    print(f"[Server] UDP Audio error: {e}")
                time.sleep(0.1)

    def stop(self):
        self._running = False
        if self.httpd:
            self.httpd.shutdown()
            self.httpd = None
        if self.udp_socket:
            self.udp_socket.close()
            self.udp_socket = None
