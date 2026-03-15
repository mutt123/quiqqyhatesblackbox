package com.quimodotcom.blackboxcure;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.quimodotcom.blackboxcure.Services.FixedSpooferService;
import com.quimodotcom.blackboxcure.Services.ISpooferService;
import com.quimodotcom.blackboxcure.Services.JoystickService;
import com.quimodotcom.blackboxcure.Services.RouteSpooferService;
import com.quimodotcom.blackboxcure.Services.SpooferControlService;
import com.quimodotcom.blackboxcure.UI.MapsActivity;

public class MainServiceControl {

    /** Bestehende Broadcasts */
    public static final String SERVICE_CONTROL_ACTION = "com.quimodotcom.blackboxcure.actionservice.daemons.ctrl";

    /** Overlay → Spoofer-Services: Pause oder Geschwindigkeit ändern */
    public static final String ACTION_OVERLAY_CONTROL = "com.quimodotcom.blackboxcure.actionservice.overlay.ctrl";
    public static final String KEY_PAUSE_TOGGLE       = "overlay_pause_toggle";
    public static final String KEY_SPEED_DELTA        = "overlay_speed_delta";
    public static final String KEY_BASE_SPEED = "overlay_base_speed";

    private final Context mContext;

    public MainServiceControl(Context context) {
        mContext = context;
        IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICE_CONTROL_ACTION);
    }

    // ── Koordinaten an laufenden Fixed-Service schicken ──────────
    public void sendNewCoordinates(double latitude, double longitude) {
        Intent local = new Intent();
        local.setAction(SERVICE_CONTROL_ACTION);
        local.putExtra(ListickApp.LATITUDE, latitude);
        local.putExtra(ListickApp.LONGITUDE, longitude);
        mContext.sendBroadcast(local);
    }

    // ── Overlay-Service starten / stoppen ────────────────────────
    public static void startOverlay(Context context) {
        Intent i = new Intent(context, SpooferControlService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(i);
        else
            context.startService(i);
    }

    public static void stopOverlay(Context context) {
        context.stopService(new Intent(context, SpooferControlService.class));
    }

    /**
     * Geschwindigkeit und Pause-Status an SpooferControlService melden,
     * damit das Overlay aktuell bleibt.
     * Aufruf aus RouteSpooferService.updateUI() oder FixedSpooferService.
     */
    public static void notifyOverlaySpeed(Context context, int speedKmh,int baseSpeed, boolean paused) {
        Intent i = new Intent(SpooferControlService.ACTION_SPEED_UPDATE);
        i.putExtra(SpooferControlService.KEY_SPEED_KMH,  speedKmh);
        i.putExtra(SpooferControlService.KEY_IS_PAUSED,  paused);
        i.putExtra(KEY_BASE_SPEED, baseSpeed);          // ← NEU
        context.sendBroadcast(i);
    }

    // ── Service-Status-Checks ────────────────────────────────────
    public static boolean isRouteSpoofingServiceRunning(Context context) {
        return PermissionManager.isServiceRunning(context, RouteSpooferService.class);
    }
    public static boolean isFixedSpoofingServiceRunning(Context context) {
        return PermissionManager.isServiceRunning(context, FixedSpooferService.class);
    }
    public static boolean isJoystickSpoofingRunning(Context context) {
        return PermissionManager.isServiceRunning(context, JoystickService.class);
    }

    // ── AIDL-Wrapper ─────────────────────────────────────────────
    public void setPause(ISpooferService service, boolean pause) {
        try { service.setPause(pause); }
        catch (Exception e) { android.util.Log.d(BuildConfig.APPLICATION_ID, null, e); }
    }

    public boolean isPaused(ISpooferService service) {
        try { return service.isPaused(); }
        catch (Exception e) { android.util.Log.d(BuildConfig.APPLICATION_ID, null, e); }
        return false;
    }

    // ── Foreground-Notification für Spoofer-Services ─────────────
    public static void startServiceForeground(Service context) {
        String CHANNEL_ID = "com.quimodotcom.blackboxcure_SPOOFING_STATUS";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Spoofing Status", NotificationManager.IMPORTANCE_LOW);
            channel.setLightColor(Color.BLUE);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(context, MapsActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE
                        : 0);

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.spoofing))
                .setContentText(context.getString(R.string.notify_status_description))
                .setSmallIcon(R.drawable.ic_near_me)
                .setContentIntent(pendingIntent)
                .setSilent(true)
                .build();

        context.startForeground(1, notification);
    }
}
