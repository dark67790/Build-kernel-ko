package com.example.wlan1vpn

import android.net.VpnService
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal local SOCKS5 server - no auth. Supports:
 *  - CONNECT (TCP): dial the real destination ourselves, protect() the
 *    socket so it's exempt from our own VPN capture, then pipe both ways.
 *  - UDP ASSOCIATE: needed for anything that uses UDP (QUIC/HTTP3 used by
 *    Play Store & Gmail, DNS, P2P/DHT). We open a UDP relay socket, reply
 *    with its address/port, and from then on unwrap/rewrap SOCKS5 UDP
 *    datagrams between the client and the real destinations, each real
 *    destination socket also protect()-ed.
 *
 * tun2proxy/hev forwards everything it reads off the tun interface here.
 * Once protect()-ed, sockets fall through to the normal routing rules -
 * including the root `ip rule`/default route from wlan1_up.sh - and go
 * out wlan1.
 */
class Wlan1SocksServer(private val vpnService: VpnService, private val port: Int) : Thread() {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    override fun run() {
        running = true
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress("127.0.0.1", port))
            serverSocket = server
            Log.i(TAG, "listening on 127.0.0.1:$port")

            while (running) {
                try {
                    val client = server.accept()
                    Thread { handleClient(client) }.start()
                } catch (e: IOException) {
                    if (running) Log.e(TAG, "accept() failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "server setup failed", e)
        }
    }

    fun shutdown() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(client: Socket) {
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())

            // greeting: VER NMETHODS METHODS...
            val ver = input.readUnsignedByte()
            if (ver != 5) { client.close(); return }
            val nMethods = input.readUnsignedByte()
            val methods = ByteArray(nMethods)
            input.readFully(methods)
            output.write(byteArrayOf(5, 0)) // no auth required
            output.flush()

            // request: VER CMD RSV ATYP DST.ADDR DST.PORT
            input.readUnsignedByte() // ver again
            val cmd = input.readUnsignedByte()
            input.readUnsignedByte() // RSV
            val atyp = input.readUnsignedByte()

            val destAddress: InetAddress = when (atyp) {
                1 -> { // IPv4
                    val addr = ByteArray(4)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr)
                }
                3 -> { // domain name
                    val len = input.readUnsignedByte()
                    val nameBytes = ByteArray(len)
                    input.readFully(nameBytes)
                    InetAddress.getByName(String(nameBytes, Charsets.US_ASCII))
                }
                4 -> { // IPv6
                    val addr = ByteArray(16)
                    input.readFully(addr)
                    InetAddress.getByAddress(addr)
                }
                else -> { client.close(); return }
            }
            val destPort = input.readUnsignedShort()

            if (cmd == 3) { // UDP ASSOCIATE
                handleUdpAssociate(client, output)
                return
            }

