package com.quimodotcom.blackboxcure.UI;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.RadioGroup;
import com.quimodotcom.blackboxcure.MultipleRoutesInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.facebook.shimmer.ShimmerFrameLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

import com.quimodotcom.blackboxcure.AppPreferences;
import com.quimodotcom.blackboxcure.Contract.RouteSettingsImpl;
import com.quimodotcom.blackboxcure.BlackBoxCureApp;
import com.quimodotcom.blackboxcure.OnSingleClickListener;
import com.quimodotcom.blackboxcure.Presenter.RouteSettingsPresenter;
import com.quimodotcom.blackboxcure.R;

public class RouteSettingsActivity extends FragmentActivity implements RouteSettingsImpl.UI {

    private TextView mPauseAtStartingTimer;
    private TextView mLatestPointDelayTimer;
    private EditText speedField;
    private EditText differenceField;

    private EditText elevation;
    private EditText elevationDiff;
    private MaterialButton mContinue;

    private RadioGroup mLoopModeGroup;
    private android.widget.LinearLayout mLoopCountContainer;
    private EditText mLoopCountField;
    private MaterialCheckBox mSmoothTurns;
    private android.widget.Spinner mWaypointNotifySpinner;

    private View mPauseAtStartingContainer;
    private View mLatestPointDelayContainer;
    private ShimmerFrameLayout mDetectingAltitude;

    private android.widget.SeekBar mOriginSeekBar;
    private android.widget.SeekBar mDestSeekBar;
    private TextView mOriginTimerLabel;
    private TextView mDestTimerLabel;

    // Jeder Schritt = 5 Sekunden (max = 60 Schritte → 300 s = 5 min)
    private static final int TIMER_STEP_SEC = 5;

    private RouteSettingsPresenter mPresenter;

