package local.udp.esp.vpn;

final class VpnNative {
    static { System.loadLibrary("udpespvpn"); }
    static native long create();
    static native int run(long handle,int tunFd,Object callbacks);
    static native void stop(long handle);
    static native void destroy(long handle);
    static native byte[] decompress(byte[] compressed);
    private VpnNative() {}
}