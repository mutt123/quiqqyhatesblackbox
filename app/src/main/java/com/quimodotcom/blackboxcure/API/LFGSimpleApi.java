package com.quimodotcom.blackboxcure.API;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import com.quimodotcom.blackboxcure.Enumerations.ERouteTransport;
import com.quimodotcom.blackboxcure.WebClient;

// Routing: OSRM (https://routing.openstreetmap.de) — kein API-Key nötig
// Elevation: Open-Elevation (https://open-elevation.com) — kein API-Key nötig
public class LFGSimpleApi {

    private static final String ELEVATION_URL = "https://api.open-elevation.com/api/v1/lookup";

    public static final int CODE_SUCCESS          =  0;
    public static final int CODE_CONNECTION_FAILED = -1;
    public static final int CODE_RECAPTCHA_RESPONSE    = -2; // Kompatibilität
    public static final int CODE_BAD_RECAPTCHA_RESPONSE = -3; // Kompatibilität
    public static final int CODE_UNKNOWN_ERROR    = -4;

    public LFGSimpleApi() {}

    // ─────────────────────────────────────────────────────────────
    // Elevation
    // ─────────────────────────────────────────────────────────────
    public static class Elevation {

        public interface ElevationCallback {
            void onRequestSuccess(float altitude);
            void onCaptchaResult();
            void onRequestError();
        }

        public Elevation() {}
        @Deprecated
        public Elevation(java.io.File cacheDir) {}

        public void getElevation(double latitude, double longitude,
                                 String ignoredChallengeResult,
                                 ElevationCallback callback) {
            String url = ELEVATION_URL + "?locations=" + latitude + "," + longitude;
            Request request = new Request.Builder().url(url).get().build();

            WebClient.getInstance().makeRequest(request, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e("Elevation", "onFailure", e);
                    callback.onRequestError();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try {
                        JSONObject root    = new JSONObject(response.body().string());
                        JSONArray  results = root.getJSONArray("results");
                        float altitude = (float) results.getJSONObject(0).getDouble("elevation");
                        callback.onRequestSuccess(altitude);
                    } catch (JSONException e) {
                        Log.e("Elevation", "parse error", e);
                        callback.onRequestError();
                    }
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Directions  – OSRM mit optionalen Zwischenstopps
    // ─────────────────────────────────────────────────────────────
    public static class Directions {

        private final double sourcelat;
        private final double sourcelong;
        private final double destlat;
        private final double destlong;

        /** Optionale Zwischenstopps zwischen Start und Ziel */
        private final List<GeoPoint> waypoints;

        private final ERouteTransport transport;
        private double distance;

        public static class DirectionsResponse {
            public String error;
            public int    code;
            public ArrayList<GeoPoint>  result;
            public double distance;
        }

        public interface DirectionsCallback {
            void onResult(DirectionsResponse response);
        }

        /** Konstruktor ohne Zwischenstopps – Rückwärtskompatibilität */
        public Directions(double sourcelat, double sourcelong,
                          double destlat,   double destlong,
                          ERouteTransport transport, String ignoredCaptcha) {
            this(sourcelat, sourcelong, destlat, destlong, transport, null, ignoredCaptcha);
        }

        /** Konstruktor mit Zwischenstopps */
        public Directions(double sourcelat, double sourcelong,
                          double destlat,   double destlong,
                          ERouteTransport transport,
                          List<GeoPoint> waypoints,
                          String ignoredCaptcha) {
            this.sourcelat  = sourcelat;
            this.sourcelong = sourcelong;
            this.destlat    = destlat;
            this.destlong   = destlong;
            this.transport  = transport;
            this.waypoints  = (waypoints != null) ? waypoints : new ArrayList<>();
        }

        /**
         * Baut die OSRM-URL mit Start, beliebig vielen Zwischenstopps und Ziel.
         * Format: /route/v1/{profil}/lon1,lat1;lon2,lat2;...;lonN,latN
         */
        private String buildOsrmUrl() {
            String baseUrl;
            switch (transport) {
                case ROUTE_BIKE:
                    baseUrl = "https://routing.openstreetmap.de/routed-bike/route/v1/bike/";
                    break;
                case ROUTE_CAR:
                    baseUrl = "https://routing.openstreetmap.de/routed-car/route/v1/driving/";
                    break;
                case ROUTE_WALK:
                default:
                    baseUrl = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/";
                    break;
            }

            StringBuilder coords = new StringBuilder();
            // Start
            coords.append(sourcelong).append(",").append(sourcelat);
            // Zwischenstopps
            for (GeoPoint wp : waypoints) {
                coords.append(";").append(wp.getLongitude()).append(",").append(wp.getLatitude());
            }
            // Ziel
            coords.append(";").append(destlong).append(",").append(destlat);

            return baseUrl + coords + "?overview=full&geometries=geojson";
        }

        public void downloadRoute(android.content.Context context, DirectionsCallback callback) {
            downloadRoute(callback);
        }

        public void downloadRoute(DirectionsCallback callback) {
            DirectionsResponse response = new DirectionsResponse();

            Request request = new Request.Builder()
                    .url(buildOsrmUrl())
                    .get()
                    .build();

            WebClient.getInstance().makeRequest(request, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e("RouteBuilder", "onFailure", e);
                    response.error = e.getMessage();
                    response.code  = CODE_CONNECTION_FAILED;
                    callback.onResult(response);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response _response) throws IOException {
                    try {
                        String     responseString = _response.body().string();
                        JSONObject root           = new JSONObject(responseString);

                        String code = root.optString("code", "");
                        if (!code.equals("Ok")) {
                            response.code  = CODE_UNKNOWN_ERROR;
                            response.error = root.optString("message", "OSRM error: " + code);
                            callback.onResult(response);
                            return;
                        }

                        JSONObject route       = root.getJSONArray("routes").getJSONObject(0);
                        distance               = route.getDouble("distance");
                        response.distance      = distance;

                        JSONArray coordinates  = route.getJSONObject("geometry").getJSONArray("coordinates");
                        ArrayList<GeoPoint> points = new ArrayList<>(coordinates.length());
                        for (int i = 0; i < coordinates.length(); i++) {
                            JSONArray coord = coordinates.getJSONArray(i);
                            points.add(new GeoPoint(coord.getDouble(1), coord.getDouble(0)));
                        }

                        response.result      = points;
                        response.code        = CODE_SUCCESS;

                    } catch (Exception e) {
                        Log.e("RouteBuilder", "parse error", e);
                        response.code  = CODE_UNKNOWN_ERROR;
                        response.error = e.getMessage();
                    }
                    callback.onResult(response);
                }
            });
        }

        public double getDistance() { return distance; }
    }
}