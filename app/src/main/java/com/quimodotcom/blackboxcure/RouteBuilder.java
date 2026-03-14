package com.quimodotcom.blackboxcure;

import static com.quimodotcom.blackboxcure.API.LFGSimpleApi.CODE_CONNECTION_FAILED;
import static com.quimodotcom.blackboxcure.API.LFGSimpleApi.CODE_SUCCESS;
import static com.quimodotcom.blackboxcure.API.LFGSimpleApi.CODE_UNKNOWN_ERROR;

import android.app.Activity;
import android.util.Log;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;

import com.quimodotcom.blackboxcure.API.LFGSimpleApi;
import com.quimodotcom.blackboxcure.Enumerations.ERouteTransport;

public class RouteBuilder {

    public static final String TAG = "RouteBuilder";

    private final Activity activity;

    private final double originLat;
    private final double originLng;
    private final double destLat;
    private final double destLng;

    private final ERouteTransport transport;
    private boolean canceled;

    public interface IRouteBuilder {
        void prepare();
        void onRouteBuilt(ArrayList<GeoPoint> points, ArrayList<Integer> speedLimits,
                          double sourceLat, double sourceLong,
                          double destLat, double destLong,
                          double distance, ERouteTransport transport);
        void onRouteError(ArrayList<GeoPoint> points,
                          double sourceLat, double sourceLong,
                          double destLat, double destLong,
                          double distance, ERouteTransport transport);
        // kept so existing call-sites compile; never invoked anymore
        void captchaResponse();
    }

    public RouteBuilder(Activity activity,
                        double originLat, double originLng,
                        double destLat, double destLng,
                        ERouteTransport transport,
                        String ignoredCaptchaResult) {
        this.activity = activity;
        this.originLat = originLat;
        this.originLng = originLng;
        this.destLat = destLat;
        this.destLng = destLng;
        this.transport = transport;
        this.canceled = false;
    }

    public void build(IRouteBuilder listener) {
        listener.prepare();

        LFGSimpleApi.Directions directionsApi = new LFGSimpleApi.Directions(
                originLat, originLng, destLat, destLng, transport, null);

        directionsApi.downloadRoute(activity, response -> {
            if (canceled) return;

            if (response.code == CODE_SUCCESS) {
                activity.runOnUiThread(() ->
                        listener.onRouteBuilt(
                                response.result,
                                response.speedLimits,   // null for pedestrian
                                originLat, originLng,
                                destLat, destLng,
                                response.distance,
                                transport));

            } else {
                // CODE_CONNECTION_FAILED or CODE_UNKNOWN_ERROR
                ArrayList<GeoPoint> fallback = new ArrayList<>();
                fallback.add(new GeoPoint(originLat, originLng));
                fallback.add(new GeoPoint(destLat, destLng));

                activity.runOnUiThread(() ->
                        listener.onRouteError(
                                fallback,
                                originLat, originLng,
                                destLat, destLng,
                                0, transport));
            }
        });
    }

    public void cancel() {
        this.canceled = true;
    }
}
