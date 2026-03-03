package project.listick.fakegps.Services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Handler;
import android.os.IBinder;

import androidx.annotation.Nullable;

import project.listick.fakegps.FusedLocationsProvider;
import project.listick.fakegps.MainServiceControl;
import project.listick.fakegps.MockLocProvider;
import project.listick.fakegps.PermissionManager;
import project.listick.fakegps.NmeaParser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.OnNmeaMessageListener;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.concurrent.ConcurrentLinkedQueue;

public class RealtimeSpooferService extends Service {

    public static final String KEY_BUFFER_DELAY = "buffer_delay";

    private LocationManager mLocationManager;
    private FusedLocationsProvider mFusedLocationProvider;
    private Handler mHandler;

    private boolean isMockLocationsEnabled;
    private boolean isSystemApp;
    private int mBufferDelaySecs = 10;

    private ConcurrentLinkedQueue<Location> locationBuffer = new ConcurrentLinkedQueue<>();
    private OnNmeaMessageListener nmeaListener;
    private LocationListener networkListener;

    private Location lastOutputLocation = null;
    private double currentSpeedMs = 0;
    private long lastOutputTime = 0;

    // For path interpolation
    private Location currentTarget = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MainServiceControl.startServiceForeground(this);
        mHandler = new Handler();
        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        mFusedLocationProvider = new FusedLocationsProvider(this);
        isMockLocationsEnabled = PermissionManager.isMockLocationsEnabled(this);
        isSystemApp = PermissionManager.isSystemApp(this);

        MockLocProvider.initTestProvider();

        if (intent != null) {
            mBufferDelaySecs = intent.getIntExtra(KEY_BUFFER_DELAY, 10);
        }

        registerListeners();

        mHandler.postDelayed(spoofLoop, mBufferDelaySecs * 1000L);

