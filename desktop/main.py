#!/usr/bin/env python3
"""
AirBridge Desktop - Linux Companion Application
Provides Virtual Microphone, Seamless Image Vault, and AI Speech-to-Text Transcriptions.
"""

import os
import sys
import time
import subprocess
import threading
from typing import List

from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QTabWidget, QLabel, QPushButton, QSlider, QCheckBox, QListWidget,
    QListWidgetItem, QProgressBar, QFileDialog, QMessageBox, QFrame,
    QSystemTrayIcon, QMenu, QSplitter, QTextEdit, QScrollArea, QGridLayout
)
from PyQt6.QtCore import Qt, QTimer, pyqtSignal, QObject, QUrl, QSize
from PyQt6.QtGui import QIcon, QPixmap, QImage, QColor, QFont, QDesktopServices, QPainter

from audio_bridge import AudioBridge
from whisper_client import WhisperClient
from server import ServerManager
from pairing import PairingManager


DARK_STYLESHEET = """
QMainWindow, QWidget {
    background-color: #0B0F19;
    color: #F8FAFC;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 13px;
}

QTabWidget::pane {
    border: 1px solid #1E293B;
    background-color: #0F172A;
    border-radius: 8px;
    padding: 6px;
}

QTabBar::tab {
    background: #1E293B;
    color: #94A3B8;
    padding: 10px 20px;
    margin-right: 4px;
    border-top-left-radius: 8px;
    border-top-right-radius: 8px;
    font-weight: bold;
}

QTabBar::tab:selected {
    background: #06B6D4;
    color: #0B0F19;
}

QTabBar::tab:hover:!selected {
    background: #334155;
    color: #F8FAFC;
}

QFrame.card {
    background-color: #151E2E;
    border: 1px solid #1E293B;
    border-radius: 12px;
    padding: 14px;
}

QPushButton {
    background-color: #1E293B;
    color: #F8FAFC;
    border: 1px solid #334155;
    border-radius: 8px;
    padding: 8px 16px;
    font-weight: bold;
}

QPushButton:hover {
    background-color: #06B6D4;
    color: #0B0F19;
    border-color: #06B6D4;
}

QPushButton:pressed {
    background-color: #0891B2;
}

QPushButton.primary {
    background-color: #06B6D4;
    color: #0B0F19;
    border: none;
}

QPushButton.primary:hover {
    background-color: #22D3EE;
}

QPushButton.success {
    background-color: #10B981;
    color: #0B0F19;
    border: none;
}

QPushButton.success:hover {
    background-color: #34D399;
}

QPushButton.danger {
    background-color: #EF4444;
    color: #FFFFFF;
    border: none;
}

QProgressBar {
    border: 1px solid #334155;
    border-radius: 6px;
    text-align: center;
    background-color: #1E293B;
    height: 14px;
}

QProgressBar::chunk {
    background-color: qlineargradient(x1:0, y1:0, x2:1, y2:0, stop:0 #10B981, stop:0.8 #06B6D4, stop:1 #EF4444);
    border-radius: 5px;
}

QSlider::groove:horizontal {
    height: 6px;
    background: #1E293B;
    border-radius: 3px;
}

QSlider::sub-page:horizontal {
    background: #06B6D4;
    border-radius: 3px;
}

QSlider::handle:horizontal {
    background: #F8FAFC;
    border: 2px solid #06B6D4;
    width: 16px;
    margin-top: -5px;
    margin-bottom: -5px;
    border-radius: 8px;
}

QListWidget, QTextEdit {
    background-color: #111827;
    border: 1px solid #1E293B;
    border-radius: 8px;
    padding: 6px;
    color: #F8FAFC;
}

QListWidget::item {
    background-color: #151E2E;
    border: 1px solid #1E293B;
    border-radius: 6px;
    margin-bottom: 6px;
    padding: 8px;
}

QListWidget::item:selected {
    background-color: #1E293B;
    border-color: #06B6D4;
}

QCheckBox {
    spacing: 8px;
}

QCheckBox::indicator {
    width: 18px;
    height: 18px;
    border: 1px solid #334155;
    border-radius: 4px;
    background-color: #1E293B;
}

QCheckBox::indicator:checked {
    background-color: #06B6D4;
}
"""


