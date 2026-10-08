package hev.htproxy

/**
 * JNI shim matching hev-socks5-tunnel's expected package/class name
 * (hev.htproxy.TProxyService) - the prebuilt .so's JNI method table is
 * compiled against this exact name, so it can't be renamed/moved.
 */
class TProxyService {
    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        @JvmStatic
        external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        external fun TProxyStopService()

        @JvmStatic
        external fun TProxyGetStats(): LongArray
    }
}
