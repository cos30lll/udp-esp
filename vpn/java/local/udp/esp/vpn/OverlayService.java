package local.udp.esp.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Display only. Single latest-state worker, no per-frame JSON work on UI thread. */
public final class OverlayService extends Service {
    public static final String ACTION_START = "local.udp.esp.vpn.OVERLAY_START";
    public static final String ACTION_STOP = "local.udp.esp.vpn.OVERLAY_STOP";
    private static final String CHANNEL = "udp_projection_status";
    private static final int NOTIFICATION_ID = 851;
    
    private static final long TRANSIENT_GRACE_MS = 300;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> poll;
    
    private volatile int drawWidth, drawHeight;
    private volatile boolean destroyed;
    private WindowManager windows;
    private OverlayView overlay;
    private JSONObject config;
    private Projection.Result frame;
    private long frameExpiresAt, lastResponseAt;
    private String failure = "等待本机 VPN 数据";
    private String timing = "";
    private boolean transientFailure;
    private int generation;
    private final Object deliveryLock = new Object();
    private Delivery pendingDelivery;
    private boolean deliveryPosted;
    private static final class Delivery {
        final int token;
        final Projection.Result result;
        final long receivedAt, validUntil;
        final String error;
        Delivery(int token, Projection.Result result, long receivedAt, long validUntil, String error) {
            this.token=token; this.result=result; this.receivedAt=receivedAt;
            this.validUntil=validUntil; this.error=error;
        }
    }
    private final Runnable deliverLatest = new Runnable() {
        @Override public void run() {
            Delivery update;
            synchronized (deliveryLock) {
                update=pendingDelivery; pendingDelivery=null; deliveryPosted=false;
            }
            if (update==null || destroyed || generation!=update.token) return;
            if (update.result!=null) {
                frame=update.result.drawable ? update.result : null;
                frameExpiresAt=update.validUntil; lastResponseAt=update.receivedAt;
                transientFailure=false; failure=update.result.reason; timing=update.result.timing;
            } else {
                transientFailure=true; failure=update.error;
                if (SystemClock.elapsedRealtime()-lastResponseAt>=TRANSIENT_GRACE_MS) frame=null;
            }
            if (overlay!=null) overlay.postInvalidateOnAnimation();
        }
    };
    private void deliver(Delivery update) {
        synchronized (deliveryLock) {
            pendingDelivery=update;
            if (deliveryPosted) return;
            deliveryPosted=true;
        }
        main.post(deliverLatest);
    }

