package com.quimodotcom.blackboxcure;


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
        startMotion(points, buffer, false);
    }

    public static void startMotion(List<GeoPoint> points, ArrayList<GeoPoint> buffer, boolean smooth) {
        List<GeoPoint> workingPoints = points;

        if (smooth && points.size() > 2) {
            for (int iter = 0; iter < 2; iter++) {
                List<GeoPoint> smoothedPoints = new ArrayList<>();
                smoothedPoints.add(workingPoints.get(0));

                for (int i = 0; i < workingPoints.size() - 1; i++) {
                    GeoPoint p0 = workingPoints.get(i);
                    GeoPoint p1 = workingPoints.get(i + 1);

                    double qLat = 0.75 * p0.getLatitude()  + 0.25 * p1.getLatitude();
                    double qLon = 0.75 * p0.getLongitude() + 0.25 * p1.getLongitude();
                    double rLat = 0.25 * p0.getLatitude()  + 0.75 * p1.getLatitude();
                    double rLon = 0.25 * p0.getLongitude() + 0.75 * p1.getLongitude();

                    smoothedPoints.add(new GeoPoint(qLat, qLon, p0.getAltitude()));
                    smoothedPoints.add(new GeoPoint(rLat, rLon, p1.getAltitude()));
                }

                smoothedPoints.add(workingPoints.get(workingPoints.size() - 1));
                workingPoints = smoothedPoints;
            }
        }

        for (int i = 0; i <= workingPoints.size() - 1; i++) {
            if ((i + 1) != workingPoints.size()) {
                float[] results = new float[1];
                Location.distanceBetween(workingPoints.get(i).getLatitude(), workingPoints.get(i).getLongitude(), workingPoints.get(i + 1).getLatitude(), workingPoints.get(i + 1).getLongitude(), results);
                segmentPoints(workingPoints.get(i), workingPoints.get(i + 1), (int) results[0], buffer);
            }
        }
    }

    private static void segmentPoints(GeoPoint paramLatLng1, GeoPoint paramLatLng2, int paramInt, ArrayList<GeoPoint> buffer) {
        for (int i = paramInt; i >= 0; i--) {
            double d1 = paramLatLng1.getLatitude();
            double d2 = paramInt - i;
            double d3 = paramLatLng2.getLatitude();
            double d4 = paramLatLng1.getLatitude();
            double elevation = paramLatLng1.getAltitude();
            GeoPoint geo = new GeoPoint(d1 + (d3 - d4) * d2 / paramInt, paramLatLng1.getLongitude() + d2 * (paramLatLng2.getLongitude() - paramLatLng1.getLongitude()) / paramInt, elevation);
            buffer.add(geo);
        }
    }
}