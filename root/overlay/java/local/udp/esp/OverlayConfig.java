package local.udp.esp;
import org.json.JSONException;
import org.json.JSONObject;
/** Fixed version profile, no user calibration or hidden manual fields. */
final class OverlayConfig {
    private OverlayConfig() {}
    static JSONObject validate(String ignored) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("mode","automatic");
        result.put("profile_id","ugc2_1201_fixed_1.35.20.001_zero_r1");
        return result;
    }
}
