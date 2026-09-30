package com.smshub.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class SyncJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> { boolean complete = SyncEngine.sync(getApplicationContext()); jobFinished(params, !complete); }, "sms-sync-job").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
