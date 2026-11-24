package local.udp.esp.vpn;
import org.json.JSONObject;
/** Process-local latest snapshot. No HTTP server or filesystem polling. */
final class LiveRuntime {
    static volatile JSONObject snapshot;
    static volatile String status="尚未启动 VPN";
    static volatile boolean running;
    static volatile long queuedDrops,decodeMs;
    private LiveRuntime() {}
}