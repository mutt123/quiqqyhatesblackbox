package com.quimodotcom.blackboxcure.Services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.quimodotcom.blackboxcure.MainServiceControl;
import com.quimodotcom.blackboxcure.R;
import com.quimodotcom.blackboxcure.SpooferControlOverlay;

/**
 * Foreground-Service, der das schwebende Kontroll-Overlay hält.
 * Starten:  startService(new Intent(ctx, SpooferControlService.class))
 * Stoppen:  stopService(new Intent(ctx, SpooferControlService.class))
 *
 * Kommuniziert mit laufenden Spoofer-Services per Broadcast:
 *   MainServiceControl.ACTION_OVERLAY_CONTROL
 *   Extras: KEY_PAUSE_TOGGLE (boolean) | KEY_SPEED_DELTA (int)
 *
 * Empfängt UI-Updates (Geschwindigkeit) über:
 *   MainServiceControl.UPDATE_UI_ACTION → Presenter überträgt Speed
 */
public class SpooferControlService extends Service implements SpooferControlOverlay.Callbacks {

    public static final String ACTION_SPEED_UPDATE = "com.quimodotcom.blackboxcure.overlay.speed_update";
    public static final String KEY_SPEED_KMH       = "overlay_speed_kmh";
    public static final String KEY_IS_PAUSED       = "overlay_is_paused";

    private SpooferControlOverlay mOverlay;
    private BroadcastReceiver     mSpeedReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();

        mOverlay = new SpooferControlOverlay(this, this);
        mOverlay.show();

        // Geschwindigkeits-Updates aus laufendem Service empfangen
        mSpeedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int speed    = intent.getIntExtra(KEY_SPEED_KMH, -1);
                int base    = intent.getIntExtra(MainServiceControl.KEY_BASE_SPEED, -1);  // ← NEU
                boolean paused = intent.getBooleanExtra(KEY_IS_PAUSED, false);
                if (mOverlay != null) {
                    if (speed >= 0) mOverlay.updateSpeed(speed);
                    if (base  >= 0) mOverlay.updateBaseSpeed(base);                        // ← NEU
                    mOverlay.setPaused(paused);
                }
            }
        };
        //registerReceiver(mSpeedReceiver, new IntentFilter(ACTION_SPEED_UPDATE));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mSpeedReceiver,
                    new IntentFilter(ACTION_SPEED_UPDATE),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mSpeedReceiver, new IntentFilter(ACTION_SPEED_UPDATE));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOverlay != null) mOverlay.dismiss();
        try { unregisterReceiver(mSpeedReceiver); } catch (IllegalArgumentException ignored) {}
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── SpooferControlOverlay.Callbacks ──────────────────────────

    @Override
    public void onPauseToggle(boolean pause) {
        Intent broadcast = new Intent(MainServiceControl.ACTION_OVERLAY_CONTROL);
        broadcast.putExtra(MainServiceControl.KEY_PAUSE_TOGGLE, pause);
        sendBroadcast(broadcast);
    }

    @Override
    public void onSpeedDelta(int deltaKmh) {
        Intent broadcast = new Intent(MainServiceControl.ACTION_OVERLAY_CONTROL);
        broadcast.putExtra(MainServiceControl.KEY_SPEED_DELTA, deltaKmh);
        sendBroadcast(broadcast);
    }

    @Override
    public void onClose() {
        stopSelf();
    }

    // ── Notification ─────────────────────────────────────────────

    private void startForegroundCompat() {
        String channelId = "spoofer_overlay_ctrl";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Spoofing-Steuerung", NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_near_me)
                .setContentTitle("Spoofing aktiv")
                .setContentText("Overlay aktiv – Pause & Geschwindigkeit steuerbar")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .build();
        startForeground(52, notification);
    }
}
