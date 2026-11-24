package local.udp.esp;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Zero calibration UI. Starts display only, never a capture or a game. */
public final class MainActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(18 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad,pad,pad,pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(layout); setContentView(scroll);
        TextView title = new TextView(this); title.setTextSize(23);
        title.setText("纯 UDP ESP · 方框 / 射线 / HP"); layout.addView(title);
        TextView info = new TextView(this); info.setTextSize(16);
        info.setText("不需要填写 FOV、眼高、距离或分辨率。固定版本 1.35.20.001 / 1201 的配置已内置；坐标、视角和身份只取现有被动 UDP 后端。\n\n方框、底部射线和 HP 默认显示。方框为固定站姿尺寸近似；HP 只取收到的 UDP 上报，未收到显示未知，不假定最大血量。当前使用基础 FOV 和静态模型规则近似投影；碰撞、动画、变焦及最终镜头尚未完整恢复。免填参不代表精确镜头已验收。\n\n仅连接本机后端；不会启动游戏、后端或抓包，不读游戏内存，不截屏，不操作触摸。");
        layout.addView(info);
        Button permission = new Button(this); permission.setText("授予悬浮窗权限");
        permission.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())))); layout.addView(permission);
        if (Build.VERSION.SDK_INT >= 33) {
            Button notify = new Button(this); notify.setText("允许运行通知（可选）");
            notify.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12)); layout.addView(notify);
        }
        Button start = new Button(this); start.setText("启动免填参 ESP");
        start.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) { status.setText("请先授予悬浮窗权限。"); return; }
            try {
                Intent intent = new Intent(this,OverlayService.class).setAction(OverlayService.ACTION_START);
                intent.putExtra("config","{}"); startForegroundService(intent);
                status.setText("已请求启动显示层；请查看通知和悬浮文字。后端须由你按原流程运行。");
            } catch (Exception error) { status.setText("未启动："+error.getMessage()); }
        }); layout.addView(start);
        Button stop = new Button(this); stop.setText("停止 ESP");
        stop.setOnClickListener(v -> { stopService(new Intent(this,OverlayService.class)); status.setText("已请求移除悬浮层；UDP 后端不受影响。"); }); layout.addView(stop);
        status = new TextView(this); status.setText("尚未启动。没有相机参数输入项。"); layout.addView(status);
    }
}