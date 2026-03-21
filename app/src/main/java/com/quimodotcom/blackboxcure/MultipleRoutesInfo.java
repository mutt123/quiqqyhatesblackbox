package com.quimodotcom.blackboxcure;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
import java.util.List;

import com.quimodotcom.blackboxcure.Enumerations.ERouteTransport;
import com.quimodotcom.blackboxcure.RouteMarker.OriginAndDestMarker;

public class MultipleRoutesInfo implements Parcelable {

    private final OriginAndDestMarker originAndDestMarkers = new OriginAndDestMarker();

    private ArrayList<GeoPoint> mRoute;
    private boolean mSmoothTurns = false;
    private int mPauseSeconds = -1;
    private int mSpeed = -1;
    private int mSpeedDiff = -1;

    /** Wiederholungs-Modus: 0=aus, 1=Ping-Pong (A↔B), 2=Schleife (A→B→zurück→A) */
    public static final int LOOP_OFF      = 0;
    public static final int LOOP_PINGPONG = 1;
    public static final int LOOP_CIRCLE   = 2;
    private int mLoopMode  = LOOP_OFF;
    /** Anzahl Durchgänge, 0 = endlos */
    private int mLoopCount = 0;

    private ArrayList<GeoPoint> mWaypoints = new ArrayList<>();
    /** Notification mode at waypoints: 0=none, 1=sound, 2=vibration, 3=both */
    private int mWaypointNotifyMode = 0;

    private float mElevation = -1;
    private float mElevationDiff = -1;

    private String mAddress;
    private double mDistance;
    private ERouteTransport mTransport;

    public MultipleRoutesInfo() {

    }

    MultipleRoutesInfo(Parcel in) {
        mRoute = (ArrayList<GeoPoint>) in.readSerializable();
        mPauseSeconds = in.readInt();
        mSpeed = in.readInt();
        mSpeedDiff = in.readInt();
        mElevation = in.readFloat();
        mElevationDiff = in.readFloat();
        mAddress = in.readString();
        mDistance = in.readDouble();
        mTransport = (ERouteTransport) in.readSerializable();
        mSmoothTurns = in.readByte() != 0;
        mLoopMode    = in.readInt();
        mLoopCount   = in.readInt();
        mWaypointNotifyMode = in.readInt();
        int wpCount = in.readInt();
        mWaypoints = new ArrayList<>();
        for (int i = 0; i < wpCount; i++) {
            mWaypoints.add(new GeoPoint(in.readDouble(), in.readDouble()));
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeSerializable(mRoute);
        parcel.writeInt(mPauseSeconds);
        parcel.writeInt(mSpeed);
        parcel.writeInt(mSpeedDiff);
        parcel.writeFloat(mElevation);
        parcel.writeFloat(mElevationDiff);
        parcel.writeString(mAddress);
        parcel.writeDouble(mDistance);
        parcel.writeSerializable(mTransport);
        parcel.writeByte((byte) (mSmoothTurns ? 1 : 0));
        parcel.writeInt(mLoopMode);
        parcel.writeInt(mLoopCount);
        parcel.writeInt(mWaypointNotifyMode);
        parcel.writeInt(mWaypoints == null ? 0 : mWaypoints.size());
        if (mWaypoints != null) {
            for (GeoPoint wp : mWaypoints) {
                parcel.writeDouble(wp.getLatitude());
                parcel.writeDouble(wp.getLongitude());
            }
        }
    }

    public int getStartingPauseTime() {
        return mPauseSeconds;
    }

    public int getSpeed() {
        return mSpeed;
    }

    public int getSpeedDiff() {
        return mSpeedDiff;
    }

    public float getElevation() {
        return mElevation;
    }

    public double getDistance() {
        return mDistance;
    }

    public float getElevationDiff() {
        return mElevationDiff;
    }

    public List<GeoPoint> getRoute() {
        return mRoute;
    }

    public void setStartingPauseTime(int pauseTime) {
        this.mPauseSeconds = pauseTime;
    }

    public void setSpeed(int speed) {
        this.mSpeed = speed;
    }

    public void setSpeedDiff(int speedDiff) {
        this.mSpeedDiff = speedDiff;
    }

    public void setElevation(float elevation) {
        this.mElevation = elevation;
    }

    public void setElevationDiff(float elevationDiff) {
        this.mElevationDiff = elevationDiff;
    }

    public void setRoute(List<GeoPoint> route) {
        this.mRoute = (ArrayList<GeoPoint>) route;
    }

    public void setSmoothTurns(boolean smoothTurns) {
        this.mSmoothTurns = smoothTurns;
    }

    public boolean getSmoothTurns() {
        return mSmoothTurns;
    }

    public void setLoopMode(int mode)  { this.mLoopMode  = mode; }
    public int  getLoopMode()          { return mLoopMode; }
    public void setLoopCount(int count){ this.mLoopCount = count; }
    public int  getLoopCount()         { return mLoopCount; }

    public void setWaypoints(List<GeoPoint> waypoints) {
        this.mWaypoints = waypoints != null ? new ArrayList<>(waypoints) : new ArrayList<>();
    }

    public ArrayList<GeoPoint> getWaypoints() {
        return mWaypoints != null ? mWaypoints : new ArrayList<>();
    }

    public void setWaypointNotifyMode(int mode) {
        this.mWaypointNotifyMode = mode;
    }

    public int getWaypointNotifyMode() {
        return mWaypointNotifyMode;
    }

    public void setDistance(double distance) {
        this.mDistance = distance;
    }

    public void setAddress(String address) {
        this.mAddress = address;
    }

    public void setTransport(ERouteTransport transport) {
        this.mTransport = transport;
    }

    public ERouteTransport getTransport() {
        return mTransport;
    }

    public String getAddress() {
        return mAddress;
    }

    public OriginAndDestMarker getRouteMarker(Context context) {
        RouteMarker origin = new RouteMarker(RouteMarker.Type.SOURCE);
        origin.setPosition(mRoute.get(0).getLatitude(), mRoute.get(0).getLongitude());

        RouteMarker dest = new RouteMarker(RouteMarker.Type.DEST);
        dest.setPosition(mRoute.get(mRoute.size() - 1).getLatitude(), mRoute.get(mRoute.size() - 1).getLongitude());

        originAndDestMarkers.origin = origin;
        originAndDestMarkers.dest = dest;

        return originAndDestMarkers;
    }

    public static Creator<MultipleRoutesInfo> CREATOR = new Creator<MultipleRoutesInfo>() {
        public MultipleRoutesInfo createFromParcel(Parcel parcel) {
            return new MultipleRoutesInfo(parcel);
        }

        public MultipleRoutesInfo[] newArray(int size) {
            return new MultipleRoutesInfo[size];
        }
    };


}