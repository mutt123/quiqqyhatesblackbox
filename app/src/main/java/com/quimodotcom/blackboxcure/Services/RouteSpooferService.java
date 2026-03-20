package com.quimodotcom.blackboxcure.Services;

import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.quimodotcom.blackboxcure.AppPreferences;
import com.quimodotcom.blackboxcure.FusedLocationsProvider;
import com.quimodotcom.blackboxcure.Geometry;
import com.quimodotcom.blackboxcure.ListickApp;
import com.quimodotcom.blackboxcure.LocationOperations;
import com.quimodotcom.blackboxcure.MainServiceControl;
import com.quimodotcom.blackboxcure.MockLocProvider;
import com.quimodotcom.blackboxcure.MultipleRoutesInfo;
import com.quimodotcom.blackboxcure.PermissionManager;
import com.quimodotcom.blackboxcure.Presenter.MapsPresenter;
import com.quimodotcom.blackboxcure.Presenter.RouteSettingsPresenter;
import com.quimodotcom.blackboxcure.Randomizer;
import com.quimodotcom.blackboxcure.RouteManager;
import com.quimodotcom.blackboxcure.SpoofingPlaceInfo;
import com.quimodotcom.blackboxcure.Services.ISpooferService;

public class RouteSpooferService extends Service {

    public static final String KEY_ACCURACY         = "accuracy";
    public static final String KEY_DEVIATION        = "deviation";
    public static final String KEY_UPDATES_DELAY    = "updates_delay";
    public static final String KEY_DEFAULT_UNIT     = "default_unit";
    public static final String KEY_BRAKE_AT_TURINING = "brake_at_turning";

    public static final String UI_SPEED_KEY        = "ui_speed_key";
    public static final String UI_PASSED_DISTANCE  = "ui_passed_distance";
    public static final String UI_TOTAL_DISTANCE   = "ui_total_distance";
    public static final String UI_ARRIVED          = "ui_arrived";
    public static final String UI_SPOOF_LAT        = "ui_spoof_lat";
    public static final String UI_SPOOF_LON        = "ui_spoof_lon";

    private FusedLocationsProvider mFusedLocationProvider;
    private Randomizer  mRandomizer;
    private GeoPoint    mCurrentStep;
    private Handler     mHandler;

    private volatile int mSpeed;
    private int mSpeedDiff;
    private int mDefaultUnit;
    private int mTrafficSide;
    private int mOriginDelay;
    private int mUpdatesDelay;
    private int mUpdatesDrift;   // ±Drift in ms, aus Settings

    private float mAccuracy;
    private float mElevation;
    private float mElevationDiff;
    private float mBearing;

    private double mTotalDistance;
    private double mPassedDistance;

    private boolean isClosedRoute     = false;
    private boolean mDeviation;
    private boolean mBrakeAtTurning;
    private boolean isMockLocationsEnabled;
    private boolean isSystemApp;
    private volatile boolean isPaused;
    private volatile boolean mSpeedManualOverride = false; // NEU
    private volatile int mBaseSpeed = 0;
    private boolean waitingStart;

    private Intent mUpdateUI;

    private BroadcastReceiver mOverlayControlReceiver;

    private int                        mRouteSlice  = 0;
    private ArrayList<GeoPoint>[]      mSlices;
    private ArrayList<Integer>[]       mSlicesSpeeds;
    private ArrayList<GeoPoint>        mSpoofRoute       = new ArrayList<>();
    private ArrayList<Integer>         mSpoofRouteSpeeds = new ArrayList<>();
    private ArrayList<MultipleRoutesInfo> mRoutes        = new ArrayList<>();

    private static class SourceData {
        static double  totalDistance;
        static boolean isClosedRoute;
    }

    private boolean mBound = false;
    private IBinder mCachedBinder = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        MainServiceControl.startServiceForeground(this);
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Wenn bereits gebunden (Route ändern-Fall): nur IBinder zurückgeben
        // KEIN initTestProvider() – das würde laufende Mock-Provider zerstören
        if (mBound) {
            return mCachedBinder;
        }
        mBound = true;