    public static void startActivity(Activity activity, double latitude, double longitude, double distance, boolean isRoute, boolean addMoreRoute, int requestCode) {
        activity.startActivityForResult(new Intent(activity, RouteSettingsActivity.class)
                .putExtra(BlackBoxCureApp.LATITUDE, latitude)
                .putExtra(BlackBoxCureApp.LONGITUDE, longitude)
                .putExtra(BlackBoxCureApp.DISTANCE, distance)
                .putExtra(RouteSettingsPresenter.ADD_MORE_ROUTE, addMoreRoute)
                .putExtra(RouteSettingsPresenter.IS_ROUTE, isRoute), requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
/*
        if (requestCode == CaptchaActivity.ACTIVITY_REQUEST_CODE) {
            if (mPresenter != null && resultCode == RESULT_OK) {
                String challengeResult = data.getStringExtra(CaptchaActivity.KEY_CAPTCHA_RESULT);
                mPresenter.onChallengePassed(challengeResult);
            } else if (mPresenter != null) {
                stopAltitudeDetection();
                mPresenter.setElevation();
                onAltitudeDetermined(false, false);
            }
        }
*/
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.route_activity);

        mContinue = findViewById(R.id.continue_action);
        View back = findViewById(R.id.back);

        speedField = findViewById(R.id.speed);
        differenceField = findViewById(R.id.speed_difference);
        elevation = findViewById(R.id.elevation);
        elevationDiff = findViewById(R.id.elevation_different);
        mLoopModeGroup    = findViewById(R.id.loop_mode_group);
        mLoopCountContainer = findViewById(R.id.loop_count_container);
        mLoopCountField   = findViewById(R.id.loop_count);

        mLoopModeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            boolean showCount = (checkedId == R.id.loop_pingpong || checkedId == R.id.loop_circle);
            mLoopCountContainer.setVisibility(showCount ? View.VISIBLE : View.GONE);
        });
        mSmoothTurns = findViewById(R.id.smooth_turns_checkbox);
        mDetectingAltitude = findViewById(R.id.detecting_altitude);
        mWaypointNotifySpinner = findViewById(R.id.waypoint_notify_spinner);

        mLatestPointDelayContainer = findViewById(R.id.delay_at_the_last_point);
        mLatestPointDelayTimer = findViewById(R.id.datlp_timepicker);

        mPauseAtStartingContainer = findViewById(R.id.pause_at_starting);
        mPauseAtStartingTimer = findViewById(R.id.parking_time);

        mDetectingAltitude.hideShimmer();

        TextView speedUnit = findViewById(R.id.speed_unit);
        TextView speedDiffUnit = findViewById(R.id.speed_diff_unit);

        String unitName = AppPreferences.getUnitName(this, AppPreferences.getStandartUnit(this));
        speedUnit.setText(unitName);
        speedDiffUnit.setText(unitName);

        mPresenter = new RouteSettingsPresenter(this);

        mContinue.setOnClickListener(new OnSingleClickListener() {
            @Override
            public void onSingleClick(View v) {
                boolean speedValid = !speedField.getText().toString().isEmpty() && TextUtils.isDigitsOnly(speedField.getText().toString());
                boolean diffValid = !differenceField.getText().toString().isEmpty() && TextUtils.isDigitsOnly(differenceField.getText().toString());

                if (speedValid && diffValid) {
                    String elevationStr = RouteSettingsActivity.this.elevation.getText().toString();
                    String elevationDiffStr = RouteSettingsActivity.this.elevationDiff.getText().toString();

                    float elevation = 0;
                    float elevationDiff = 0;

                    if (!elevationStr.isEmpty()) {
                        try {
                            elevation = Float.parseFloat(elevationStr);
                        } catch (NumberFormatException e) {
                            UIEffects.TextView.attachErrorWithShake(RouteSettingsActivity.this, RouteSettingsActivity.this.elevation, () -> {});
                            return;
                        }
                    }

                    if (!elevationDiffStr.isEmpty()) {
                        try {
                            elevationDiff = Float.parseFloat(elevationDiffStr);
                        } catch (NumberFormatException e) {
                            UIEffects.TextView.attachErrorWithShake(RouteSettingsActivity.this, RouteSettingsActivity.this.elevationDiff, () -> {});
                            return;
                        }
                    }

                    int currentSpeed = Integer.parseInt(speedField.getText().toString());
                    int currentDiff = Integer.parseInt(differenceField.getText().toString());
                    mPresenter.onContinueClick(currentSpeed, currentDiff, elevation, elevationDiff,
                            getLoopMode(), getLoopCount(), mSmoothTurns.isChecked(), getWaypointNotifyMode());
                }

                mPresenter.saveElevation(Float.parseFloat(elevation.getText().toString()), Float.parseFloat(elevationDiff.getText().toString()));


            }
        });

        back.setOnClickListener(new OnSingleClickListener() {
            @Override
            public void onSingleClick(View v) {
                mPresenter.onCancelClick();
            }
        });

        mOriginSeekBar  = findViewById(R.id.parking_time_seekbar);
        mOriginTimerLabel = mPauseAtStartingTimer;   // TextView zeigt "0 s"
        mDestSeekBar    = findViewById(R.id.datlp_seekbar);
        mDestTimerLabel = mLatestPointDelayTimer;

        mOriginSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                int secs = p * TIMER_STEP_SEC;
                mOriginTimerLabel.setText(secs == 0 ? "0 s" : (secs >= 60 ? (secs / 60) + " min " + (secs % 60) + " s" : secs + " s"));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });

        mDestSeekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                int secs = p * TIMER_STEP_SEC;
                mDestTimerLabel.setText(secs == 0 ? "0 s" : (secs >= 60 ? (secs / 60) + " min " + (secs % 60) + " s" : secs + " s"));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });

        mLatestPointDelayContainer.setVisibility(View.GONE);

        mPresenter.onActivityLoad();
    }

    @Override
    public void getSpeed(int speed) {
        speedField.setText(String.valueOf(speed));
    }

    @Override
    public void getSpeedDifference(int difference) {
        differenceField.setText(String.valueOf(difference));
    }

    @Override
    public void pushDifferenceError() {
        Toast.makeText(this, R.string.difference_error, Toast.LENGTH_SHORT).show();

        UIEffects.TextView.attachErrorWithShake(this, differenceField, () -> {
        });
    }

    @Override
    public void getElevation(float elevation, float difference) {
        this.elevation.setText(String.valueOf(elevation));
        this.elevationDiff.setText(String.valueOf(difference));
    }

    @Override
    public void startAltitudeDetection() {
        mContinue.setEnabled(false);
        mContinue.setText(R.string.detecting_altitude);
        mDetectingAltitude.showShimmer(true);
    }

    @Override
    public void stopAltitudeDetection() {
        mContinue.setEnabled(true);
        mContinue.setText(R.string.continue_text);
        mDetectingAltitude.hideShimmer();
        elevation.startAnimation(AnimationUtils.loadAnimation(this, R.anim.attenuation));
    }

    @Override
    public void onAltitudeDetermined(boolean success, boolean isRoute) {
        PrettyToast.show(this, success ? getString(R.string.altitude_determined) : getString(R.string.altitude_detection_failed), R.drawable.ic_terrain);

        LinearLayout elevationController = findViewById(R.id.elevation_controller);
        TextView autoAltitude = findViewById(R.id.auto_elevation);

        if (success && isRoute) {
            elevationController.setVisibility(View.GONE);
            autoAltitude.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setFixedMode() {
        LinearLayout speedContainer = findViewById(R.id.speeds_container);
        TextView activityTitle = findViewById(R.id.title);
        activityTitle.setText(R.string.spoofing);
        speedContainer.setVisibility(View.GONE);
        View loopContainer = findViewById(R.id.loop_container);
        if (loopContainer != null) loopContainer.setVisibility(View.GONE);
        mPauseAtStartingContainer.setVisibility(View.GONE);
        mLatestPointDelayContainer.setVisibility(View.GONE);
        View wpNotify = findViewById(R.id.waypoint_notify_container);
        if (wpNotify != null) wpNotify.setVisibility(View.GONE);
    }

    @Override
    public void addMoreRoute() {
        View loopContainer = findViewById(R.id.loop_container);
        if (loopContainer != null) loopContainer.setVisibility(View.GONE);
        mLatestPointDelayContainer.setVisibility(View.GONE);
    }

    @Override
    public int getOriginTimerMinutes() {
        int secs = (mOriginSeekBar != null ? mOriginSeekBar.getProgress() : 0) * TIMER_STEP_SEC;
        return secs / 60;
    }

    @Override
    public int getOriginTimerSeconds() {
        int secs = (mOriginSeekBar != null ? mOriginSeekBar.getProgress() : 0) * TIMER_STEP_SEC;
        return secs % 60;
    }

    @Override
    public int getDestTimerMinutes() {
        int secs = (mDestSeekBar != null ? mDestSeekBar.getProgress() : 0) * TIMER_STEP_SEC;
        return secs / 60;
    }

    @Override
    public int getDestTimerSeconds() {
        int secs = (mDestSeekBar != null ? mDestSeekBar.getProgress() : 0) * TIMER_STEP_SEC;
        return secs % 60;
    }

    public int getLoopMode() {
        if (mLoopModeGroup == null) return MultipleRoutesInfo.LOOP_OFF;
        int id = mLoopModeGroup.getCheckedRadioButtonId();
        if (id == R.id.loop_pingpong) return MultipleRoutesInfo.LOOP_PINGPONG;
        if (id == R.id.loop_circle)   return MultipleRoutesInfo.LOOP_CIRCLE;
        return MultipleRoutesInfo.LOOP_OFF;
    }

    public int getLoopCount() {
        if (mLoopCountField == null) return 0;
        try { return Math.max(0, Integer.parseInt(mLoopCountField.getText().toString().trim())); }
        catch (NumberFormatException e) { return 0; }
    }

    @Override
    public int getWaypointNotifyMode() {
        if (mWaypointNotifySpinner == null) return 0;
        return mWaypointNotifySpinner.getSelectedItemPosition(); // 0=keine,1=ton,2=vibration,3=beides
    }


}