class BridgeSignals(QObject):
    vu_updated = pyqtSignal(float)
    image_received = pyqtSignal(str)
    transcription_received = pyqtSignal(str, bool, bool)
    phone_connected = pyqtSignal(str)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("AirBridge — Computer & Phone Companion")
        self.resize(920, 680)
        self.setStyleSheet(DARK_STYLESHEET)

        self.signals = BridgeSignals()
        self.audio_bridge = AudioBridge(sample_rate=48000, channels=1)
        self.whisper_client = WhisperClient(endpoint_url="http://127.0.0.1:8200")
        self.server_mgr = ServerManager(self.audio_bridge, self.whisper_client, http_port=8765, udp_audio_port=8766)
        self.pairing_mgr = PairingManager(http_port=8765, udp_audio_port=8766)

        # Settings
        self.auto_copy_transcriptions = True
        self.auto_type_transcriptions = False
        self.show_notifications = True

        self._setup_signals()
        self._init_ui()
        self._start_services()

    def _setup_signals(self):
        self.signals.vu_updated.connect(self._on_vu_updated)
        self.signals.image_received.connect(self._on_image_received)
        self.signals.transcription_received.connect(self._on_transcription_received)
        self.signals.phone_connected.connect(self._on_phone_connected)

        # Connect audio bridge VU level callback
        self.audio_bridge.vu_callback = lambda level: self.signals.vu_updated.emit(level)

        # Connect server callbacks
        self.server_mgr.on_image_received = lambda path: self.signals.image_received.emit(path)
        self.server_mgr.on_transcription_received = lambda text, typ, cpy: self.signals.transcription_received.emit(text, typ, cpy)
        self.server_mgr.on_phone_connected = lambda ip: self.signals.phone_connected.emit(ip)

    def _start_services(self):
        self.audio_bridge.start()
        self.server_mgr.start()
        self.pairing_mgr.start_discovery_responder()

    def _init_ui(self):
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        main_layout = QVBoxLayout(central_widget)
        main_layout.setContentsMargins(16, 16, 16, 16)
        main_layout.setSpacing(12)

        # Header with status bar
        header = self._create_header()
        main_layout.addWidget(header)

        # Tab widget
        self.tabs = QTabWidget()
        self.tabs.addTab(self._create_mic_tab(), "🎙️ Virtual Microphone")
        self.tabs.addTab(self._create_image_tab(), "🖼️ Image Vault")
        self.tabs.addTab(self._create_transcription_tab(), "🤖 AI Transcriptions")
        self.tabs.addTab(self._create_pairing_tab(), "📶 Pairing & ADB")

        main_layout.addWidget(self.tabs)

    def _create_header(self) -> QWidget:
        frame = QFrame()
        frame.setProperty("class", "card")
        layout = QHBoxLayout(frame)
        layout.setContentsMargins(12, 10, 12, 10)

        title_layout = QVBoxLayout()
        title_label = QLabel("⚡ AirBridge Desktop")
        title_label.setFont(QFont("Arial", 16, QFont.Weight.Bold))
        title_label.setStyleSheet("color: #06B6D4;")

        subtitle_label = QLabel("Seamless Companion: Phone Virtual Mic, Instant Images & AI Transcribe")
        subtitle_label.setStyleSheet("color: #94A3B8; font-size: 11px;")
        title_layout.addWidget(title_label)
        title_layout.addWidget(subtitle_label)
        layout.addLayout(title_layout)

        layout.addStretch()

        # Connection badge
        self.status_badge = QLabel("● Ready for Connection")
        self.status_badge.setStyleSheet("""
            background-color: #1E293B;
            color: #10B981;
            padding: 6px 12px;
            border-radius: 12px;
            font-weight: bold;
            font-size: 12px;
        """)
        layout.addWidget(self.status_badge)

        return frame

    # --- TAB 1: VIRTUAL MICROPHONE ---

    def _create_mic_tab(self) -> QWidget:
        widget = QWidget()
        layout = QVBoxLayout(widget)
        layout.setSpacing(16)

        # Device info card
        card = QFrame()
        card.setProperty("class", "card")
        card_layout = QVBoxLayout(card)

        dev_title = QLabel("Linux Virtual Microphone Pipeline")
        dev_title.setFont(QFont("Arial", 13, QFont.Weight.Bold))
        dev_title.setStyleSheet("color: #06B6D4;")
        card_layout.addWidget(dev_title)

        self.mic_device_label = QLabel(
            f"Active PulseAudio/PipeWire Source: <b>{self.audio_bridge.source_name}</b><br>"
            f"Sink: <b>{self.audio_bridge.sink_name}</b> (48,000 Hz, 16-bit PCM Low-Latency)"
        )
        self.mic_device_label.setStyleSheet("color: #E2E8F0; line-height: 1.4;")
        card_layout.addWidget(self.mic_device_label)

        btn_row = QHBoxLayout()
        btn_default = QPushButton("Set as Linux Default Input Device")
        btn_default.clicked.connect(self._set_default_mic)
        btn_row.addWidget(btn_default)

        self.btn_mute = QPushButton("Mute PC Mic")
        self.btn_mute.clicked.connect(self._toggle_pc_mute)
        btn_row.addWidget(self.btn_mute)

        card_layout.addLayout(btn_row)
        layout.addWidget(card)

        # Live VU Meter Card
        vu_card = QFrame()
        vu_card.setProperty("class", "card")
        vu_layout = QVBoxLayout(vu_card)

        vu_header = QHBoxLayout()
        vu_title = QLabel("Live Audio Input Level (Phone Stream)")
        vu_title.setFont(QFont("Arial", 12, QFont.Weight.Bold))
        self.vu_db_label = QLabel("-∞ dB")
        self.vu_db_label.setStyleSheet("color: #06B6D4; font-weight: bold;")
        vu_header.addWidget(vu_title)
        vu_header.addStretch()
        vu_header.addWidget(self.vu_db_label)
        vu_layout.addLayout(vu_header)

        self.vu_bar = QProgressBar()
        self.vu_bar.setRange(0, 100)
        self.vu_bar.setValue(0)
        self.vu_bar.setTextVisible(False)
        self.vu_bar.setFixedHeight(18)
        vu_layout.addWidget(self.vu_bar)

        # Gain Slider
        gain_layout = QHBoxLayout()
        gain_label = QLabel("PC Input Gain:")
        self.gain_val_label = QLabel("1.0x")
        self.gain_val_label.setStyleSheet("color: #06B6D4; font-weight: bold;")
        self.gain_slider = QSlider(Qt.Orientation.Horizontal)
        self.gain_slider.setRange(5, 30)
        self.gain_slider.setValue(10)
        self.gain_slider.valueChanged.connect(self._on_gain_changed)

        gain_layout.addWidget(gain_label)
        gain_layout.addWidget(self.gain_slider)
        gain_layout.addWidget(self.gain_val_label)
        vu_layout.addLayout(gain_layout)

        layout.addWidget(vu_card)

        # Instructions
        info_card = QFrame()
        info_card.setProperty("class", "card")
        info_layout = QVBoxLayout(info_card)
        info_text = QLabel(
            "<b>💡 How to use with Discord, Zoom, OBS, or Chrome:</b><br>"
            "1. Open your voice settings in Discord, Zoom, or OBS.<br>"
            "2. Select <b>'Antigravity_Virtual_Microphone'</b> (or 'AirBridge Microphone') as your Input Device.<br>"
            "3. Tap the glowing Mic button in the AirBridge Android app to start speaking!"
        )
        info_text.setStyleSheet("color: #94A3B8; line-height: 1.5;")
        info_layout.addWidget(info_text)
        layout.addWidget(info_card)

        layout.addStretch()
        return widget

    def _set_default_mic(self):
        success = self.audio_bridge.set_as_default_source()
        if success:
            QMessageBox.information(self, "Virtual Mic", f"Set {self.audio_bridge.source_name} as default input device!")
        else:
            QMessageBox.warning(self, "Virtual Mic", "Failed to set default source via pactl.")

    def _toggle_pc_mute(self):
        self.audio_bridge.is_muted = not self.audio_bridge.is_muted
        if self.audio_bridge.is_muted:
            self.btn_mute.setText("Unmute PC Mic")
            self.btn_mute.setStyleSheet("background-color: #EF4444; color: white;")
        else:
            self.btn_mute.setText("Mute PC Mic")
            self.btn_mute.setStyleSheet("")

    def _on_gain_changed(self, val: int):
        gain = val / 10.0
        self.audio_bridge.set_gain(gain)
        self.gain_val_label.setText(f"{gain:.1f}x")

    def _on_vu_updated(self, level: float):
        int_val = int(level * 100)
        self.vu_bar.setValue(int_val)
        if level > 0:
            db = (level * 60.0) - 60.0
            self.vu_db_label.setText(f"{db:.1f} dB")
        else:
            self.vu_db_label.setText("-∞ dB")

    # --- TAB 2: IMAGE VAULT ---

    def _create_image_tab(self) -> QWidget:
        widget = QWidget()
        layout = QVBoxLayout(widget)
        layout.setSpacing(12)

        # Header bar
        top_bar = QHBoxLayout()
        vault_label = QLabel(f"📂 Saved to: <code>{self.server_mgr.AirBridgeRequestHandler.save_directory}</code>")
        vault_label.setStyleSheet("color: #94A3B8;")
        top_bar.addWidget(vault_label)
        top_bar.addStretch()

        btn_open_folder = QPushButton("Open Folder in Files")
        btn_open_folder.clicked.connect(self._open_image_folder)
        top_bar.addWidget(btn_open_folder)
        layout.addLayout(top_bar)

        # Image list / preview splitter
        splitter = QSplitter(Qt.Orientation.Horizontal)

        # Left list
        left_widget = QWidget()
        left_layout = QVBoxLayout(left_widget)
        left_layout.setContentsMargins(0, 0, 0, 0)
        left_title = QLabel("Received Images")
        left_title.setFont(QFont("Arial", 11, QFont.Weight.Bold))
        left_layout.addWidget(left_title)

        self.image_list = QListWidget()
        self.image_list.itemClicked.connect(self._on_image_selected)
        left_layout.addWidget(self.image_list)
        splitter.addWidget(left_widget)

        # Right preview card
        right_widget = QFrame()
        right_widget.setProperty("class", "card")
        right_layout = QVBoxLayout(right_widget)

        self.preview_label = QLabel("Select an image to preview")
        self.preview_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.preview_label.setStyleSheet("color: #64748B; background: #0F172A; border-radius: 8px;")
        self.preview_label.setMinimumSize(320, 260)
        right_layout.addWidget(self.preview_label, stretch=1)

        # Image action buttons
        action_row = QHBoxLayout()
        self.btn_copy_image = QPushButton("📋 Copy Image to Clipboard")
        self.btn_copy_image.setProperty("class", "primary")
        self.btn_copy_image.clicked.connect(self._copy_selected_image)

        self.btn_open_viewer = QPushButton("👁️ Open in Default Viewer")
        self.btn_open_viewer.clicked.connect(self._open_selected_image_viewer)

        action_row.addWidget(self.btn_copy_image)
        action_row.addWidget(self.btn_open_viewer)
        right_layout.addLayout(action_row)

        splitter.addWidget(right_widget)
        splitter.setSizes([340, 500])
        layout.addWidget(splitter)

        # Load existing images
        self._reload_existing_images()
        return widget

    def _reload_existing_images(self):
        save_dir = self.server_mgr.AirBridgeRequestHandler.save_directory
        if os.path.exists(save_dir):
            files = sorted(
                [os.path.join(save_dir, f) for f in os.listdir(save_dir) if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".gif"))],
                key=os.path.getmtime,
                reverse=True
            )
            for f in files:
                self._add_image_to_list(f)

    def _add_image_to_list(self, file_path: str):
        filename = os.path.basename(file_path)
        item = QListWidgetItem(filename)
        item.setData(Qt.ItemDataRole.UserRole, file_path)
        self.image_list.insertItem(0, item)

    def _on_image_selected(self, item: QListWidgetItem):
        file_path = item.data(Qt.ItemDataRole.UserRole)
        if file_path and os.path.exists(file_path):
            pixmap = QPixmap(file_path)
            if not pixmap.isNull():
                scaled = pixmap.scaled(
                    self.preview_label.size(),
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation
                )
                self.preview_label.setPixmap(scaled)

    def _copy_selected_image(self):
        curr = self.image_list.currentItem()
        if not curr:
            return
        path = curr.data(Qt.ItemDataRole.UserRole)
        if path and os.path.exists(path):
            img = QImage(path)
            if not img.isNull():
                QApplication.clipboard().setImage(img)
                QMessageBox.information(self, "Clipboard", "Image copied to Linux clipboard! Ready to Ctrl+V.")

    def _open_selected_image_viewer(self):
        curr = self.image_list.currentItem()
        if not curr:
            return
        path = curr.data(Qt.ItemDataRole.UserRole)
        if path and os.path.exists(path):
            QDesktopServices.openUrl(QUrl.fromLocalFile(path))

    def _open_image_folder(self):
        save_dir = self.server_mgr.AirBridgeRequestHandler.save_directory
        os.makedirs(save_dir, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(save_dir))

    def _on_image_received(self, file_path: str):
        self._add_image_to_list(file_path)
        self.image_list.setCurrentRow(0)
        self._on_image_selected(self.image_list.item(0))

        # Auto-copy image to clipboard immediately
        img = QImage(file_path)
        if not img.isNull():
            QApplication.clipboard().setImage(img)

        self.status_badge.setText(f"📸 Image Received: {os.path.basename(file_path)}")

    # --- TAB 3: AI TRANSCRIPTIONS ---

    def _create_transcription_tab(self) -> QWidget:
        widget = QWidget()
        layout = QVBoxLayout(widget)
        layout.setSpacing(12)

        # Settings toolbar
        settings_card = QFrame()
        settings_card.setProperty("class", "card")
        set_layout = QHBoxLayout(settings_card)

        self.cb_auto_copy = QCheckBox("Auto-copy incoming transcriptions to Linux Clipboard")
        self.cb_auto_copy.setChecked(True)

        self.cb_auto_type = QCheckBox("Auto-type into active Linux window (via xdotool)")
        self.cb_auto_type.setChecked(False)

        set_layout.addWidget(self.cb_auto_copy)
        set_layout.addWidget(self.cb_auto_type)
        set_layout.addStretch()

        whisper_status = "● Whisper RTX GPU: ONLINE" if self.whisper_client.is_available() else "○ Whisper STT: Offline"
        whisper_color = "#10B981" if self.whisper_client.is_available() else "#94A3B8"
        self.whisper_label = QLabel(whisper_status)
        self.whisper_label.setStyleSheet(f"color: {whisper_color}; font-weight: bold;")
        set_layout.addWidget(self.whisper_label)

        layout.addWidget(settings_card)

        # Transcriptions log
        log_title = QLabel("AI Transcription History")
        log_title.setFont(QFont("Arial", 12, QFont.Weight.Bold))
        layout.addWidget(log_title)

        self.transcription_list = QListWidget()
        layout.addWidget(self.transcription_list)

        # Actions
        btn_bar = QHBoxLayout()
        btn_copy_text = QPushButton("Copy Selected Text")
        btn_copy_text.clicked.connect(self._copy_selected_transcription)

        btn_clear = QPushButton("Clear History")
        btn_clear.clicked.connect(lambda: self.transcription_list.clear())

        btn_bar.addWidget(btn_copy_text)
        btn_bar.addWidget(btn_clear)
        btn_bar.addStretch()
        layout.addLayout(btn_bar)

        return widget

    def _copy_selected_transcription(self):
        curr = self.transcription_list.currentItem()
        if curr:
            text = curr.data(Qt.ItemDataRole.UserRole)
            if text:
                QApplication.clipboard().setText(text)
                QMessageBox.information(self, "Clipboard", "Copied transcription to clipboard!")

    def _on_transcription_received(self, text: str, type_into_window: bool, copy_clipboard: bool):
        timestamp = time.strftime("%H:%M:%S")
        item_text = f"[{timestamp}] {text}"
        item = QListWidgetItem(item_text)
        item.setData(Qt.ItemDataRole.UserRole, text)
        self.transcription_list.insertItem(0, item)

        if self.cb_auto_copy.isChecked() or copy_clipboard:
            QApplication.clipboard().setText(text)

        if self.cb_auto_type.isChecked() or type_into_window:
            try:
                subprocess.Popen(["xdotool", "type", "--delay", "10", text])
            except Exception as e:
                print(f"[Desktop] xdotool error: {e}")

        self.status_badge.setText(f"🤖 Transcribed: '{text[:25]}...'")

    # --- TAB 4: PAIRING & ADB ---

    def _create_pairing_tab(self) -> QWidget:
        widget = QWidget()
        layout = QVBoxLayout(widget)
        layout.setSpacing(16)

        pair_card = QFrame()
        pair_card.setProperty("class", "card")
        p_layout = QVBoxLayout(pair_card)

        p_title = QLabel("Connect Your Android Device")
        p_title.setFont(QFont("Arial", 14, QFont.Weight.Bold))
        p_title.setStyleSheet("color: #06B6D4;")
        p_layout.addWidget(p_title)

        info_text = QLabel(
            f"<b>Local Wi-Fi IP Address:</b> <code style='font-size:15px; color:#10B981;'>{self.pairing_mgr.local_ip}</code><br>"
            f"<b>HTTP Server Port:</b> <code>{self.pairing_mgr.http_port}</code><br>"
            f"<b>UDP Audio Port:</b> <code>{self.pairing_mgr.udp_audio_port}</code><br><br>"
            "1. Open the <b>AirBridge</b> app on your Android phone.<br>"
            "2. Tap <b>'Auto-Scan'</b> (discovery will find this PC automatically).<br>"
            "3. Or enter the IP address above manually."
        )
        info_text.setStyleSheet("line-height: 1.6;")
        p_layout.addWidget(info_text)

        layout.addWidget(pair_card)

        # ADB Reverse Tethering Card
        adb_card = QFrame()
        adb_card.setProperty("class", "card")
        adb_layout = QVBoxLayout(adb_card)

        adb_title = QLabel("🔌 USB Zero-Latency Mode (ADB Reverse)")
        adb_title.setFont(QFont("Arial", 12, QFont.Weight.Bold))
        adb_title.setStyleSheet("color: #F59E0B;")
        adb_layout.addWidget(adb_title)

        adb_desc = QLabel(
            "Want 0ms latency without Wi-Fi? Plug your phone in via USB and click below.<br>"
            "Your phone will connect straight to <code>127.0.0.1:8765</code> through the USB cable!"
        )
        adb_desc.setStyleSheet("color: #94A3B8; line-height: 1.4;")
        adb_layout.addWidget(adb_desc)

        btn_adb = QPushButton("Enable 1-Click USB ADB Reverse")
        btn_adb.clicked.connect(self._enable_adb_reverse)
        adb_layout.addWidget(btn_adb)

        layout.addWidget(adb_card)
        layout.addStretch()
        return widget

    def _enable_adb_reverse(self):
        try:
            r1 = subprocess.run(["adb", "reverse", "tcp:8765", "tcp:8765"], capture_output=True, text=True)
            if r1.returncode == 0:
                QMessageBox.information(
                    self,
                    "ADB Reverse Active",
                    "USB port forwarding configured!\n\nIn your phone app, connect to host: 127.0.0.1"
                )
            else:
                QMessageBox.warning(self, "ADB Error", f"Failed: {r1.stderr or 'No phone found over ADB'}")
        except Exception as e:
            QMessageBox.critical(self, "ADB Error", str(e))

    def _on_phone_connected(self, ip: str):
        self.status_badge.setText(f"● Phone Streaming: {ip}")
        self.status_badge.setStyleSheet("""
            background-color: #10B981;
            color: #0B0F19;
            padding: 6px 12px;
            border-radius: 12px;
            font-weight: bold;
            font-size: 12px;
        """)

    def closeEvent(self, event):
        self.audio_bridge.stop()
        self.server_mgr.stop()
        self.pairing_mgr.stop()
        event.accept()


def main():
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
