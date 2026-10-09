package com.example.wlan1vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class Wlan1VpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var socksServer: Wlan1SocksServer? = null
    private val socksPort = 10800
    private var tunnelStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tunFd == null) start()
        return START_STICKY
    }

    private fun start() {
        val builder = Builder()
            .setSession("wlan1vpn")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        // Without this, OUR OWN app's outbound traffic (the SOCKS server's
        // connections) also gets captured by our own tun0, since the VPN
        // otherwise covers every UID including our own - that's the
        // self-loop causing instant fake "connects" to nothing. Excluding
        // ourselves here fixes it directly, independent of protect().
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "failed to exclude own app from VPN capture", e)
        }

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "establish() returned null - permission not granted?")
            return
        }
        tunFd = fd

        val server = Wlan1SocksServer(this, socksPort)
        socksServer = server
        server.start()

        // give the SOCKS server a moment to bind before the tunnel engine connects to it
        Thread.sleep(300)

        val logFile = File(filesDir, "hev-tunnel.log")
        val configFile = File(filesDir, "hev-tunnel.yaml")
        configFile.writeText(
            """
            tunnel:
              mtu: 1500
              multi-queue: false
              name: tun0
              ipv4: 10.0.0.2

            socks5:
              port: $socksPort
              address: 127.0.0.1
              udp: 'tcp'

            misc:
              log-level: debug
              log-file: ${logFile.absolutePath}
            """.trimIndent()
        )
        Log.i(TAG, "wrote config to ${configFile.absolutePath}, engine log will be at ${logFile.absolutePath}")

        // hev-socks5-tunnel runs as a native library loaded INSIDE this process
        // (via JNI), not a separate process - so there's no fd-passing problem:
        // the fd number we hand it is simply valid, same address space.
        // (TProxyService itself is the class already compiled into the AAR -
        // we don't declare it ourselves, that caused a duplicate-class crash.)
        tunnelStarted = hev.htproxy.TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)
        Log.i(TAG, "TProxyStartService -> $tunnelStarted")
    }

    override fun onDestroy() {
        if (tunnelStarted) {
            try { hev.htproxy.TProxyService.TProxyStopService() } catch (e: Exception) {
                Log.e(TAG, "TProxyStopService failed", e)
            }
            tunnelStarted = false
        }
        socksServer?.shutdown()
        tunFd?.close()
        tunFd = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "wlan1vpn"
    }
}