        return START_STICKY;
    }

    private Runnable spoofLoop = new Runnable() {
        @Override
        public void run() {
            processBuffer();
            mHandler.postDelayed(this, 1000); // Process every 1 second
        }
    };

    private void processBuffer() {
        long targetTime = System.currentTimeMillis() - (mBufferDelaySecs * 1000L);

        // Ensure we always have a current target that is delayed by exactly mBufferDelaySecs
        // If the buffer has points older than targetTime, we can move towards them
        while (currentTarget == null || currentTarget.getTime() <= targetTime) {
            if (locationBuffer.isEmpty()) {
                break;
            }
            Location head = locationBuffer.peek();
            if (head.getTime() <= targetTime) {
                currentTarget = locationBuffer.poll();
            } else {
                break; // Next point is not ready yet
            }
        }

        if (currentTarget != null) {
            if (lastOutputLocation == null) {
                // First initialization
                lastOutputLocation = new Location(currentTarget);
                currentSpeedMs = currentTarget.getSpeed();
                lastOutputTime = System.currentTimeMillis();
                outputLocation(lastOutputLocation.getLatitude(), lastOutputLocation.getLongitude(), (float)currentSpeedMs, currentTarget.getBearing(), currentTarget.getAccuracy());
                return;
            }

            long now = System.currentTimeMillis();
            double dt = (now - lastOutputTime) / 1000.0; // Seconds since last loop
            lastOutputTime = now;

            double distanceToTarget = lastOutputLocation.distanceTo(currentTarget);

            if (distanceToTarget < 1.0) {
                // Already at target, don't move further, just wait for next target
                outputLocation(currentTarget.getLatitude(), currentTarget.getLongitude(), 0f, currentTarget.getBearing(), currentTarget.getAccuracy());
                lastOutputLocation.setLatitude(currentTarget.getLatitude());
                lastOutputLocation.setLongitude(currentTarget.getLongitude());
                return;
            }

            double targetSpeedMs = currentTarget.getSpeed(); // Speed recorded at the real GPS point

            // Calculate turning angle if we have next points in the buffer
            double angle = 180.0; // Straight line
            Location nextTarget = locationBuffer.peek();
            if (nextTarget != null) {
                angle = project.listick.fakegps.Geometry.getAngle(
                    lastOutputLocation.getLatitude(), lastOutputLocation.getLongitude(),
                    currentTarget.getLatitude(), currentTarget.getLongitude(),
                    nextTarget.getLatitude(), nextTarget.getLongitude()
                );
            }

            // Brake at turning logic
            double coefficient = angle / 180.0;
            double maxSpeedMs = targetSpeedMs * Math.pow(coefficient, 2);

            // Smooth speed transition
            double accelMs2 = 1.5; // max acceleration/deceleration m/s^2
            if (currentSpeedMs > maxSpeedMs) {
                currentSpeedMs -= accelMs2 * dt; // Slow down
                if (currentSpeedMs < maxSpeedMs) currentSpeedMs = maxSpeedMs;
            } else if (currentSpeedMs < maxSpeedMs) {
                currentSpeedMs += accelMs2 * dt; // Speed up
                if (currentSpeedMs > maxSpeedMs) currentSpeedMs = maxSpeedMs;
            }

            // Interpolate position
            double travelDistance = currentSpeedMs * dt;
            if (travelDistance > distanceToTarget) {
                travelDistance = distanceToTarget;
            }

            double bearing = lastOutputLocation.bearingTo(currentTarget);

            // Calculate new lat/lon using basic equirectangular approximation for small distances
            double earthRadius = 6371000.0;
            double lat1 = Math.toRadians(lastOutputLocation.getLatitude());
            double lon1 = Math.toRadians(lastOutputLocation.getLongitude());
            double brng = Math.toRadians(bearing);

            double lat2 = Math.asin(Math.sin(lat1) * Math.cos(travelDistance / earthRadius) +
                            Math.cos(lat1) * Math.sin(travelDistance / earthRadius) * Math.cos(brng));
            double lon2 = lon1 + Math.atan2(Math.sin(brng) * Math.sin(travelDistance / earthRadius) * Math.cos(lat1),
                            Math.cos(travelDistance / earthRadius) - Math.sin(lat1) * Math.sin(lat2));

            lastOutputLocation.setLatitude(Math.toDegrees(lat2));
            lastOutputLocation.setLongitude(Math.toDegrees(lon2));
            lastOutputLocation.setBearing((float)bearing);
            lastOutputLocation.setSpeed((float)currentSpeedMs);

            // Output location
            outputLocation(lastOutputLocation.getLatitude(), lastOutputLocation.getLongitude(), (float)currentSpeedMs, (float)bearing, currentTarget.getAccuracy());
        }
    }

    private void outputLocation(double lat, double lon, float speedMs, float bearing, float accuracy) {
        if (isMockLocationsEnabled) {
            MockLocProvider.setNetworkProvider(lat, lon, accuracy, bearing, 0);
            // MockLocProvider.setGpsProvider expects km/h because it internally divides by 3.6f
            MockLocProvider.setGpsProvider(lat, lon, bearing, speedMs * 3.6f, accuracy, 0);
            // FusedLocationsProvider.build also expects km/h and divides by 3.6f internally
            Location fusedLocation = mFusedLocationProvider.build(lat, lon, accuracy, bearing, speedMs * 3.6f, 0);
            mFusedLocationProvider.spoof(fusedLocation);
        } else if (isSystemApp) {
            // reportLocation takes speed directly in m/s
            MockLocProvider.reportLocation(lat, lon, accuracy, bearing, speedMs, 0);
        }
    }

    @SuppressLint("MissingPermission")
    private void registerListeners() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nmeaListener = new OnNmeaMessageListener() {
                @Override
                public void onNmeaMessage(String message, long timestamp) {
                    Location loc = NmeaParser.parse(message);
                    if (loc != null) {
                        Log.d("RealtimeSpoofer", "Got NMEA location: " + loc.getLatitude() + ", " + loc.getLongitude());
                        locationBuffer.add(loc);
                    }
                }
            };
            mLocationManager.addNmeaListener(nmeaListener, mHandler);
        }

        networkListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null && location.getProvider().equals(LocationManager.NETWORK_PROVIDER)) {
                    // Check if this location is a mock location to avoid feedback loop
                    boolean isMock = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        isMock = location.isMock();
                    } else {
                        isMock = location.isFromMockProvider();
                    }

                    if (!isMock) {
                        // Only use network if NMEA queue is empty to avoid duplicates
                        if (locationBuffer.isEmpty()) {
                            Log.d("RealtimeSpoofer", "Got real Network location: " + location.getLatitude() + ", " + location.getLongitude());
                            locationBuffer.add(location);
                        }
                    }
                }
            }
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override
            public void onProviderEnabled(String provider) {}
            @Override
            public void onProviderDisabled(String provider) {}
        };

        if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, networkListener, mHandler.getLooper());
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mLocationManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && nmeaListener != null) {
                mLocationManager.removeNmeaListener(nmeaListener);
            }
            if (networkListener != null) {
                mLocationManager.removeUpdates(networkListener);
            }
        }
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        stopForeground(true);
        MockLocProvider.removeProviders();
    }
}
