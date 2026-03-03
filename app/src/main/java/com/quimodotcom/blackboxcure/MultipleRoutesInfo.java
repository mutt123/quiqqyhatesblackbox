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
    private ArrayList<Integer> mSpeedLimits;
    private boolean mFollowSpeedLimits = false;
    private boolean mSmoothTurns = false;
    private int mPauseSeconds = -1;
    private int mSpeed = -1;
    private int mSpeedDiff = -1;

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
        mSpeedLimits = (ArrayList<Integer>) in.readSerializable();
        mFollowSpeedLimits = in.readByte() != 0;
        mSmoothTurns = in.readByte() != 0;
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
        parcel.writeSerializable(mSpeedLimits);
        parcel.writeByte((byte) (mFollowSpeedLimits ? 1 : 0));
        parcel.writeByte((byte) (mSmoothTurns ? 1 : 0));
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

    public void setSpeedLimits(List<Integer> speedLimits) {
        this.mSpeedLimits = (ArrayList<Integer>) speedLimits;
    }

    public List<Integer> getSpeedLimits() {
        return mSpeedLimits;
    }

    public void setFollowSpeedLimits(boolean follow) {
        this.mFollowSpeedLimits = follow;
    }

    public boolean getFollowSpeedLimits() {
        return mFollowSpeedLimits;
    }

    public void setSmoothTurns(boolean smoothTurns) {
        this.mSmoothTurns = smoothTurns;
    }

    public boolean getSmoothTurns() {
        return mSmoothTurns;
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
