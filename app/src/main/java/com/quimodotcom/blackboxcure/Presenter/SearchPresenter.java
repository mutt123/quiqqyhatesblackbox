package com.quimodotcom.blackboxcure.Presenter;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.quimodotcom.blackboxcure.Contract.SearchImpl;
import com.quimodotcom.blackboxcure.Enumerations.ERouteTransport;
import com.quimodotcom.blackboxcure.ListickApp;
import com.quimodotcom.blackboxcure.R;
import com.quimodotcom.blackboxcure.SpoofingPlaceInfo;
import com.quimodotcom.blackboxcure.UI.SelectPointActivity;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;

public class SearchPresenter implements SearchImpl.Presenter {

    /** Public – wird von SelectPointActivity referenziert */
    public static final String OPEN_SEARCH = "open_search";

    private static final int DESTINATION   = 1;
    private static final int ORIGIN        = 2;
    /** Request-Codes für Zwischenstopps: 100, 101, 102 … */
    private static final int WAYPOINT_BASE = 100;

    private final Activity      mActivity;
    private final SearchImpl.UI mUserInterface;

    private double  mOriginLat;
    private double  mOriginLong;
    private double  mDestLat;
    private double  mDestLong;
    private String  mOriginAddress;
    private String  mDestAddress;
    private boolean preparedForFinish = false;

    private ERouteTransport mTransport = ERouteTransport.ROUTE_WALK;

    /** Zwischenstopps in Reihenfolge */
    private final ArrayList<GeoPoint> mWaypoints     = new ArrayList<>();
    private final ArrayList<String>   mWaypointAddrs = new ArrayList<>();

    public SearchPresenter(Activity activity, SearchImpl.UI ui) {
        mActivity      = activity;
        mUserInterface = ui;
        getOriginAddress();
        changeTransport(ERouteTransport.ROUTE_WALK);
    }

    @Override public void onActivityLoad()  { getOriginAddress(); }
    @Override public void onDestination()   { findOnMap(DESTINATION); }
    @Override public void onOrigin()        { findOnMap(ORIGIN); }
    @Override public void onContinue()      { sendResults(); }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;

        double lat  = data.getDoubleExtra(ListickApp.LATITUDE,  0d);
        double lon  = data.getDoubleExtra(ListickApp.LONGITUDE, 0d);
        String addr = data.getStringExtra(SpoofingPlaceInfo.ADDRESS);

