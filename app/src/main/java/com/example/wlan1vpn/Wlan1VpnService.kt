package com.example.wlan1vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * Step 1: just establish the TUN interface so ConnectivityService registers
 * this as a real network (this is the part raw `ip rule`/`iptables` tricks
 * could never do). Packets land in the read loop below but are currently
 * dropped - no forwarding yet. That's the next step, added once we've
 * confirmed this part alone makes Android treat the connection as a real
 * network for apps.
 */
class Wlan1VpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tunFd == null) {
            start()
        }
        return START_STICKY
    }

    private fun start() {
        val builder = Builder()
            .setSession("wlan1vpn")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        tunFd = builder.establish()
        if (tunFd == null) {
            Log.e("wlan1vpn", "establish() returned null - permission not granted?")
            return
        }

        running = true
        Thread {
            val input = FileInputStream(tunFd!!.fileDescriptor)
            val buffer = ByteArray(32767)
            Log.i("wlan1vpn", "tun established, reading packets (forwarding not implemented yet)")
            while (running) {
                try {
                    val len = input.read(buffer)
                    if (len > 0) {
                        // TODO next step: parse IP header, open/forward the
                        // connection via wlan1 (protect() + the uid ip-rule
                        // trick), write replies back to tunFd.
                    }
                } catch (e: Exception) {
                    if (running) Log.e("wlan1vpn", "read loop error", e)
                    break
                }
            }
        }.start()
    }

    override fun onDestroy() {
        running = false
        tunFd?.close()
        tunFd = null
        super.onDestroy()
    }
}
