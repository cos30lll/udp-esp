package local.udp.esp.vpn;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler ui=new Handler();private TextView status;
    private final Runnable refresh=new Runnable(){public void run(){status.setText(LiveRuntime.status);ui.postDelayed(this,500);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(18*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        TextView title=new TextView(this);title.setTextSize(23);title.setText("纯 UDP ESP · VPN 免 root 候选");layout.addView(title);
        TextView info=new TextView(this);info.setTextSize(16);
        info.setText("独立免 root APK：本机 VPN 转发游戏流量，内置 UDP 解码与方框、射线、HP。无需 Python 后端、tcpdump、ROOT 或填写相机参数。\n\n仅接管超自然行动 com.pi.czrxdfirst，不设置远程 VPN 服务器、不解密 TLS。Android 同时只允许一个 VPN，需停用其他 VPN/加速器。\n\n先启动 VPN，再由你进入或重新连接游戏以收齐登录身份。相机和方框沿用 r4 的近似规则；本版网络转发与显示尚待实机验收。");layout.addView(info);
        button(layout,"授予悬浮窗权限",()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        if(Build.VERSION.SDK_INT>=33)button(layout,"允许运行通知（可选）",()->requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12));
        button(layout,"启动 VPN + ESP",()->{
            if(!Settings.canDrawOverlays(this)){status.setText("先授予悬浮窗权限");return;}
            Intent prepare=VpnService.prepare(this);if(prepare!=null)startActivityForResult(prepare,101);else startVpn();
        });
        button(layout,"停止 VPN + ESP",()->{
            stopService(new Intent(this,GameVpnService.class));stopService(new Intent(this,OverlayService.class));
            status.setText("已请求停止 VPN 和悬浮层");
        });
        status=new TextView(this);layout.addView(status);
    }
    private void button(LinearLayout parent,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setOnClickListener(v->action.run());parent.addView(b);}
    private void startVpn(){try{startForegroundService(new Intent(this,GameVpnService.class).setAction(GameVpnService.START));}catch(Exception error){status.setText(error.toString());}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==101&&result==RESULT_OK)startVpn();}
    @Override protected void onResume(){super.onResume();ui.post(refresh);}
    @Override protected void onPause(){ui.removeCallbacks(refresh);super.onPause();}
}