package com.quimodotcom.blackboxcure;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.content.Context.WINDOW_SERVICE;

/**
 * Schwebendes Kontroll-Overlay über anderen Apps.
 * Kollabiert → kleiner Hamburger-Pill.
 * Aufgeklappt → Pause/Resume + Geschwindigkeit ±5.
 *
 * Kommuniziert per Broadcast mit den laufenden Spoofer-Services.
 */
public class SpooferControlOverlay {

    private static final String PREFS   = "spoofer_overlay_prefs";
    private static final String KEY_X   = "overlay_x";
    private static final String KEY_Y   = "overlay_y";

    private final Context mContext;
    private final Callbacks mCallbacks;

    private WindowManager mWindowManager;
    private View mRoot;
    private WindowManager.LayoutParams mParams;

    private LinearLayout mCollapsed;
    private LinearLayout mExpanded;
    private TextView     mPauseBtn;
    private TextView     mSpeedLabel;
    private TextView     mWaypointContinueBtn;

    private boolean mIsPaused   = false;
    private int     mCurrentSpeedKmh = -1;
    private TextView mBaseSpeedLabel;

    public interface Callbacks {
        void onPauseToggle(boolean pause);
        void onSpeedDelta(int deltaKmh);
        void onClose();
        void onWaypointContinue();
    }

    public SpooferControlOverlay(Context context, Callbacks callbacks) {
        mContext   = context;
        mCallbacks = callbacks;
    }

    @SuppressLint({"ClickableViewAccessibility", "InflateParams"})
    public void show() {
        mRoot = LayoutInflater.from(mContext).inflate(R.layout.overlay_spoofer_control, null);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        mParams.gravity = Gravity.TOP | Gravity.START;

        // Letzte Position wiederherstellen
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mParams.x = prefs.getInt(KEY_X, 24);
        mParams.y = prefs.getInt(KEY_Y, 200);

        mWindowManager = (WindowManager) mContext.getSystemService(WINDOW_SERVICE);
        mWindowManager.addView(mRoot, mParams);

        mCollapsed  = mRoot.findViewById(R.id.overlay_collapsed);
        mExpanded   = mRoot.findViewById(R.id.overlay_expanded);
        mPauseBtn   = mRoot.findViewById(R.id.overlay_pause_btn);
        mSpeedLabel = mRoot.findViewById(R.id.overlay_speed_label);
        mBaseSpeedLabel = mRoot.findViewById(R.id.overlay_base_speed_label);
        mWaypointContinueBtn = mRoot.findViewById(R.id.overlay_waypoint_continue);

        setupDrag();
        setupClicks();
    }

    // ── Drag ─────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void setupDrag() {
        final int[] lastX = {0};
        final int[] lastY = {0};
        final boolean[] moved = {false};

        View.OnTouchListener dragListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastX[0] = (int) event.getRawX();
                    lastY[0] = (int) event.getRawY();
                    moved[0]  = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = (int) event.getRawX() - lastX[0];
                    int dy = (int) event.getRawY() - lastY[0];
                    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved[0] = true;
                    mParams.x += dx;
                    mParams.y += dy;
                    lastX[0] = (int) event.getRawX();
                    lastY[0] = (int) event.getRawY();
                    mWindowManager.updateViewLayout(mRoot, mParams);
                    return true;

                case MotionEvent.ACTION_UP:
                    savePosition();
                    if (!moved[0]) {
                        // Tap → expand/collapse
                        toggleExpand();
                    }
                    return true;
            }
            return false;
        };

        mCollapsed.setOnTouchListener(dragListener);
    }

    private void toggleExpand() {
        boolean expanding = mExpanded.getVisibility() != View.VISIBLE;
        mCollapsed.setVisibility(expanding ? View.GONE  : View.VISIBLE);
        mExpanded.setVisibility(expanding  ? View.VISIBLE : View.GONE);
    }

    // ── Button-Klicks ────────────────────────────────────────────

    private void setupClicks() {

        // NEU – Einklappen per Tap auf Header
        mRoot.findViewById(R.id.overlay_collapse_btn).setOnClickListener(v -> toggleExpand());

        mPauseBtn.setOnClickListener(v -> {
            mIsPaused = !mIsPaused;
            mCallbacks.onPauseToggle(mIsPaused);
            updatePauseLabel();
        });

        mRoot.findViewById(R.id.overlay_speed_minus).setOnClickListener(v -> {
            mCallbacks.onSpeedDelta(-1);
            if (mCurrentSpeedKmh > 0) {
                mCurrentSpeedKmh = Math.max(1, mCurrentSpeedKmh - 5);
                updateSpeedLabel();
            }
        });

        mRoot.findViewById(R.id.overlay_speed_plus).setOnClickListener(v -> {
            mCallbacks.onSpeedDelta(+1);
            if (mCurrentSpeedKmh > 0) {
                mCurrentSpeedKmh += 5;
                updateSpeedLabel();
            }
        });

        mRoot.findViewById(R.id.overlay_close_btn).setOnClickListener(v -> mCallbacks.onClose());

        if (mWaypointContinueBtn != null) {
            mWaypointContinueBtn.setOnClickListener(v -> {
                mCallbacks.onWaypointContinue();
                setWaypointWaiting(false);
            });
        }
    }

    // ── Öffentliche Update-Methoden ──────────────────────────────

    /** Wird vom Service aufgerufen wenn sich die angezeigte Geschwindigkeit ändert */
    public void updateSpeed(int speedKmh) {
        mCurrentSpeedKmh = speedKmh;
        if (mSpeedLabel != null) mRoot.post(this::updateSpeedLabel);
    }

    public void setPaused(boolean paused) {
        mIsPaused = paused;
        if (mPauseBtn != null) mRoot.post(this::updatePauseLabel);
    }

    private void updatePauseLabel() {
        mPauseBtn.setText(mIsPaused ? "▶  Fortsetzen" : "⏸  Pause");
    }

    private void updateSpeedLabel() {
        mSpeedLabel.setText(mCurrentSpeedKmh > 0 ? mCurrentSpeedKmh + " km/h" : "–");
    }

    // ── Aufräumen ────────────────────────────────────────────────

    public void dismiss() {
        if (mWindowManager != null && mRoot != null) {
            try {
                mWindowManager.removeView(mRoot);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void savePosition() {
        mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_X, mParams.x)
                .putInt(KEY_Y, mParams.y)
                .apply();
    }
    // Neue Methode:
    public void updateBaseSpeed(int baseKmh) {
        mBaseSpeedLabel.post(() ->
                mBaseSpeedLabel.setText("Basis: " + baseKmh + " km/h"));
    }

    /** Zeigt/versteckt den Waypoint-„Weiter"-Button im Overlay */
    public void setWaypointWaiting(boolean waiting) {
        if (mWaypointContinueBtn == null) return;
        mRoot.post(() -> {
            mWaypointContinueBtn.setVisibility(waiting ? View.VISIBLE : View.GONE);
            // Overlay aufklappen damit der Button sichtbar ist
            if (waiting) {
                mCollapsed.setVisibility(View.GONE);
                mExpanded.setVisibility(View.VISIBLE);
            }
        });
    }
}
