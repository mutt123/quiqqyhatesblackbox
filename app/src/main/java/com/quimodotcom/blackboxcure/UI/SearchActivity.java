package com.quimodotcom.blackboxcure.UI;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.quimodotcom.blackboxcure.Contract.SearchImpl;
import com.quimodotcom.blackboxcure.Enumerations.ERouteTransport;
import com.quimodotcom.blackboxcure.ListickApp;
import com.quimodotcom.blackboxcure.OnSingleClickListener;
import com.quimodotcom.blackboxcure.Presenter.SearchPresenter;
import com.quimodotcom.blackboxcure.R;
import com.quimodotcom.blackboxcure.SpoofingPlaceInfo;

import java.util.ArrayList;
import java.util.List;

public class SearchActivity extends Activity implements SearchImpl.UI {

    public static final String ADD_MORE_ROUTE    = "add_more_route";
    public static final int    ACTIVITY_REQUEST_CODE = 1;

    private EditText origin;
    private EditText destination;

    private SearchPresenter presenter;

    private ImageView car;
    private ImageView bike;
    private ImageView walk;

    /** Container in search_activity.xml – Zeilen für Zwischenstopps */
    private LinearLayout waypointsContainer;

    /** Parallellist zu mWaypoints im Presenter – enthält die erstellten Zeilen-Views */
    private final List<View> waypointViews = new ArrayList<>();

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    public static void startActivity(Activity activity, String originAddress,
                                     double latitude, double longitude,
                                     boolean addRoute, Bundle options) {
        activity.startActivityForResult(
                new Intent(activity, SearchActivity.class)
                        .putExtra(SpoofingPlaceInfo.ORIGIN_ADDRESS, originAddress)
                        .putExtra(ListickApp.LATITUDE,  latitude)
                        .putExtra(ListickApp.LONGITUDE, longitude)
                        .putExtra(ADD_MORE_ROUTE, addRoute),
                ACTIVITY_REQUEST_CODE, options);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_activity);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);

        presenter = new SearchPresenter(this, this);

        origin      = findViewById(R.id.origin);
        destination = findViewById(R.id.destination);
        car         = findViewById(R.id.transport_car);
        bike        = findViewById(R.id.transport_bike);
        walk        = findViewById(R.id.transport_walk);

        // Container muss in search_activity.xml vorhanden sein (kann auch fehlen – dann kein Crash)
        waypointsContainer = findViewById(R.id.waypoints_container);

        RelativeLayout selectOnMap = findViewById(R.id.select_on_map);
        Button         nextAction  = findViewById(R.id.next_action);
        View           addWpBtn    = findViewById(R.id.add_waypoint_btn);

        car.setOnClickListener(v  -> presenter.onTransport(ERouteTransport.ROUTE_CAR));
        bike.setOnClickListener(v -> presenter.onTransport(ERouteTransport.ROUTE_BIKE));
        walk.setOnClickListener(v -> presenter.onTransport(ERouteTransport.ROUTE_WALK));

        destination.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) presenter.onDestination();
            return true;
        });
        origin.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) presenter.onOrigin();
            return true;
        });

        if (selectOnMap != null)
            selectOnMap.setOnClickListener(new OnSingleClickListener() {
                @Override public void onSingleClick(View v) { presenter.selectOnMap(); }
            });

        if (nextAction != null)
            nextAction.setOnClickListener(new OnSingleClickListener() {
                @Override public void onSingleClick(View v) { presenter.onContinue(); }
            });

        // „+ Zwischenstopp" Button
        if (addWpBtn != null)
            addWpBtn.setOnClickListener(v -> presenter.addWaypoint());

        presenter.onActivityLoad();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        presenter.onActivityResult(requestCode, resultCode, data);
    }

    // ── SearchImpl.UI ─────────────────────────────────────────────

    @Override public void setOriginAddress(String address) { if (origin != null) origin.setText(address); }
    @Override public void setDestAddress(String address)   { if (destination != null) destination.setText(address); }

    @Override
    public void setTransport(ERouteTransport transport) {
        resetTransportAlpha();
        switch (transport) {
            case ROUTE_CAR:  if (car  != null) car.setAlpha(1f);  break;
            case ROUTE_BIKE: if (bike != null) bike.setAlpha(1f); break;
            case ROUTE_WALK: if (walk != null) walk.setAlpha(1f); break;
        }
    }

    @Override public void removeTransport(ERouteTransport transport) { resetTransportAlpha(); }

    @Override
    public void addWaypointRow(int index, String address) {
        runOnUiThread(() -> {
            if (waypointsContainer == null) return;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setPadding(0, 8, 0, 8);

            TextView label = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            label.setLayoutParams(lp);
            label.setText(address.isEmpty() ? getString(R.string.tap_to_select_waypoint) : address);
            label.setTextColor(Color.WHITE);
            label.setPadding(16, 8, 8, 8);
            label.setOnClickListener(v -> presenter.editWaypoint(waypointViews.indexOf(row)));

            TextView removeBtn = new TextView(this);
            removeBtn.setText("×");
            removeBtn.setTextSize(20f);
            removeBtn.setTextColor(Color.parseColor("#FF5252"));
            removeBtn.setPadding(16, 8, 16, 8);
            removeBtn.setOnClickListener(v -> presenter.removeWaypoint(waypointViews.indexOf(row)));

            row.addView(label);
            row.addView(removeBtn);

            waypointViews.add(row);
            waypointsContainer.addView(row);
        });
    }

    @Override
    public void removeWaypointRow(int index) {
        runOnUiThread(() -> {
            if (index < 0 || index >= waypointViews.size()) return;
            View row = waypointViews.remove(index);
            if (waypointsContainer != null) waypointsContainer.removeView(row);
        });
    }

    @Override
    public void updateWaypoint(int index, String address) {
        runOnUiThread(() -> {
            if (index < 0 || index >= waypointViews.size()) return;
            View row = waypointViews.get(index);
            if (row instanceof LinearLayout) {
                View child = ((LinearLayout) row).getChildAt(0);
                if (child instanceof TextView) ((TextView) child).setText(address);
            }
        });
    }

    // ── Hilfe ─────────────────────────────────────────────────────

    private void resetTransportAlpha() {
        if (car  != null) car.setAlpha(0.4f);
        if (bike != null) bike.setAlpha(0.4f);
        if (walk != null) walk.setAlpha(0.4f);
    }
}
