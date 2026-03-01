package project.listick.fakegps;


import android.location.Location;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

/*
 * Created by LittleAngry on 04.01.19 (macOS 10.12)
 * */
public class RouteManager {

    public static List<MultipleRoutesInfo> routes = new ArrayList<>();

    public static int getLatestElement() {
        return RouteManager.routes.size() - 1;
    }

    public static void startMotion(List<GeoPoint> points, ArrayList<GeoPoint> buffer) {
        startMotion(points, null, buffer, null, false);
    }

    public static void startMotion(List<GeoPoint> points, List<Integer> speedLimits, ArrayList<GeoPoint> buffer, ArrayList<Integer> speedBuffer, boolean smooth) {
        List<GeoPoint> workingPoints = points;
        List<Integer> workingSpeedLimits = speedLimits;

        if (smooth && points.size() > 2) {
            // Apply 2 iterations of Chaikin's algorithm for smoothing
            for (int iter = 0; iter < 2; iter++) {
                List<GeoPoint> smoothedPoints = new ArrayList<>();
                List<Integer> smoothedSpeedLimits = speedLimits != null ? new ArrayList<>() : null;

                // Keep first point
                smoothedPoints.add(workingPoints.get(0));
                if (smoothedSpeedLimits != null) smoothedSpeedLimits.add(workingSpeedLimits.get(0));

                for (int i = 0; i < workingPoints.size() - 1; i++) {
                    GeoPoint p0 = workingPoints.get(i);
                    GeoPoint p1 = workingPoints.get(i + 1);

                    double lat0 = p0.getLatitude();
                    double lon0 = p0.getLongitude();
                    double lat1 = p1.getLatitude();
                    double lon1 = p1.getLongitude();

                    // Q = 0.75*P0 + 0.25*P1
                    double qLat = 0.75 * lat0 + 0.25 * lat1;
                    double qLon = 0.75 * lon0 + 0.25 * lon1;
                    // R = 0.25*P0 + 0.75*P1
                    double rLat = 0.25 * lat0 + 0.75 * lat1;
                    double rLon = 0.25 * lon0 + 0.75 * lon1;

                    smoothedPoints.add(new GeoPoint(qLat, qLon, p0.getAltitude()));
                    smoothedPoints.add(new GeoPoint(rLat, rLon, p1.getAltitude()));

                    if (smoothedSpeedLimits != null) {
                        smoothedSpeedLimits.add(workingSpeedLimits.get(i));
                        smoothedSpeedLimits.add(workingSpeedLimits.get(i));
                    }
                }

                // Keep last point
                smoothedPoints.add(workingPoints.get(workingPoints.size() - 1));
                if (smoothedSpeedLimits != null) smoothedSpeedLimits.add(workingSpeedLimits.get(workingSpeedLimits.size() - 1));

                workingPoints = smoothedPoints;
                workingSpeedLimits = smoothedSpeedLimits;
            }
        }

        for (int i = 0; i <= workingPoints.size() - 1; i++) {
            if ((i + 1) != workingPoints.size()) {
                float[] results = new float[1];
                Location.distanceBetween(workingPoints.get(i).getLatitude(), workingPoints.get(i).getLongitude(), workingPoints.get(i + 1).getLatitude(), workingPoints.get(i + 1).getLongitude(), results);
                int p = (int) results[0];
                int speedLimit = (workingSpeedLimits != null && i < workingSpeedLimits.size()) ? workingSpeedLimits.get(i) : 50;
                segmentPoints(workingPoints.get(i), workingPoints.get(i + 1), p, buffer, speedBuffer, speedLimit);
            }
        }
    }

    private static void segmentPoints(GeoPoint paramLatLng1, GeoPoint paramLatLng2, int paramInt, ArrayList<GeoPoint> buffer, ArrayList<Integer> speedBuffer, int speedLimit) {
        for (int i = paramInt; i >= 0; i--) {
            double d1 = paramLatLng1.getLatitude();
            double d2 = paramInt - i;
            double d3 = paramLatLng2.getLatitude();
            double d4 = paramLatLng1.getLatitude();
            double elevation = paramLatLng1.getAltitude();
            GeoPoint geo = new GeoPoint(d1 + (d3 - d4) * d2 / paramInt, paramLatLng1.getLongitude() + d2 * (paramLatLng2.getLongitude() - paramLatLng1.getLongitude()) / paramInt, elevation);
            buffer.add(geo);
            if (speedBuffer != null) {
                speedBuffer.add(speedLimit);
            }
        }
    }
}
