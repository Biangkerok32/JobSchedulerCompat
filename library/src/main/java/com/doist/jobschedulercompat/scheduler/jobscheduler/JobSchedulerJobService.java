package com.doist.jobschedulercompat.scheduler.jobscheduler;

import com.doist.jobschedulercompat.JobInfo;
import com.doist.jobschedulercompat.JobParameters;
import com.doist.jobschedulercompat.JobScheduler;
import com.doist.jobschedulercompat.JobService;
import com.doist.jobschedulercompat.PersistableBundle;
import com.doist.jobschedulercompat.job.JobStatus;

import android.annotation.TargetApi;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.RestrictTo;
import android.util.Log;
import android.util.SparseArray;

/**
 * Job service for {@link JobSchedulerSchedulerV21}, the {@link android.app.job.JobScheduler}-based scheduler.
 *
 * This service runs whenever {@link android.app.job.JobScheduler} starts it based on the current jobs and constraints.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RestrictTo(RestrictTo.Scope.LIBRARY)
public class JobSchedulerJobService extends android.app.job.JobService implements JobService.Binder.Callback {
    private static final String LOG_TAG = "PlatformJobService";

    protected JobScheduler jobScheduler;
    private final SparseArray<Connection> connections = new SparseArray<>();

    @Override
    public void onCreate() {
        super.onCreate();
        jobScheduler = JobScheduler.get(this);
    }

    @Override
    public boolean onStartJob(android.app.job.JobParameters params) {
        startJob(params);
        return true;
    }

    @Override
    public boolean onStopJob(android.app.job.JobParameters params) {
        Connection connection = connections.get(params.getJobId());
        if (connection != null) {
            JobService.Binder binder = connection.binder;
            boolean needsReschedule = binder != null && binder.stopJob(toLocalParameters(connection.params));
            stopJob(connection, needsReschedule);
            return needsReschedule;
        } else {
            return false;
        }
    }

    @Override
    public void jobFinished(JobParameters params, boolean needsReschedule) {
        Connection connection = connections.get(params.getJobId());
        if (connection != null) {
            stopJob(connection, needsReschedule);
        }
    }

    /**
     * Starts the user's {@link JobService} by binding to it.
     */
    private void startJob(android.app.job.JobParameters params) {
        int jobId = params.getJobId();
        JobStatus jobStatus = jobScheduler.getJob(jobId);
        if (jobStatus != null) {
            Connection connection = new Connection(jobId, params);
            Intent jobIntent = new Intent();
            ComponentName service = jobStatus.getServiceComponent();
            jobIntent.setComponent(service);
            if (bindService(jobIntent, connection, BIND_AUTO_CREATE)) {
                connections.put(jobId, connection);
            } else {
                Log.w(LOG_TAG, "Unable to bind to service: " + service + ". Have you declared it in the manifest?");
                stopJob(connection, true);
            }
        }
    }

    /**
     * Stops the user's {@link android.app.job.JobService} by unbinding from it and passing the result to the platform.
     */
    private void stopJob(Connection connection, boolean needsReschedule) {
        connections.remove(connection.jobId);
        try {
            unbindService(connection);
        } catch (IllegalArgumentException e) {
            // Service not registered at this point. Drop it.
        }
        jobFinished(connection.params, needsReschedule);
        jobScheduler.onJobCompleted(connection.jobId, needsReschedule);
    }

    private JobParameters toLocalParameters(android.app.job.JobParameters params) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new JobParameters(
                    params.getJobId(), new PersistableBundle(params.getExtras()), params.getTransientExtras(),
                    params.isOverrideDeadlineExpired(), params.getTriggeredContentUris(),
                    params.getTriggeredContentAuthorities());
        } else {
            JobInfo job = jobScheduler.getJob(params.getJobId()).getJob();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return new JobParameters(
                        params.getJobId(), new PersistableBundle(params.getExtras()), job.getTransientExtras(),
                        params.isOverrideDeadlineExpired(), params.getTriggeredContentUris(),
                        params.getTriggeredContentAuthorities());
            } else {
                return new JobParameters(
                        params.getJobId(), new PersistableBundle(params.getExtras()), job.getTransientExtras(),
                        params.isOverrideDeadlineExpired(), null, null);
            }
        }
    }

    /**
     * {@link ServiceConnection} to the user's {@link JobService} that starts jobs when connected.
     */
    private class Connection implements ServiceConnection {
        private final int jobId;
        private final android.app.job.JobParameters params;
        private JobService.Binder binder;

        private Connection(int jobId, android.app.job.JobParameters params) {
            this.jobId = jobId;
            this.params = params;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!(service instanceof JobService.Binder)) {
                Log.w(LOG_TAG, "Unknown service connected: " + service);
                stopJob(this, false);
                return;
            }
            binder = (JobService.Binder) service;
            if (!binder.startJob(toLocalParameters(params), JobSchedulerJobService.this)) {
                stopJob(this, false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Should never happen as it's the same process.
            binder = null;
            if (connections.get(jobId) == this) {
                stopJob(this, false);
            }
        }
    }
}