    // Only a freshness watchdog. New snapshots draw immediately on the next vsync.
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (destroyed || overlay == null) return;
            if (!Settings.canDrawOverlays(OverlayService.this)) { stopSelf(); return; }
            long now = SystemClock.elapsedRealtime();
            if (frame != null && (now >= frameExpiresAt ||
                    (transientFailure && now - lastResponseAt >= TRANSIENT_GRACE_MS))) {
                frame = null;
                if (!transientFailure) failure = "UDP 视角或场景数据已超时 · 暂停绘制";
                overlay.postInvalidateOnAnimation();
            }
            main.postDelayed(this, 100);
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopService(new Intent(this,GameVpnService.class));
            stopSelf(); return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        try {
            JSONObject next = OverlayConfig.validate(intent.getStringExtra("config"));
            if (!Settings.canDrawOverlays(this)) throw new IllegalStateException("缺少悬浮窗权限");
            showForeground();
            cancelPolling();
            config = next; failure = "等待本机 VPN 数据";
            if (overlay == null) addOverlay();
            overlay.resetViewport();
            main.removeCallbacks(watchdog); main.post(watchdog);
            final int token = generation;
            // No overlap, no catch-up queue. Each request returns the newest state.
            poll = network.scheduleWithFixedDelay(() -> fetch(token, next), 0, 16, TimeUnit.MILLISECONDS);
        } catch (Exception error) { stopSelf(); }
        return START_NOT_STICKY;
    }

    private void showForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "UDP 投影运行状态", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("UDP 免填参 ESP · VPN r1")
                .setContentText("被动 UDP；近似镜头。点击停止可移除。")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止悬浮层", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else startForeground(NOTIFICATION_ID, notification);
    }

    private void addOverlay() {
        windows = getSystemService(WindowManager.class);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0; params.y = 0; params.alpha = 0.70f;
        if (Build.VERSION.SDK_INT >= 31) {
            InputManager input = getSystemService(InputManager.class);
            if (input == null) throw new IllegalStateException("无法确认触摸穿透透明度");
            float limit = input.getMaximumObscuringOpacityForTouch();
            if (Float.isNaN(limit) || limit <= 0) throw new IllegalStateException("系统不允许透明触摸穿透");
            params.alpha = Math.min(params.alpha, Math.max(0, limit - 0.01f));
        }
        if (Build.VERSION.SDK_INT >= 30) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        OverlayView next = new OverlayView();
        windows.addView(next, params); overlay = next;
    }

    private void fetch(final int token, final JSONObject settings) {
        try {
            int width=drawWidth,height=drawHeight;
            if(destroyed||width<64||height<64)return;
            JSONObject snapshot=LiveRuntime.snapshot;
            if(snapshot==null){deliver(new Delivery(token,null,0,0,LiveRuntime.status));return;}
            double epochNow=System.currentTimeMillis()/1000.0;long receivedAt=SystemClock.elapsedRealtime();
            Projection.Result result=Projection.project(snapshot,settings,epochNow,width,height);
            long projectMs=SystemClock.elapsedRealtime()-receivedAt;
            JSONObject transport=snapshot.optJSONObject("transport");
            long age=Double.isNaN(result.cameraAgeMs)?-1:Math.round(result.cameraAgeMs);
            result.timing="VPN本机 · 视角龄 "+age+"ms /解码 "+LiveRuntime.decodeMs+"ms /投影 "+projectMs+"ms /丢分析 "+LiveRuntime.queuedDrops;
            if(!result.drawable)result.reason += " · "+LiveRuntime.status;
            long validUntil=receivedAt+(long)Math.max(0,(result.expiresAt-epochNow)*1000);
            deliver(new Delivery(token,result,receivedAt,validUntil,null));
        } catch(Exception error) {
            deliver(new Delivery(token,null,0,0,"VPN 投影异常："+error.getMessage()));
        }
    }

    private void cancelPolling() {
        generation++;
        synchronized (deliveryLock) { pendingDelivery=null; }
        if (poll != null) { poll.cancel(true); poll = null; }
        frame = null; transientFailure = false; lastResponseAt = frameExpiresAt = 0;
    }

    @Override public void onDestroy() {
        if(LiveRuntime.running)stopService(new Intent(this,GameVpnService.class));
        destroyed = true; cancelPolling(); main.removeCallbacksAndMessages(null); network.shutdownNow();
        if (overlay != null && windows != null) {
            try { windows.removeViewImmediate(overlay); } catch (IllegalArgumentException ignored) {}
        }
        overlay = null; config = null; frame = null; stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private final class OverlayView extends View {
        private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tracer = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hpText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        OverlayView() {
            super(OverlayService.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            marker.setColor(Color.CYAN); marker.setStrokeWidth(2); marker.setStyle(Paint.Style.STROKE);
            label.setColor(Color.WHITE); label.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
            label.setShadowLayer(3, 1, 1, Color.BLACK); shadow.setColor(Color.argb(150, 0, 0, 0));
            float density=getResources().getDisplayMetrics().density;
            marker.setStrokeWidth(Math.max(1.5f,density));
            tracer.setColor(Color.argb(175,255,235,100));tracer.setStrokeWidth(Math.max(1,density*0.7f));
            hpText.setTextSize(label.getTextSize());hpText.setShadowLayer(3,1,1,Color.BLACK);
        }
        void resetViewport() { frame = null; postInvalidateOnAnimation(); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            drawWidth = width; drawHeight = height; frame = null;
            postInvalidateOnAnimation();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Projection.Result result = frame;
            long now = SystemClock.elapsedRealtime();
            boolean usable = result != null && result.drawable && now < frameExpiresAt
                    && result.width == getWidth() && result.height == getHeight()
                    && (!transientFailure || now - lastResponseAt < TRANSIENT_GRACE_MS);
            String message = usable ? result.reason : failure;
            if (usable) {
                int save = canvas.save();
                canvas.clipRect(result.left, result.top, result.left + result.width, result.top + result.height);
                for (Projection.Point point : result.points) {
                    float footX=Math.max(result.left,Math.min(point.x,result.left+result.width));
                    float footY=Math.max(result.top,Math.min(point.y,result.top+result.height));
                    canvas.drawLine(result.left+result.width/2f,result.top+result.height-2,footX,footY,tracer);
                    canvas.drawRect(point.left,point.top,point.right,point.bottom,marker);
                    float x=Math.max(result.left+2,Math.min(point.left,
                            result.left+result.width-label.measureText(point.label)-2));
                    float y=point.top-8;
                    if(y<result.top+label.getTextSize())y=point.top+label.getTextSize()+2;
                    y=Math.min(y,result.top+result.height-3);
                    canvas.drawText(point.label,x,y,label);
                    hpText.setColor(!point.hpKnown?Color.LTGRAY:point.hpPositive?Color.GREEN:Color.RED);
                    float hpX=Math.max(result.left+2,Math.min(point.left,
                            result.left+result.width-hpText.measureText(point.hpLabel)-2));
                    float hpY=Math.min(result.top+result.height-3,y+hpText.getTextSize()+5);
                    if(hpY-y<hpText.getTextSize())hpY=Math.max(result.top+hpText.getTextSize(),y-hpText.getTextSize()-4);
                    canvas.drawText(point.hpLabel,hpX,hpY,hpText);
                }
                canvas.restoreToCount(save);
            }
            float y = Math.min(getHeight() - 8, 42 * getResources().getDisplayMetrics().density);
            canvas.drawRect(8, y - label.getTextSize() - 6,
                    Math.min(getWidth(), label.measureText(message) + 24), y + 6, shadow);
            canvas.drawText(message, 12, y, label);
            if (!timing.isEmpty()) {
                float second=Math.min(getHeight()-8,y+label.getTextSize()+12);
                canvas.drawRect(8,second-label.getTextSize()-4,
                        Math.min(getWidth(),label.measureText(timing)+24),second+5,shadow);
                canvas.drawText(timing,12,second,label);
            }
        }
    }
}