            if (cmd != 1) { // only CONNECT and UDP ASSOCIATE supported
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            val r = Socket()
            remote = r
            val protectedOk = vpnService.protect(r)
            Log.i(TAG, "connecting to $destAddress:$destPort (protect() -> $protectedOk)")
            r.connect(InetSocketAddress(destAddress, destPort), 10000)
            Log.i(TAG, "connected to $destAddress:$destPort")

            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()

            val t1 = Thread { pipe(client.getInputStream(), r.getOutputStream()) }
            val t2 = Thread { pipe(r.getInputStream(), client.getOutputStream()) }
            t1.start(); t2.start()
            t1.join(); t2.join()
            Log.i(TAG, "session to $destAddress:$destPort ended")
        } catch (e: Exception) {
            Log.e(TAG, "connect/relay failed (dest may be unreachable via wlan1)", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
    }

    /**
     * RFC 1928 UDP ASSOCIATE. The TCP connection stays open for the
     * lifetime of the association - we watch it in the background and
     * tear everything down when it closes. Real UDP datagrams to each
     * distinct destination go out their own protect()-ed DatagramSocket;
     * replies get rewrapped in the SOCKS5 UDP header and sent back to
     * whichever address the client's first datagram came from.
     */
    private fun handleUdpAssociate(client: Socket, output: DataOutputStream) {
        val relay = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val relayPort = relay.localPort
        Log.i(TAG, "UDP ASSOCIATE -> relay on 127.0.0.1:$relayPort")

        // reply: VER REP RSV ATYP BND.ADDR BND.PORT (our relay socket)
        val reply = ByteArray(10)
        reply[0] = 5; reply[1] = 0; reply[2] = 0; reply[3] = 1
        val loopback = InetAddress.getByName("127.0.0.1").address
        System.arraycopy(loopback, 0, reply, 4, 4)
        reply[8] = (relayPort shr 8).toByte()
        reply[9] = (relayPort and 0xFF).toByte()
        output.write(reply)
        output.flush()

        val running = AtomicBoolean(true)
        var clientAddr: InetSocketAddress? = null
        val destSockets = ConcurrentHashMap<String, DatagramSocket>()

        fun teardown() {
            running.set(false)
            try { relay.close() } catch (_: Exception) {}
            destSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
            try { client.close() } catch (_: Exception) {}
        }

        // Control connection lives only to signal when the association
        // should end - no data is expected on it.
        val watcher = Thread {
            try {
                val buf = ByteArray(1)
                while (client.getInputStream().read(buf) >= 0) { /* ignore */ }
            } catch (_: Exception) {
            }
            teardown()
        }
        watcher.isDaemon = true
        watcher.start()

        val buffer = ByteArray(65536)
        try {
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                relay.receive(packet)
                if (clientAddr == null) {
                    clientAddr = packet.socketAddress as InetSocketAddress
                    Log.i(TAG, "UDP ASSOCIATE client bound at $clientAddr")
                }

                val data = packet.data
                val len = packet.length
                if (len < 4) continue
                var idx = 2
                val frag = data[idx].toInt(); idx++
                if (frag != 0) continue // fragmentation not supported
                val a = data[idx].toInt() and 0xFF; idx++
                val dAddr: InetAddress
                when (a) {
                    1 -> {
                        if (idx + 4 > len) continue
                        dAddr = InetAddress.getByAddress(data.copyOfRange(idx, idx + 4)); idx += 4
                    }
                    3 -> {
                        if (idx + 1 > len) continue
                        val l = data[idx].toInt() and 0xFF; idx++
                        if (idx + l > len) continue
                        dAddr = InetAddress.getByName(String(data, idx, l, Charsets.US_ASCII)); idx += l
                    }
                    4 -> {
                        if (idx + 16 > len) continue
                        dAddr = InetAddress.getByAddress(data.copyOfRange(idx, idx + 16)); idx += 16
                    }
                    else -> continue
                }
                if (idx + 2 > len) continue
                val dPort = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
                idx += 2
                val payloadLen = len - idx
                if (payloadLen <= 0) continue

                val key = "$dAddr:$dPort"
                val finalClientAddr = clientAddr
                val destSock = destSockets.getOrPut(key) {
                    val s = DatagramSocket(0)
                    val ok = vpnService.protect(s)
                    Log.i(TAG, "udp relay -> $key (protect() -> $ok)")
                    Thread {
                        val rbuf = ByteArray(65536)
                        try {
                            while (running.get() && !s.isClosed) {
                                val rp = DatagramPacket(rbuf, rbuf.size)
                                s.receive(rp)
                                val header = buildUdpHeader(dAddr, dPort)
                                val outBuf = ByteArray(header.size + rp.length)
                                System.arraycopy(header, 0, outBuf, 0, header.size)
                                System.arraycopy(rp.data, 0, outBuf, header.size, rp.length)
                                if (finalClientAddr != null) {
                                    relay.send(DatagramPacket(outBuf, outBuf.size, finalClientAddr))
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }.apply { isDaemon = true }.start()
                    s
                }
                destSock.send(DatagramPacket(data, idx, payloadLen, dAddr, dPort))
            }
        } catch (_: Exception) {
        } finally {
            teardown()
        }
    }

    private fun buildUdpHeader(addr: InetAddress, port: Int): ByteArray {
        val addrBytes = addr.address
        val atyp = if (addrBytes.size == 4) 1 else 4
        val header = ByteArray(4 + addrBytes.size + 2)
        header[0] = 0; header[1] = 0; header[2] = 0; header[3] = atyp.toByte()
        System.arraycopy(addrBytes, 0, header, 4, addrBytes.size)
        header[4 + addrBytes.size] = (port shr 8).toByte()
        header[4 + addrBytes.size + 1] = (port and 0xFF).toByte()
        return header
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "wlan1vpn-socks"
    }
}
