package com.smshub.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Telephony;

/** User-visible SMS relay service; the user can pause it directly from its notification. */
public final class SmsMonitorService extends Service {
    private static final String CHANNEL = "sms-sync", PAUSE = "com.smshub.app.PAUSE";
    static volatile boolean running;
    private HandlerThread thread;
    private Handler worker;
    private ContentObserver observer;
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!new Config(SmsMonitorService.this).enabled()) { stopSelf(); return; }
            SyncEngine.sync(getApplicationContext(), false);
            worker.postDelayed(this, 30000);
        }
    };
    static void start(Context context) {
        Config config = new Config(context);
        if (!config.enabled() || (!config.smsPermission() && !(config.inboxEnabled() && config.inboxPermission()))) return;
        try { context.startForegroundService(new Intent(context, SmsMonitorService.class)); }
        catch (Exception error) { new SmsDiagnostics(config.prefs).failed("后台收码服务未能启动，请打开 App 重试", error); }
    }
    static void stop(Context context) { context.stopService(new Intent(context, SmsMonitorService.class)); }
    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("sms-inbox-monitor"); thread.start(); worker = new Handler(thread.getLooper());
        NotificationChannel channel = new NotificationChannel(CHANNEL, "后台收码", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示验证码同步正在运行，可从通知暂停");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Config config = new Config(this);
        if (intent != null && PAUSE.equals(intent.getAction())) {
            config.setEnabled(false); SyncEngine.cancel(this);
            new Thread(() -> SyncEngine.sendState(getApplicationContext()), "sms-pause-state").start();
            stopSelf(); return START_NOT_STICKY;
        }
        if (!config.enabled()) { stopSelf(); return START_NOT_STICKY; }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this, 1, new Intent(this, SmsMonitorService.class).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SMS Hub 正在后台收码")
            .setContentText(config.inboxEnabled() ? "兼容模式：检查新收到的验证码短信" : "等待新验证码短信，点击管理")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "暂停同步", pause).build()).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(20, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            else startForeground(20, notification);
        } catch (Exception error) {
            new SmsDiagnostics(config.prefs).failed("后台收码通知启动失败", error);
            stopSelf(); return START_NOT_STICKY;
        }
        if (!running) new SmsDiagnostics(config.prefs).record("后台收码服务已启动");
        running = true;
        if (observer != null) { getContentResolver().unregisterContentObserver(observer); observer = null; }
        if (config.inboxEnabled() && config.inboxPermission()) {
            observer = new ContentObserver(worker) {
                @Override public void onChange(boolean selfChange) { worker.removeCallbacks(check); worker.postDelayed(check, 500); }
            };
            try { getContentResolver().registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer); }
            catch (Exception error) { observer = null; new SmsDiagnostics(config.prefs).failed("收件箱通知未能注册，将定期补查", error); }
        }
        worker.removeCallbacks(check); worker.post(check);
        return START_STICKY;
    }
    @Override public void onDestroy() {
        running = false;
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        worker.removeCallbacksAndMessages(null); thread.quitSafely();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
