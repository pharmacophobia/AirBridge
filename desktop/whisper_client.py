"""
Whisper Client - Connects to the local GPU-accelerated Whisper STT server
for speech-to-text transcribing.
"""

import json
import os
import urllib.request
import urllib.error
import uuid


class WhisperClient:
    def __init__(self, endpoint_url: str = "http://127.0.0.1:8200"):
        self.endpoint_url = endpoint_url.rstrip("/")

    def is_available(self) -> bool:
        """Checks if the local Whisper STT server is online and healthy."""
        try:
            req = urllib.request.Request(f"{self.endpoint_url}/health", method="GET")
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                data = json.loads(resp.read().decode())
                return data.get("status") == "healthy"
        except Exception:
            return False

    def get_metadata(self) -> dict:
        try:
            req = urllib.request.Request(f"{self.endpoint_url}/health", method="GET")
            with urllib.request.urlopen(req, timeout=1.5) as resp:
                return json.loads(resp.read().decode())
        except Exception:
            return {}

    def transcribe_file(self, audio_file_path: str, prompt: str = "") -> str:
        """Uploads audio file to /v1/audio/transcriptions endpoint using multipart/form-data."""
        if not os.path.exists(audio_file_path):
            raise FileNotFoundError(f"Audio file not found: {audio_file_path}")

        boundary = f"----WebKitFormBoundary{uuid.uuid4().hex}"
        url = f"{self.endpoint_url}/v1/audio/transcriptions"

        file_name = os.path.basename(audio_file_path)
        with open(audio_file_path, "rb") as f:
            file_bytes = f.read()

        # Build multipart/form-data payload
        body = []
        # Model parameter
        body.append(f"--{boundary}\r\n".encode())
        body.append(b'Content-Disposition: form-data; name="model"\r\n\r\n')
        body.append(b"whisper-1\r\n")

        # Prompt parameter if provided
        if prompt:
            body.append(f"--{boundary}\r\n".encode())
            body.append(b'Content-Disposition: form-data; name="prompt"\r\n\r\n')
            body.append(f"{prompt}\r\n".encode())

        # File data
        body.append(f"--{boundary}\r\n".encode())
        body.append(f'Content-Disposition: form-data; name="file"; filename="{file_name}"\r\n'.encode())
        body.append(b"Content-Type: audio/wav\r\n\r\n")
        body.append(file_bytes)
        body.append(b"\r\n")

        # End boundary
        body.append(f"--{boundary}--\r\n".encode())

        payload = b"".join(body)

        req = urllib.request.Request(url, data=payload, method="POST")
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        req.add_header("Content-Length", str(len(payload)))

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                result = json.loads(resp.read().decode())
                return result.get("text", "").strip()
        except urllib.error.HTTPError as e:
            error_body = e.read().decode()
            print(f"[WhisperClient] HTTP Error {e.code}: {error_body}")
            return ""
        except Exception as e:
            print(f"[WhisperClient] Transcription error: {e}")
            return ""
