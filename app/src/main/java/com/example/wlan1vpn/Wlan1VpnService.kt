package com.example.wlan1vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log

class Wlan1VpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    private var socksServer: Wlan1SocksServer? = null
    private var tun2proxyProcess: Process? = null
    private val socksPort = 10800

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

        val fd = builder.establish()
        if (fd == null) {
            Log.e(TAG, "establish() returned null - permission not granted?")
            return
        }
        tunFd = fd

        // A subprocess normally doesn't inherit this fd (Android marks it
        // close-on-exec). Clear that flag so tun2proxy can use it after exec().
        try {
            Os.fcntlInt(fd.fileDescriptor, OsConstants.F_SETFD, 0)
        } catch (e: Exception) {
            Log.e(TAG, "failed to clear FD_CLOEXEC on tun fd", e)
        }

        val server = Wlan1SocksServer(this, socksPort)
        socksServer = server
        server.start()

        // give the SOCKS server a moment to bind before tun2proxy connects to it
        Thread.sleep(300)

        val binaryPath = applicationInfo.nativeLibraryDir + "/libtun2proxy.so"
        val rawFd = fd.fd
        val cmd = listOf(
            binaryPath,
            "--tun-fd", rawFd.toString(),
            "--proxy", "socks5://127.0.0.1:$socksPort"
        )

        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val process = pb.start()
            tun2proxyProcess = process

            Thread {
                process.inputStream.bufferedReader().forEachLine {
                    Log.i("tun2proxy", it)
                }
            }.start()

            Log.i(TAG, "tun2proxy launched: $cmd")
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch tun2proxy", e)
        }
    }

    override fun onDestroy() {
        tun2proxyProcess?.destroy()
        socksServer?.shutdown()
        tunFd?.close()
        tunFd = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "wlan1vpn"
    }
}
