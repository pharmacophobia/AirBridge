"""
Pairing & Discovery - Handles LAN UDP broadcast auto-discovery and local IP detection.
"""

import socket
import threading
import time
from typing import Optional


class PairingManager:
    DISCOVERY_PORT = 8767

    def __init__(self, http_port: int = 8765, udp_audio_port: int = 8766):
        self.http_port = http_port
        self.udp_audio_port = udp_audio_port
        self.hostname = socket.gethostname()
        self.local_ip = self._detect_local_ip()

        self._running = False
        self._thread: Optional[threading.Thread] = None
        self._socket: Optional[socket.socket] = None

    def _detect_local_ip(self) -> str:
        """Finds primary local LAN IP by querying routing table via dummy UDP socket."""
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            # Does not actually transmit network traffic
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
        except Exception:
            ip = "127.0.0.1"
        finally:
            s.close()
        return ip

    def start_discovery_responder(self):
        """Starts listening for broadcast packets from phones seeking AirBridge servers."""
        if self._running:
            return

        self._running = True
        self._thread = threading.Thread(target=self._discovery_loop, daemon=True)
        self._thread.start()
        print(f"[Pairing] Discovery responder active on UDP :{self.DISCOVERY_PORT}")

    def _discovery_loop(self):
        try:
            self._socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self._socket.bind(("", self.DISCOVERY_PORT))
            self._socket.settimeout(1.0)
        except Exception as e:
            print(f"[Pairing] Failed to bind discovery socket: {e}")
            return

        while self._running:
            try:
                data, addr = self._socket.recvfrom(1024)
                message = data.decode("utf-8", errors="ignore").strip()

                if message == "AIRBRIDGE_DISCOVER":
                    # Respond with connection parameters
                    # Format: AIRBRIDGE_OFFER|httpPort|udpAudioPort|serverName
                    reply = f"AIRBRIDGE_OFFER|{self.http_port}|{self.udp_audio_port}|{self.hostname}"
                    self._socket.sendto(reply.encode("utf-8"), addr)
                    print(f"[Pairing] Answered discovery request from {addr[0]}")
            except socket.timeout:
                continue
            except Exception as e:
                if self._running:
                    print(f"[Pairing] Discovery exception: {e}")
                time.sleep(0.5)

    def stop(self):
        self._running = False
        if self._socket:
            try:
                self._socket.close()
            except Exception:
                pass
            self._socket = None
