"""
Audio Bridge - Manages low-latency PulseAudio / PipeWire virtual microphone.
Pipes incoming PCM audio from Android phone into a virtual input device,
allowing system apps (Discord, Zoom, Google Meet, OBS) to receive the phone mic.
"""

import math
import os
import struct
import subprocess
import threading
import time
from typing import Callable, Optional


class AudioBridge:
    def __init__(self, sample_rate: int = 48000, channels: int = 1):
        self.sample_rate = sample_rate
        self.channels = channels
        self.sink_name = "AntigravitySink"
        self.source_name = "AntigravityVirtualMic"

        self.pacat_process: Optional[subprocess.Popen] = None
        self._lock = threading.Lock()
        self.is_running = False
        self.is_muted = False
        self.gain = 1.0

        self.vu_callback: Optional[Callable[[float], None]] = None
        self._init_virtual_devices()

    def _init_virtual_devices(self):
        """Checks if virtual sink & source exist in PulseAudio/PipeWire; creates if missing."""
        try:
            sinks_out = subprocess.check_output(["pactl", "list", "sinks", "short"], text=True)
            sources_out = subprocess.check_output(["pactl", "list", "sources", "short"], text=True)

            # Check if AntigravitySink exists
            if "AntigravitySink" in sinks_out and "AntigravityVirtualMic" in sources_out:
                self.sink_name = "AntigravitySink"
                self.source_name = "AntigravityVirtualMic"
                return

            # Check if AirBridgeSink already exists
            if "AirBridgeSink" in sinks_out and "AirBridgeMic" in sources_out:
                self.sink_name = "AirBridgeSink"
                self.source_name = "AirBridgeMic"
                return

            # Create AirBridgeSink and AirBridgeMic
            self.sink_name = "AirBridgeSink"
            self.source_name = "AirBridgeMic"

            if "AirBridgeSink" not in sinks_out:
                subprocess.run(
                    ["pactl", "load-module", "module-null-sink", "sink_name=AirBridgeSink",
                     "sink_properties=device.description=AirBridge_Sink"],
                    check=False
                )

            if "AirBridgeMic" not in sources_out:
                subprocess.run(
                    ["pactl", "load-module", "module-remap-source", "master=AirBridgeSink.monitor",
                     "source_name=AirBridgeMic",
                     "source_properties=device.description=AirBridge_Microphone"],
                    check=False
                )
        except Exception as e:
            print(f"[AudioBridge] Error initializing virtual devices: {e}")

    def start(self):
        with self._lock:
            if self.is_running:
                return
            self._start_pacat()
            self.is_running = True

    def _start_pacat(self):
        try:
            cmd = [
                "pacat",
                "-p",
                "-d", self.sink_name,
                f"--rate={self.sample_rate}",
                f"--channels={self.channels}",
                "--format=s16le",
                "--latency-msec=20",
                "--client-name=AirBridgeVirtualMic"
            ]
            self.pacat_process = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL
            )
            print(f"[AudioBridge] pacat pipe opened to sink: {self.sink_name}")
        except Exception as e:
            print(f"[AudioBridge] Failed to spawn pacat: {e}")
            self.pacat_process = None

    def feed_pcm(self, pcm_data: bytes):
        """Feeds raw 16-bit PCM bytes into the pacat process stdin and calculates VU level."""
        if not self.is_running or not pcm_data:
            return

        with self._lock:
            if self.pacat_process is None or self.pacat_process.poll() is not None:
                self._start_pacat()

            if self.pacat_process and self.pacat_process.stdin:
                try:
                    if self.is_muted:
                        # Write silence
                        silent_bytes = b"\x00" * len(pcm_data)
                        self.pacat_process.stdin.write(silent_bytes)
                        self.pacat_process.stdin.flush()
                        if self.vu_callback:
                            self.vu_callback(0.0)
                        return

                    # Gain adjustment & VU calculation
                    sample_count = len(pcm_data) // 2
                    if sample_count > 0:
                        samples = struct.unpack(f"<{sample_count}h", pcm_data)

                        if self.gain != 1.0:
                            adjusted = []
                            for s in samples:
                                adj = int(s * self.gain)
                                adj = max(-32768, min(32767, adj))
                                adjusted.append(adj)
                            out_bytes = struct.pack(f"<{sample_count}h", *adjusted)
                            samples = adjusted
                        else:
                            out_bytes = pcm_data

                        # Write to virtual sink
                        self.pacat_process.stdin.write(out_bytes)
                        self.pacat_process.stdin.flush()

                        # VU level (RMS)
                        if self.vu_callback:
                            sum_sq = sum(s * s for s in samples)
                            rms = math.sqrt(sum_sq / sample_count)
                            if rms > 0:
                                db = 20 * math.log10(rms / 32767.0)
                            else:
                                db = -100.0
                            # Normalize -60dB -> 0dB to 0.0 -> 1.0
                            norm_vu = max(0.0, min(1.0, (db + 60.0) / 60.0))
                            self.vu_callback(norm_vu)
                except (BrokenPipeError, OSError):
                    self._start_pacat()

    def set_mute(self, muted: bool):
        self.is_muted = muted

    def set_gain(self, gain: float):
        self.gain = max(0.1, min(5.0, gain))

    def set_as_default_source(self):
        """Sets the virtual mic as default PulseAudio/PipeWire source."""
        try:
            subprocess.run(["pactl", "set-default-source", self.source_name], check=False)
            print(f"[AudioBridge] Set {self.source_name} as default Linux source.")
            return True
        except Exception as e:
            print(f"[AudioBridge] Failed to set default source: {e}")
            return False

    def stop(self):
        with self._lock:
            self.is_running = False
            if self.pacat_process:
                try:
                    self.pacat_process.stdin.close()
                    self.pacat_process.terminate()
                    self.pacat_process.wait(timeout=1)
                except Exception:
                    pass
                self.pacat_process = None
