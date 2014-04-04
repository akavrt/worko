package com.akavrt.ups.service;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.preference.PreferenceManager;
import android.util.Log;

import com.akavrt.ups.R;
import com.akavrt.ups.SettingsActivity;
import com.akavrt.ups.events.PullUpEvent;
import com.akavrt.ups.events.PullUpWorkerEvent;
import com.akavrt.ups.events.PullUpsAdjustEvent;
import com.akavrt.ups.sensor.CompatSensorHelper;
import com.akavrt.ups.sensor.SensorHelper;
import com.akavrt.ups.utils.BusProvider;
import com.akavrt.ups.utils.Constants;
import com.squareup.otto.Produce;
import com.squareup.otto.Subscribe;

/**
 * @author Victor Balabanov <akavrt@gmail.com>
 */
public class CountingService extends Service {
    private static final String TAG = CountingService.class.getName();
    private static final String THREAD_NAME = "WorkerThread";
    private static boolean isRunning;
    // helpers
    private SensorHelper mSensorHelper;
    private Notificator mNotificator;
    private int mPullUpsCounter;
    private PowerManager.WakeLock mWakeLock;
    private MediaPlayer mPlayer;
    // threading
    private HandlerThread mWorkerThread;
    private Handler mWorkerHandler;
    private Handler mUIHandler = new Handler(Looper.getMainLooper()) {

        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG, "handleMessage(), thread = " + checkThread());

            handlePullUp();
        }
    };

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate(), thread = " + checkThread() + ", isRunning = " + isRunning);

        isRunning = true;

        // acquire power lock
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.acquire();

        mNotificator = new Notificator(this);
        startForeground(mNotificator.getId(), mNotificator.build());

        mPlayer = preparePlayer();
        mSensorHelper = prepareSensorHelper();

        prepareWorkerThread();

        BusProvider.getWorkerInstance().register(this);
        BusProvider.getInstance().register(this);
    }

    private void prepareWorkerThread() {
        mWorkerThread = new HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_BACKGROUND);
        mWorkerThread.start();

        Looper serviceLooper = mWorkerThread.getLooper();
        mWorkerHandler = new Handler(serviceLooper);
    }

    private MediaPlayer preparePlayer() {
        MediaPlayer player = MediaPlayer.create(this, R.raw.beep);

        if (player != null) {
            player.setLooping(false);
        }

        return player;
    }

    private SensorHelper prepareSensorHelper() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isLoggingEnabled = sharedPrefs.getBoolean(
                Constants.Settings.IS_LOGGING_ENABLED_KEY, false);

        return new CompatSensorHelper(this, isLoggingEnabled);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand(), thread = " + checkThread());
        logIntent(intent);

        mSensorHelper.register(SensorManager.SENSOR_DELAY_GAME, mWorkerHandler);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy(), thread = " + checkThread());

        BusProvider.getWorkerInstance().unregister(this);
        BusProvider.getInstance().unregister(this);

        mSensorHelper.unregister();

        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }

        mWorkerThread.quit();
        mWakeLock.release();

        stopForeground(true);

        isRunning = false;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static boolean isRunning() {
        return isRunning;
    }

    @Subscribe
    public void onPullUpDetected(PullUpWorkerEvent event) {
        Log.d(TAG, "onPullUpDetected(), thread = " + checkThread());

        // redirect this event to the main thread
        mUIHandler.sendEmptyMessage(0);
    }

    private void handlePullUp() {
        mPullUpsCounter++;
        BusProvider.getInstance().post(new PullUpEvent(mPullUpsCounter));

        if (mPlayer != null) {
            mPlayer.start();
        }
    }

    @Subscribe
    public void onPullUpsAdjusted(PullUpsAdjustEvent event) {
        Log.d(TAG, "onPullUpsAdjusted(), thread = " + checkThread());

        if (mPullUpsCounter + event.delta >= 0) {
            mPullUpsCounter += event.delta;
            BusProvider.getInstance().post(new PullUpEvent(mPullUpsCounter));
        }
    }

    @Produce
    public PullUpEvent produceOnPullUpDetectedEvent() {
        Log.d(TAG, "produceOnPullUpDetectedEvent(), thread = " + checkThread());
        return new PullUpEvent(mPullUpsCounter);
    }

    private static void logIntent(Intent intent) {
        if (intent == null) {
            Log.d(TAG, "onStartCommand(), service was recreated.");
        } else {
            Log.d(TAG, "onStartCommand(), intent: " + intent);

            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                for (String key : bundle.keySet()) {
                    Object value = bundle.get(key);
                    Log.d(TAG, String.format("%s %s (%s)", key, value.toString(),
                            value.getClass().getName()));
                }
            }
        }
    }

    private static String checkThread() {
        return Thread.currentThread().getName();
    }
}
