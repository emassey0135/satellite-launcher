package dev.emassey0135.communitylauncher;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Carries on with setup as soon as the device has started again after setup restarted it. Android runs jobs kept across
 * a restart as soon as the device is unlocked, which on the Orbit is straight after it starts, while its "device has
 * started" announcement ({@link BootReceiver}) reaches apps one after another and can take a minute more. Until setup
 * has made it the home app, nothing else starts the launcher.
 */
public final class SetupResumeJob extends JobService {
    private static final int ID = 1;
    /**
     * Longer than setup takes to ask for the restart and the device to go down, so the job runs after the restart. If it
     * ran before, it would do nothing, as the device hasn't restarted yet.
     */
    private static final long DELAY_MS = 30_000;

    /** Schedule the job, just before setup restarts the device. */
    static void schedule(Context context) {
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(ID, new ComponentName(context, SetupResumeJob.class))
                        .setPersisted(true)
                        .setMinimumLatency(DELAY_MS)
                        .build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        SetupService.resumeIfRestarted(this);
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) { return false; }
}
