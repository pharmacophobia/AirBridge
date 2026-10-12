package com.airbridge.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.airbridge.app.model.DiscoveredServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class DiscoveryClient(private val context: Context) {

    companion object {
        private const val TAG = "DiscoveryClient"
        const val DISCOVERY_PORT = 8767
        private const val DISCOVER_MSG = "AIRBRIDGE_DISCOVER"
        private const val OFFER_PREFIX = "AIRBRIDGE_OFFER"
    }

    suspend fun discoverServers(timeoutMs: Int = 2500): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<DiscoveredServer>()
        var socket: DatagramSocket? = null
        var multicastLock: WifiManager.MulticastLock? = null

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("AirBridgeDiscovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = timeoutMs
            }

            val sendData = DISCOVER_MSG.toByteArray(Charsets.UTF_8)
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val sendPacket = DatagramPacket(sendData, sendData.size, broadcastAddress, DISCOVERY_PORT)

            // Send discovery broadcast
            socket.send(sendPacket)

            // Also send to local loopback / gateway subnet broadcast if applicable
            try {
                socket.send(DatagramPacket(sendData, sendData.size, InetAddress.getByName("10.0.2.2"), DISCOVERY_PORT))
            } catch (_: Exception) {}

            val buffer = ByteArray(1024)
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)

                    val reply = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8).trim()
                    val hostAddress = receivePacket.address.hostAddress ?: continue

                    Log.d(TAG, "Received broadcast response from $hostAddress: $reply")

                    // Format: AIRBRIDGE_OFFER|httpPort|udpAudioPort|serverName
                    if (reply.startsWith(OFFER_PREFIX)) {
                        val parts = reply.split("|")
                        val httpPort = if (parts.size > 1) parts[1].toIntOrNull() ?: 8765 else 8765
                        val udpPort = if (parts.size > 2) parts[2].toIntOrNull() ?: 8766 else 8766
                        val name = if (parts.size > 3) parts[3] else "AirBridge Desktop"

                        val server = DiscoveredServer(
                            host = hostAddress,
                            httpPort = httpPort,
                            udpAudioPort = udpPort,
                            serverName = name
                        )

                        if (servers.none { it.host == server.host && it.httpPort == server.httpPort }) {
                            servers.add(server)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during UDP discovery", e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
        }

        servers
    }
}
