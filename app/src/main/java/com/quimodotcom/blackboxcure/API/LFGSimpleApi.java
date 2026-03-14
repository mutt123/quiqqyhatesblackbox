package com.quimodotcom.blackboxcure.API;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.io.IOException;
import java.util.ArrayList;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import com.quimodotcom.blackboxcure.Enumerations.ERouteTransport;
import com.quimodotcom.blackboxcure.WebClient;

// Routing: OSRM (https://project-osrm.org) — open source, no API key required
// Elevation: Open-Elevation (https://open-elevation.com) — open source, no API key required
public class LFGSimpleApi {

    // OSRM public instance — foot profile only
    private static final String OSRM_BASE_URL = "https://router.project-osrm.org/route/v1/foot/";
    // Open-Elevation public instance
    private static final String ELEVATION_URL = "https://api.open-elevation.com/api/v1/lookup";

    public static final int CODE_SUCCESS = 0;
    public static final int CODE_CONNECTION_FAILED = -1;
    // kept for source-compatibility with RouteBuilder, never triggered anymore
    public static final int CODE_RECAPTCHA_RESPONSE = -2;
    public static final int CODE_BAD_RECAPTCHA_RESPONSE = -3;
    public static final int CODE_UNKNOWN_ERROR = -4;

    public LFGSimpleApi() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Elevation  (Open-Elevation)
    // ─────────────────────────────────────────────────────────────────────────
    public static class Elevation {

        public interface ElevationCallback {
            void onRequestSuccess(float altitude);
            void onCaptchaResult(); // kept for interface compatibility, never called
            void onRequestError();
        }

        public Elevation() {}

        /** @deprecated cacheDir is no longer used — kept for call-site compatibility */
        @Deprecated
        public Elevation(java.io.File cacheDir) {}

        public void getElevation(double latitude, double longitude,
                                 String ignoredChallengeResult,
                                 ElevationCallback callback) {

            String url = ELEVATION_URL + "?locations=" + latitude + "," + longitude;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            WebClient.getInstance().makeRequest(request, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e("Elevation", "onFailure", e);
                    callback.onRequestError();
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response)
                        throws IOException {
                    try {
                        String body = response.body().string();
                        JSONObject root = new JSONObject(body);
                        JSONArray results = root.getJSONArray("results");
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

    // ─────────────────────────────────────────────────────────────────────────
    // Directions  (OSRM — foot profile)
    // ─────────────────────────────────────────────────────────────────────────
    public static class Directions {

        private final double sourcelat;
        private final double sourcelong;
        private final double destlat;
        private final double destlong;

        private double distance;

        public static class DirectionsResponse {
            public String error;
            public int code;
            public ArrayList<GeoPoint> result;
            public ArrayList<Integer> speedLimits; // always null — OSRM foot has no speed limits
            public double distance;
        }

        public interface DirectionsCallback {
            void onResult(DirectionsResponse response);
        }

        /**
         * transport and captchaResult are accepted for call-site compatibility but ignored.
         * The OSRM foot profile is always used.
         */
        public Directions(double sourcelat, double sourcelong,
                          double destlat, double destlong,
                          ERouteTransport transport, String captchaResult) {
            this.sourcelat = sourcelat;
            this.sourcelong = sourcelong;
            this.destlat = destlat;
            this.destlong = destlong;
        }

        private String buildOsrmUrl() {
            // OSRM expects  lon,lat  order
            return OSRM_BASE_URL
                    + sourcelong + "," + sourcelat
                    + ";"
                    + destlong + "," + destlat
                    + "?overview=full&geometries=geojson";
        }

        /** context parameter accepted for call-site compatibility but not used. */
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
                    response.code = CODE_CONNECTION_FAILED;
                    callback.onResult(response);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response _response)
                        throws IOException {
                    try {
                        String responseString = _response.body().string();
                        Log.d("RouteBuilder", "onResponse: " + responseString);

                        JSONObject root = new JSONObject(responseString);

                        // OSRM returns {"code":"Ok",...} on success
                        String code = root.optString("code", "");
                        if (!code.equals("Ok")) {
                            response.code = CODE_UNKNOWN_ERROR;
                            response.error = root.optString("message", "OSRM error: " + code);
                            callback.onResult(response);
                            return;
                        }

                        JSONArray routes = root.getJSONArray("routes");
                        JSONObject route = routes.getJSONObject(0);

                        // Distance in metres
                        distance = route.getDouble("distance");
                        response.distance = distance;

                        // Geometry: GeoJSON LineString  →  coordinates: [[lon,lat], ...]
                        JSONObject geometry = route.getJSONObject("geometry");
                        JSONArray coordinates = geometry.getJSONArray("coordinates");

                        ArrayList<GeoPoint> points = new ArrayList<>(coordinates.length());
                        for (int i = 0; i < coordinates.length(); i++) {
                            JSONArray coord = coordinates.getJSONArray(i);
                            double lon = coord.getDouble(0);
                            double lat = coord.getDouble(1);
                            // elevation not included in OSRM foot response by default
                            points.add(new GeoPoint(lat, lon));
                        }

                        response.result = points;
                        response.speedLimits = null; // pedestrian — no speed limits
                        response.code = CODE_SUCCESS;

                    } catch (Exception e) {
                        Log.e("RouteBuilder", "parse error", e);
                        response.code = CODE_UNKNOWN_ERROR;
                        response.error = e.getMessage();
                    }

                    callback.onResult(response);
                }
            });
        }

        public double getDistance() {
            return distance;
        }
    }
}
