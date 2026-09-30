package com.smshub.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

final class SyncEngine {
    private static final int RETRY = 10, HEARTBEAT = 11;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    static void schedule(Context context) {
        Config config = new Config(context);
        if (!config.enabled()) return;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        ComponentName service = new ComponentName(context, SyncJobService.class);
        scheduler.schedule(new JobInfo.Builder(RETRY, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setMinimumLatency(1000).setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
        if (scheduler.getPendingJob(HEARTBEAT) == null) scheduler.schedule(new JobInfo.Builder(HEARTBEAT, service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60000L).build());
    }
    static void cancel(Context context) { JobScheduler scheduler = context.getSystemService(JobScheduler.class); scheduler.cancel(RETRY); scheduler.cancel(HEARTBEAT); }
    static boolean sync(Context context) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        Config config = new Config(context);
        try (PendingMessages queue = new PendingMessages(context)) {
            if (!config.enabled()) return true;
            JSONArray messages = queue.batch();
            JSONObject result = Network.post(config.url(), "/api/device/sync", config.token(), new JSONObject().put("messages", messages).put("details", config.details()));
            queue.acknowledge(result.getJSONArray("acknowledged"));
            config.prefs.edit().putLong("lastSync", System.currentTimeMillis()).putString("error", "").apply();
            return queue.count() == 0;
        } catch (Network.ApiException error) {
            config.error(error.getMessage());
            if (error.status == 401) { config.prefs.edit().putBoolean("enabled", false).commit(); cancel(context); return true; }
            return false;
        } catch (Exception error) { config.error("连接未成功，联网后会重试。请检查服务器地址、网络和手机时间。"); return false; }
        finally { RUNNING.set(false); }
    }
    static void sendState(Context context) {
        Config config = new Config(context);
        if (!config.paired()) return;
        try {
            Network.post(config.url(), "/api/device/sync", config.token(), new JSONObject().put("messages", new JSONArray()).put("details", config.details()));
            config.prefs.edit().putLong("lastSync", System.currentTimeMillis()).putString("error", "").apply();
        }
        catch (Exception ignored) { config.error("状态尚未同步到网页，下次连接后更新。"); }
    }
}