        if (requestCode == DESTINATION) {
            preparedForFinish = true;
            mDestLat = lat; mDestLong = lon; mDestAddress = addr;
            mUserInterface.setDestAddress(mDestAddress);

        } else if (requestCode == ORIGIN) {
            mOriginLat = lat; mOriginLong = lon; mOriginAddress = addr;
            mUserInterface.setOriginAddress(mOriginAddress);

        } else if (requestCode >= WAYPOINT_BASE) {
            int index = requestCode - WAYPOINT_BASE;
            if (index < mWaypoints.size()) {
                mWaypoints.set(index, new GeoPoint(lat, lon));
                String label = (addr != null && !addr.isEmpty()) ? addr : lat + ", " + lon;
                mWaypointAddrs.set(index, label);
                mUserInterface.updateWaypoint(index, label);
            }
        }
    }

    @Override
    public void selectOnMap() {
        hideKeyboard();
        Intent i = mActivity.getIntent();
        openSelectPoint(
                DESTINATION,
                i.getDoubleExtra(ListickApp.LATITUDE,  0d),
                i.getDoubleExtra(ListickApp.LONGITUDE, 0d));
    }

    @Override
    public void onTransport(ERouteTransport transport) { changeTransport(transport); }

    // ── Zwischenstopps ───────────────────────────────────────────

    /** Fügt neuen Zwischenstopp ein und öffnet sofort die Kartenauswahl */
    public void addWaypoint() {
        int index = mWaypoints.size();
        mWaypoints.add(new GeoPoint(mOriginLat, mOriginLong));
        mWaypointAddrs.add("");
        mUserInterface.addWaypointRow(index, "");
        openSelectPoint(WAYPOINT_BASE + index, mOriginLat, mOriginLong);
    }

    /** Entfernt Zwischenstopp an Position index */
    public void removeWaypoint(int index) {
        if (index < 0 || index >= mWaypoints.size()) return;
        mWaypoints.remove(index);
        mWaypointAddrs.remove(index);
        mUserInterface.removeWaypointRow(index);
    }

    /** Öffnet Kartenauswahl um vorhandenen Zwischenstopp zu ändern */
    public void editWaypoint(int index) {
        GeoPoint wp = mWaypoints.get(index);
        openSelectPoint(WAYPOINT_BASE + index, wp.getLatitude(), wp.getLongitude());
    }

    // ── Intern ───────────────────────────────────────────────────

    private void openSelectPoint(int requestCode, double lat, double lon) {
        hideKeyboard();
        mActivity.startActivityForResult(
                new Intent(mActivity, SelectPointActivity.class)
                        .putExtra(ListickApp.LATITUDE,  lat)
                        .putExtra(ListickApp.LONGITUDE, lon)
                        .putExtra(OPEN_SEARCH, true),
                requestCode);
    }

    private void findOnMap(int field) {
        hideKeyboard();
        Intent intent = mActivity.getIntent();
        openSelectPoint(field,
                intent.getDoubleExtra(ListickApp.LATITUDE,  0d),
                intent.getDoubleExtra(ListickApp.LONGITUDE, 0d));
    }

    private void sendResults() {
        if (!preparedForFinish) {
            new MaterialAlertDialogBuilder(mActivity, R.style.ThemeOverlay_Material3_MaterialAlertDialog_Centered)
                    .setTitle(R.string.you_dont_select_dest)
                    .setMessage(R.string.please_select_dest)
                    .setCancelable(true)
                    .setPositiveButton(R.string.okay, (d, w) -> d.cancel())
                    .setIcon(R.drawable.ic_location)
                    .show();
            return;
        }

        Intent intent = new Intent();
        intent.putExtra(SpoofingPlaceInfo.ORIGIN_LAT,    mOriginLat);
        intent.putExtra(SpoofingPlaceInfo.ORIGIN_LNG,    mOriginLong);
        intent.putExtra(SpoofingPlaceInfo.DEST_LAT,      mDestLat);
        intent.putExtra(SpoofingPlaceInfo.DEST_LNG,      mDestLong);
        intent.putExtra(SpoofingPlaceInfo.ORIGIN_ADDRESS, mOriginAddress);
        intent.putExtra(SpoofingPlaceInfo.DEST_ADDRESS,   mDestAddress);
        intent.putExtra(SpoofingPlaceInfo.TRANSPORT,      mTransport);

        // Zwischenstopps als parallele double[] Arrays
        if (!mWaypoints.isEmpty()) {
            double[] lats = new double[mWaypoints.size()];
            double[] lons = new double[mWaypoints.size()];
            for (int i = 0; i < mWaypoints.size(); i++) {
                lats[i] = mWaypoints.get(i).getLatitude();
                lons[i] = mWaypoints.get(i).getLongitude();
            }
            intent.putExtra(SpoofingPlaceInfo.WAYPOINTS_LATS, lats);
            intent.putExtra(SpoofingPlaceInfo.WAYPOINTS_LONS, lons);
        }

        mActivity.setResult(Activity.RESULT_OK, intent);
        mActivity.finishAfterTransition();
    }

    private void changeTransport(ERouteTransport transport) {
        this.mTransport = transport;
        mUserInterface.removeTransport(transport);
        mUserInterface.setTransport(transport);
    }

    private void getOriginAddress() {
        Intent intent = mActivity.getIntent();
        mUserInterface.setOriginAddress(intent.getStringExtra(SpoofingPlaceInfo.ORIGIN_ADDRESS));
        mOriginLat  = intent.getDoubleExtra(ListickApp.LATITUDE,  0d);
        mOriginLong = intent.getDoubleExtra(ListickApp.LONGITUDE, 0d);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        View view = mActivity.getCurrentFocus();
        if (view == null) view = new View(mActivity);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }
}
