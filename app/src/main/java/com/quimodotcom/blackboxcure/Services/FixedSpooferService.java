package com.quimodotcom.blackboxcure.Services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;

import org.osmdroid.util.GeoPoint;

import java.util.concurrent.ThreadLocalRandom;

import com.quimodotcom.blackboxcure.AppPreferences;
import com.quimodotcom.blackboxcure.FusedLocationsProvider;
import com.quimodotcom.blackboxcure.ListickApp;
import com.quimodotcom.blackboxcure.MainServiceControl;
import com.quimodotcom.blackboxcure.MockLocProvider;
import com.quimodotcom.blackboxcure.PermissionManager;
import com.quimodotcom.blackboxcure.Presenter.RouteSettingsPresenter;
import com.quimodotcom.blackboxcure.Randomizer;

public class FixedSpooferService extends Service {

    public static final float ACCURACY_DIFF = 5;

    private FusedLocationsProvider mFusedLocationProvider;
    private Randomizer  mRandomizer;
    private Handler     mHandler;

    private int   mUpdatesDelay;
    private int   mUpdatesDrift;
    private float mBearing;
    private float mAccuracy;
    private float mElevation;
    private float mElevationDiff;

    private double mLatitude;
    private double mLongitude;

    private boolean mDeviation;
    private boolean isMockLocationsEnabled;
    private boolean isSystemApp;

    private BroadcastReceiver mUpdateService;

    // ── GPS-Positions-Drift ───────────────────────────────────────
    private double anchorLat = Double.NaN;
    private double anchorLon = Double.NaN;
    private double driftLat  = 0.0;
    private double driftLon  = 0.0;

    private static final double MAX_DRIFT_METERS = 5.0;
    private static final double METERS_TO_DEG    = 1.0 / 111320.0;
    private static final double MAX_DRIFT_DEG    = MAX_DRIFT_METERS * METERS_TO_DEG;
    private static final double DRIFT_STEP_DEG   = 0.5 * METERS_TO_DEG;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MainServiceControl.startServiceForeground(this);

        mHandler               = new Handler();
        mRandomizer            = new Randomizer();
        mFusedLocationProvider = new FusedLocationsProvider(this);
        isMockLocationsEnabled = PermissionManager.isMockLocationsEnabled(this);
        isSystemApp            = PermissionManager.isSystemApp(this);
        MockLocProvider.initTestProvider();

        mBearing       = (float) ThreadLocalRandom.current().nextDouble(10, 180);
        mAccuracy      = AppPreferences.getAccuracy(this);
        mUpdatesDelay  = AppPreferences.getUpdatesDelay(this);
        mUpdatesDrift  = AppPreferences.getUpdatesDrift(this);
        mDeviation     = AppPreferences.getLocationError(this);

        mLatitude      = intent.getDoubleExtra(ListickApp.LATITUDE, 0d);
        mLongitude     = intent.getDoubleExtra(ListickApp.LONGITUDE, 0d);
        mElevation     = intent.getFloatExtra(RouteSettingsPresenter.ELEVATION, 197);
        mElevationDiff = intent.getFloatExtra(RouteSettingsPresenter.ELEVATION_DIFF, 2);

        resetAnchor(mLatitude, mLongitude);

        if (mainStaticThread.getState() == Thread.State.NEW) {
            mainStaticThread.start();
        } else {
            mainStaticThread.interrupt();
            mHandler.removeCallbacks(mainStaticRunnable);
        }

        mUpdateService = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle data = intent.getExtras();
                if (data == null) return;
                double newLat = data.getDouble(ListickApp.LATITUDE, mLatitude);
                double newLon = data.getDouble(ListickApp.LONGITUDE, mLongitude);
                if (newLat != mLatitude || newLon != mLongitude) resetAnchor(newLat, newLon);
                mLatitude  = newLat;
                mLongitude = newLon;
            }
        };
        registerReceiver(mUpdateService, new IntentFilter(MainServiceControl.SERVICE_CONTROL_ACTION));
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mainStaticThread.interrupt();
        if (mHandler != null) mHandler.removeCallbacks(mainStaticRunnable);
        stopForeground(true);
        try { unregisterReceiver(mUpdateService); }
        catch (IllegalArgumentException e) {
            android.util.Log.d(com.quimodotcom.blackboxcure.BuildConfig.APPLICATION_ID, null, e);
        }
        MockLocProvider.removeProviders();
    }

    private void resetAnchor(double lat, double lon) {
        anchorLat = lat; anchorLon = lon;
        driftLat  = 0.0; driftLon  = 0.0;
    }

    private GeoPoint applyDrift() {
        driftLat += ThreadLocalRandom.current().nextDouble(-DRIFT_STEP_DEG, DRIFT_STEP_DEG);
        driftLon += ThreadLocalRandom.current().nextDouble(-DRIFT_STEP_DEG, DRIFT_STEP_DEG);
        double dist = Math.sqrt(driftLat * driftLat + driftLon * driftLon);
        if (dist > MAX_DRIFT_DEG) { driftLat -= driftLat * 0.15; driftLon -= driftLon * 0.15; }
        return new GeoPoint(anchorLat + driftLat, anchorLon + driftLon);
    }

    Runnable mainStaticRunnable = new Runnable() {
        @Override
        public void run() {
            GeoPoint spoofPoint = mDeviation ? applyDrift() : new GeoPoint(mLatitude, mLongitude);
            float rElevation = mRandomizer.getElevation(mElevation, mElevationDiff);
            float rAccuracy  = mRandomizer.getAccuracy(mAccuracy, ACCURACY_DIFF);
            float rBearing   = mRandomizer.getBearing(mBearing, 3);
            float rSpeed     = mRandomizer.getStaticSpeed(0, 0.2f);
            setMockLocation(spoofPoint, rAccuracy, rElevation, rBearing, rSpeed);

            long lo = Math.max(50, mUpdatesDelay - mUpdatesDrift);
            long hi = mUpdatesDelay + mUpdatesDrift;
            mHandler.postDelayed(this, ThreadLocalRandom.current().nextLong(lo, hi + 1));
        }

        private void setMockLocation(GeoPoint location, float accuracy, float elevation, float bearing, float speed) {
            if (isMockLocationsEnabled) {
                MockLocProvider.setNetworkProvider(location.getLatitude(), location.getLongitude(), accuracy, bearing, elevation);
                MockLocProvider.setGpsProvider(location.getLatitude(), location.getLongitude(), bearing, speed, accuracy, elevation);
                Location fusedLocation = mFusedLocationProvider.build(location.getLatitude(), location.getLongitude(), accuracy, bearing, speed, elevation);
                mFusedLocationProvider.spoof(fusedLocation);
            } else if (isSystemApp) {
                MockLocProvider.reportLocation(location.getLatitude(), location.getLongitude(), accuracy, bearing, speed, elevation);
            }
        }
    };

    Thread mainStaticThread = new Thread(mainStaticRunnable);
}
