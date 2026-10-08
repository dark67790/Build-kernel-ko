package com.example.wlan1vpn

import android.net.VpnService
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal local SOCKS5 server - CONNECT (TCP) only, no auth.
 *
 * tun2proxy forwards everything it reads off the tun interface here.
 * For each request we dial the real destination ourselves and call
 * VpnService.protect() on that socket, which exempts it from our own
 * VPN capture. It then falls through to the normal routing rules -
 * including the root `ip rule`/default route from wlan1_up.sh - and
 * goes out wlan1.
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

            if (cmd != 1) { // only CONNECT supported for now
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            val r = Socket()
            remote = r
            vpnService.protect(r)
            r.connect(InetSocketAddress(destAddress, destPort), 10000)

            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()

            val t1 = Thread { pipe(client.getInputStream(), r.getOutputStream()) }
            val t2 = Thread { pipe(r.getInputStream(), client.getOutputStream()) }
            t1.start(); t2.start()
            t1.join(); t2.join()
        } catch (e: Exception) {
            Log.e(TAG, "client session error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
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