        mHandler               = new Handler();
        mRandomizer            = new Randomizer();
        mFusedLocationProvider = new FusedLocationsProvider(this);
        isMockLocationsEnabled = PermissionManager.isMockLocationsEnabled(this);
        isSystemApp            = PermissionManager.isSystemApp(this);
        MockLocProvider.initTestProvider();

        mOverlayControlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.hasExtra(MainServiceControl.KEY_PAUSE_TOGGLE)) {
                    isPaused = intent.getBooleanExtra(MainServiceControl.KEY_PAUSE_TOGGLE, false);
                    if (!isPaused) mainRouteRunnable.resetDrift();
                }
                //if (intent.hasExtra(MainServiceControl.KEY_SPEED_DELTA)) {
                //int delta = intent.getIntExtra(MainServiceControl.KEY_SPEED_DELTA, 0);
                //mSpeed = Math.max(1, mSpeed + delta);
                //arrayRunSpeed = mRandomizer.getArrayRunSpeed(mSpeed, mUpdatesDelay);
                //}

                if (intent.hasExtra(MainServiceControl.KEY_SPEED_DELTA)) {
                    int deltaKmh = intent.getIntExtra(MainServiceControl.KEY_SPEED_DELTA, 0);
                    int deltaInternal;
                    if (mDefaultUnit == AppPreferences.METERS) {
                        deltaInternal = (int) Geometry.Speed.metersToKilometers(deltaKmh);
                    } else if (mDefaultUnit == AppPreferences.MILES) {
                        deltaInternal = (int) Geometry.Speed.milesToKilometers(deltaKmh);
                    } else {
                        deltaInternal = deltaKmh;
                    }
                    mSpeed = Math.max(1, mSpeed + deltaInternal);
                    mBaseSpeed = mSpeed;           // ← NEU
                    mSpeedManualOverride = true;
                }


            }
        };
        //registerReceiver(mOverlayControlReceiver,
        //        new IntentFilter(MainServiceControl.ACTION_OVERLAY_CONTROL));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mOverlayControlReceiver,
                    new IntentFilter(MainServiceControl.ACTION_OVERLAY_CONTROL),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mOverlayControlReceiver,
                    new IntentFilter(MainServiceControl.ACTION_OVERLAY_CONTROL));
        }


        mAccuracy       = intent.getFloatExtra(KEY_ACCURACY, 10);
        mUpdatesDelay   = intent.getIntExtra(KEY_UPDATES_DELAY, 1000);
        mUpdatesDrift   = AppPreferences.getUpdatesDrift(this);
        mDeviation      = intent.getBooleanExtra(KEY_DEVIATION, true);
        mDefaultUnit    = intent.getIntExtra(KEY_DEFAULT_UNIT, AppPreferences.METERS);
        mBrakeAtTurning = intent.getBooleanExtra(KEY_BRAKE_AT_TURINING, true);

        mTotalDistance = intent.getDoubleExtra(ListickApp.DISTANCE, 0);
        mSpeed         = intent.getIntExtra(ListickApp.SPEED, 0);
        mBearing       = (float) ThreadLocalRandom.current().nextDouble(5, 180);
        mSpeedDiff     = intent.getIntExtra(RouteSettingsPresenter.SPEED_DIFF, 0);
        mTrafficSide   = intent.getIntExtra(AppPreferences.TRAFFIC_SIDE, AppPreferences.RIGHT_HAND_TRAFFIC);
        mElevation     = intent.getFloatExtra(RouteSettingsPresenter.ELEVATION, 197);
        mElevationDiff = intent.getFloatExtra(RouteSettingsPresenter.ELEVATION_DIFF, 4);

        mOriginDelay = intent.getIntExtra("origin_timeout", 0);
        waitingStart = mOriginDelay > 0;

        SourceData.totalDistance = mTotalDistance;
        SourceData.isClosedRoute = intent.getBooleanExtra(
                SpoofingPlaceInfo.CLOSED_ROUTE_MOTION_INVERT, false);

        cast();
        if (mSpeed <= 8) mBrakeAtTurning = false;

        Geometry.UnitCast casted = castAllUnits(mSpeed, mSpeedDiff);
        mSpeed     = casted.speed;
        mBaseSpeed = mSpeed;
        mSpeedDiff = casted.speedDiff;

        mUpdateUI = new Intent();
        mUpdateUI.setAction(MapsPresenter.UPDATE_UI_ACTION);
        mUpdateUI.putExtra(UI_TOTAL_DISTANCE, mTotalDistance);

        mCachedBinder = new ISpooferService.Stub() {

            @Override
            public void attachRoutes(List<MultipleRoutesInfo> routes) throws RemoteException {
                setRoute(routes, false);
                mCurrentStep = new GeoPoint(
                        mSpoofRoute.get(0).getLatitude(),
                        mSpoofRoute.get(0).getLongitude(),
                        mSpoofRoute.get(0).getAltitude());

                // Alten Runnable stoppen: stopped=true verhindert weiteres postDelayed
                // selbst wenn run() gerade mitten in der Ausführung ist
                mainRouteRunnable.stopped = true;
                mHandler.removeCallbacks(mainRouteRunnable);
                if (mainRouteThread.getState() != Thread.State.NEW
                        && mainRouteThread.getState() != Thread.State.TERMINATED) {
                    mainRouteThread.interrupt();
                    try { mainRouteThread.join(300); } catch (InterruptedException ignored) {}
                }

                // Service-State für neue Route zurücksetzen
                mPassedDistance = 0;
                mRouteSlice     = 0;

                // Neues Runnable + Thread erstellen
                mainRouteRunnable = new MainRouteRunnable();
                mainRouteThread   = new Thread(mainRouteRunnable);
                isPaused          = false;
                mainRouteThread.start();

                if (waitingStart)
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> waitingStart = false, mOriginDelay);
            }

            @Override
            public void setPause(boolean pause) throws RemoteException {
                if (!pause && isPaused) mainRouteRunnable.resetDrift();
                isPaused = pause;
            }

            @Override
            public boolean isPaused() throws RemoteException { return isPaused; }

            @Override
            public List<MultipleRoutesInfo> getRoutes() throws RemoteException { return mRoutes; }
        };
        return mCachedBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mBound = false;
        mCachedBinder = null;
        mainRouteThread.interrupt();
        if (mHandler != null && mainRouteRunnable != null)
            mHandler.removeCallbacks(mainRouteRunnable);
        if (mOverlayControlReceiver != null) {
            try { unregisterReceiver(mOverlayControlReceiver); }
            catch (IllegalArgumentException ignored) {}
        }
        stopForeground(true);
        MockLocProvider.removeProviders();
    }

    private Geometry.UnitCast castAllUnits(int speed, int speedDiff) {
        Geometry.UnitCast casted = new Geometry.UnitCast();
        if (mDefaultUnit == AppPreferences.METERS) {
            speed = (int) Geometry.Speed.metersToKilometers(speed);
            speedDiff = (int) Geometry.Speed.metersToKilometers(speedDiff);
        } else if (mDefaultUnit == AppPreferences.MILES) {
            speed = (int) Geometry.Speed.milesToKilometers(speed);
            speedDiff = (int) Geometry.Speed.milesToKilometers(speedDiff);
        }
        casted.speed = speed; casted.speedDiff = speedDiff;
        return casted;
    }

    private void cast() {
        if (mDefaultUnit == AppPreferences.KILOMETERS)
            mTotalDistance = Geometry.Distance.metersToKilometers(mTotalDistance);
        if (mDefaultUnit == AppPreferences.MILES)
            mTotalDistance = Geometry.Distance.metersToMiles(mTotalDistance);
    }

    private void setRoute(List<MultipleRoutesInfo> routes, boolean closedRoute) {
        mRoutes = (ArrayList<MultipleRoutesInfo>) routes;
        try {
            mSlices = new ArrayList[mRoutes.size()];
            mSlicesSpeeds = new ArrayList[mRoutes.size()];
            mRouteSlice = 0;
            for (int i = 0; i < mRoutes.size(); i++) {
                mSlices[i] = new ArrayList<>();
                MultipleRoutesInfo routeInfo = mRoutes.get(i);
                List<GeoPoint> points = routeInfo.getRoute();
                if (routeInfo.getFollowSpeedLimits() && routeInfo.getSpeedLimits() != null) {
                    mSlicesSpeeds[i] = new ArrayList<>();
                    RouteManager.startMotion(points, routeInfo.getSpeedLimits(), mSlices[i], mSlicesSpeeds[i], routeInfo.getSmoothTurns());
                } else {
                    RouteManager.startMotion(points, null, mSlices[i], null, routeInfo.getSmoothTurns());
                }
                if (isClosedRoute) {
                    isClosedRoute = false;
                    Collections.reverse(mSlices[i]);
                    if (mSlicesSpeeds[i] != null) Collections.reverse(mSlicesSpeeds[i]);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        mSpoofRoute = mSlices[mRouteSlice];
        mSpoofRouteSpeeds = (mSlicesSpeeds != null && mSlicesSpeeds.length > mRouteSlice)
                ? mSlicesSpeeds[mRouteSlice] : null;
    }

    private void updateUI(float speed, double distance) {
        mUpdateUI.putExtra(UI_SPEED_KEY, speed);
        mUpdateUI.putExtra(UI_PASSED_DISTANCE, distance);
        mUpdateUI.putExtra(UI_SPOOF_LAT, mCurrentStep != null ? mCurrentStep.getLatitude() : 0d);
        mUpdateUI.putExtra(UI_SPOOF_LON, mCurrentStep != null ? mCurrentStep.getLongitude() : 0d);
        // Route beendet wenn letzter Slice und am Ende angekommen
        boolean allSlicesDone = (mRouteSlice >= mSlices.length - 1);
        boolean atEnd = (mainRouteRunnable != null && mainRouteRunnable.isAtEnd());
        mUpdateUI.putExtra(UI_ARRIVED, allSlicesDone && atEnd);
        sendBroadcast(mUpdateUI);
        MainServiceControl.notifyOverlaySpeed(
                RouteSpooferService.this, (int) speed, mBaseSpeed, isPaused);
    }

    public static class FakeRouteInfo {
        boolean arrived;
        float   speed;
        double  altitude;
    }

    MainRouteRunnable mainRouteRunnable = new MainRouteRunnable();
    Thread mainRouteThread = new Thread(mainRouteRunnable);

    class MainRouteRunnable implements Runnable {

        /** Wird auf true gesetzt wenn attachRoutes() einen neuen Runnable erstellt.
         *  Verhindert dass der alte Runnable sich via postDelayed neu einreiht. */
        volatile boolean stopped = false;

        private int     arrayRunIndex = 0;
        private int     arrayRunSpeed;
        private int     brakeSpeed;
        private boolean isNeedBrake;

        // ── GPS-Positions-Drift ───────────────────────────────────
        private double driftLat  = 0.0;
        private double driftLon  = 0.0;
        private double anchorLat = Double.NaN;
        private double anchorLon = Double.NaN;

        private static final double MAX_DRIFT_METERS = 5.0;
        private static final double METERS_TO_DEG    = 1.0 / 111320.0;
        private static final double MAX_DRIFT_DEG    = MAX_DRIFT_METERS * METERS_TO_DEG;
        private static final double DRIFT_STEP_DEG   = 0.5 * METERS_TO_DEG;

        @Override
        public void run() {
            if (mSpoofRoute == null || mSpoofRoute.isEmpty()) return;

            float rSpeed;
            if (mSpoofRouteSpeeds != null && arrayRunIndex >= 0 && arrayRunIndex < mSpoofRouteSpeeds.size()) {
                rSpeed = mSpoofRouteSpeeds.get(arrayRunIndex);
            } else {
                rSpeed = mRandomizer.getRandomSpeed(mSpeed, mSpeedDiff);
                if (mSpeedDiff != 0) rSpeed += Math.random() * mSpeedDiff;
            }

            float rElevation = (float) mCurrentStep.getAltitude();
            float rAccuracy  = mRandomizer.getAccuracy(mAccuracy);
            if (isNeedBrake) rSpeed = brakeSpeed;

            arrayRunSpeed = mRandomizer.getArrayRunSpeed((int) rSpeed, mUpdatesDelay);

            if (!isPaused && !waitingStart) arrayRunIndex += arrayRunSpeed;
            if (arrayRunIndex >= mSpoofRoute.size() - 1) {
                arrayRunIndex = mSpoofRoute.size() - 1;
                if (mRouteSlice < mSlices.length - 1) {
                    replaceRouteSlice();
                    postDelayedJittered();
                    return;
                }
            }

            FakeRouteInfo info = onMockArrived(rSpeed, rAccuracy);
            rSpeed = info.speed;

            mCurrentStep.setLatitude(mSpoofRoute.get(arrayRunIndex).getLatitude());
            mCurrentStep.setLongitude(mSpoofRoute.get(arrayRunIndex).getLongitude());
            mCurrentStep.setAltitude(mSpoofRoute.get(arrayRunIndex).getAltitude());

            int nextPosBearing = arrayRunIndex + 2;
            if (nextPosBearing >= mSpoofRoute.size() - 1) nextPosBearing = arrayRunIndex;
            float bearing = (float) Geometry.getAzimuth(
                    mCurrentStep.getLatitude(), mCurrentStep.getLongitude(),
                    mSpoofRoute.get(nextPosBearing).getLatitude(),
                    mSpoofRoute.get(nextPosBearing).getLongitude());

            if (info.arrived) {
                bearing    = mBearing + (float) ThreadLocalRandom.current().nextDouble(-5, 5);
                rElevation = (float) info.altitude;
            }

            makeBrakeAtTurning();
            if (!info.arrived) {
                addTrafficSideOffset(nextPosBearing);
                rElevation += ThreadLocalRandom.current().nextDouble(-0.5, 0.5);
            }

            setMockLocation(rSpeed == -1 ? 0 : rSpeed, rAccuracy,
                    bearing + (float) Math.random(), rElevation);

            if (rSpeed == -1 && isClosedRoute) {
                setRoute(mRoutes, isClosedRoute);
                MultipleRoutesInfo routeInfo = mRoutes.get(0);
                mSpeed = routeInfo.getSpeed(); mSpeedDiff = routeInfo.getSpeedDiff();
                mElevation = routeInfo.getElevation(); mElevationDiff = routeInfo.getElevationDiff();
                mPassedDistance = 0; arrayRunSpeed = 0; arrayRunIndex = 0; isNeedBrake = false;
                postDelayedJittered();
                return;
            }

            postDelayedJittered();
        }

        /** postDelayed mit ±mUpdatesDrift Jitter — nur wenn dieser Runnable noch aktiv ist */
        private void postDelayedJittered() {
            if (stopped) return; // alter Runnable → nicht mehr einreihen
            long lo = Math.max(50, mUpdatesDelay - mUpdatesDrift);
            long hi = mUpdatesDelay + mUpdatesDrift;
            mHandler.postDelayed(this, ThreadLocalRandom.current().nextLong(lo, hi + 1));
        }

        // ── Drift ────────────────────────────────────────────────

        private GeoPoint applyStationaryDrift(double currentLat, double currentLon) {
            if (Double.isNaN(anchorLat)) { anchorLat = currentLat; anchorLon = currentLon; }
            driftLat += ThreadLocalRandom.current().nextDouble(-DRIFT_STEP_DEG, DRIFT_STEP_DEG);
            driftLon += ThreadLocalRandom.current().nextDouble(-DRIFT_STEP_DEG, DRIFT_STEP_DEG);
            double dist = Math.sqrt(driftLat * driftLat + driftLon * driftLon);
            if (dist > MAX_DRIFT_DEG) { driftLat -= driftLat * 0.15; driftLon -= driftLon * 0.15; }
            return new GeoPoint(anchorLat + driftLat, anchorLon + driftLon);
        }

        public void resetDrift() {
            anchorLat = Double.NaN; anchorLon = Double.NaN;
            driftLat  = 0.0;       driftLon  = 0.0;
        }

        public boolean isAtEnd() {
            return mSpoofRoute != null && arrayRunIndex >= mSpoofRoute.size() - 1;
        }

        // ── Route-Logik ──────────────────────────────────────────

        public void replaceRouteSlice() {
            mRouteSlice++;
            mSpoofRoute = mSlices[mRouteSlice];
            mSpoofRouteSpeeds = (mSlicesSpeeds != null && mSlicesSpeeds.length > mRouteSlice)
                    ? mSlicesSpeeds[mRouteSlice] : null;
            MultipleRoutesInfo routeInfo = mRoutes.get(mRouteSlice);
            if (!mSpeedManualOverride) {
                mSpeed = routeInfo.getSpeed();
                mSpeedDiff = routeInfo.getSpeedDiff();
            }
            mElevation = routeInfo.getElevation(); mElevationDiff = routeInfo.getElevationDiff();
            SystemClock.sleep(routeInfo.getStartingPauseTime());
            arrayRunSpeed = 0; arrayRunIndex = 0; isNeedBrake = false;
        }

        private void setMockLocation(float speed, float accuracy, float bearing, float elevation) {
            if (isMockLocationsEnabled) {
                MockLocProvider.setNetworkProvider(mCurrentStep.getLatitude(), mCurrentStep.getLongitude(), accuracy, bearing, elevation);
                MockLocProvider.setGpsProvider(mCurrentStep.getLatitude(), mCurrentStep.getLongitude(), bearing, speed, accuracy, elevation);
                Location fusedLocation = mFusedLocationProvider.build(mCurrentStep.getLatitude(), mCurrentStep.getLongitude(), accuracy, bearing, speed, elevation);
                mFusedLocationProvider.spoof(fusedLocation);
            } else if (isSystemApp) {
                MockLocProvider.reportLocation(mCurrentStep.getLatitude(), mCurrentStep.getLongitude(), accuracy, bearing, speed, elevation);
            }
        }

        private FakeRouteInfo onMockArrived(float speed, float accuracy) {
            FakeRouteInfo routeInfo = new FakeRouteInfo();
            if (arrayRunIndex >= mSpoofRoute.size() - 1) {
                routeInfo.arrived = true;
                speed = (float) ThreadLocalRandom.current().nextDouble(0, 0.3);
                updateUI(speed, mTotalDistance);
                if (SourceData.isClosedRoute) { isClosedRoute = !isClosedRoute; routeInfo.speed = -1; return routeInfo; }
                double altitude = mCurrentStep.getAltitude() + ThreadLocalRandom.current().nextDouble(-1, +1);
                GeoPoint drifted = applyStationaryDrift(mCurrentStep.getLatitude(), mCurrentStep.getLongitude());
                mCurrentStep.setLatitude(drifted.getLatitude());
                mCurrentStep.setLongitude(drifted.getLongitude());
                accuracy += (float) ThreadLocalRandom.current().nextDouble(0, 3.0);
                routeInfo.speed = speed; routeInfo.altitude = altitude;
                return routeInfo;
            } else {
                if (!isPaused && !waitingStart) {
                    mPassedDistance += Geometry.distance(
                            mSpoofRoute.get(arrayRunIndex - arrayRunSpeed).getLatitude(),
                            mSpoofRoute.get(arrayRunIndex - arrayRunSpeed).getLongitude(),
                            mSpoofRoute.get(arrayRunIndex).getLatitude(),
                            mSpoofRoute.get(arrayRunIndex).getLongitude(), mDefaultUnit);
                    updateUI(speed, mPassedDistance);
                    resetDrift();
                } else {
                    GeoPoint drifted = applyStationaryDrift(mCurrentStep.getLatitude(), mCurrentStep.getLongitude());
                    mCurrentStep.setLatitude(drifted.getLatitude());
                    mCurrentStep.setLongitude(drifted.getLongitude());
                    accuracy += (float) ThreadLocalRandom.current().nextDouble(0, 2.0);
                    updateUI(0, mPassedDistance);
                }
            }
            routeInfo.speed = speed; routeInfo.arrived = false;
            return routeInfo;
        }

        private void deviate(float accuracy) {
            GeoPoint point = LocationOperations.deviate(mCurrentStep, 0.5f);
            mCurrentStep.setLatitude(point.getLatitude());
            mCurrentStep.setLongitude(point.getLongitude());
        }

        private void addTrafficSideOffset(int nextPosBearing) {
            double latitude = mCurrentStep.getLatitude(), longitude = mCurrentStep.getLongitude();
            if (mTrafficSide == AppPreferences.RIGHT_HAND_TRAFFIC) {
                double distance = Geometry.distance(latitude, longitude, mSpoofRoute.get(nextPosBearing).getLatitude(), mSpoofRoute.get(nextPosBearing).getLongitude(), AppPreferences.KILOMETERS);
                double bearing  = getNewAngle(latitude, longitude, mSpoofRoute.get(nextPosBearing).getLatitude(), mSpoofRoute.get(nextPosBearing).getLongitude());
                GeoPoint geo = bearingDistance(latitude, longitude, distance, bearing + 25);
                mCurrentStep.setLatitude(geo.getLatitude()); mCurrentStep.setLongitude(geo.getLongitude());
            } else if (mTrafficSide == AppPreferences.LEFT_HAND_TRAFFIC) {
                double distance = Geometry.distance(latitude, longitude, mSpoofRoute.get(nextPosBearing).getLatitude(), mSpoofRoute.get(nextPosBearing).getLongitude(), AppPreferences.KILOMETERS);
                double bearing  = getNewAngle(latitude, longitude, mSpoofRoute.get(nextPosBearing).getLatitude(), mSpoofRoute.get(nextPosBearing).getLongitude());
                GeoPoint geo = bearingDistance(latitude, longitude, distance, bearing - 25);
                mCurrentStep.setLatitude(geo.getLatitude()); mCurrentStep.setLongitude(geo.getLongitude());
            }
        }

        private void makeBrakeAtTurning() { if (!mBrakeAtTurning) return; }

        private double getNewAngle(double lat1, double lon1, double lat2, double lon2) {
            return Math.toDegrees(Math.atan2(lon2 - lon1, lat2 - lat1));
        }

        private GeoPoint bearingDistance(double lat, double lon, double dist, double bearing) {
            double R = 6371.0, lat1 = Math.toRadians(lat), lon1 = Math.toRadians(lon), brng = Math.toRadians(bearing);
            double lat2 = Math.asin(Math.sin(lat1) * Math.cos(dist / R) + Math.cos(lat1) * Math.sin(dist / R) * Math.cos(brng));
            double lon2 = lon1 + Math.atan2(Math.sin(brng) * Math.sin(dist / R) * Math.cos(lat1), Math.cos(dist / R) - Math.sin(lat1) * Math.sin(lat2));
            return new GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2));
        }
    }
}