package com.quimodotcom.blackboxcure.Contract;

import com.quimodotcom.blackboxcure.Enumerations.ERouteTransport;

public interface SearchImpl {

    interface UI {
        void setOriginAddress(String address);
        void setDestAddress(String address);
        void setTransport(ERouteTransport transport);
        void removeTransport(ERouteTransport transport);

        /** Fügt eine neue Zeile für Zwischenstopp[index] ein */
        void addWaypointRow(int index, String address);

        /** Entfernt die Zeile für Zwischenstopp[index] */
        void removeWaypointRow(int index);

        /** Aktualisiert die Adressanzeige von Zwischenstopp[index] */
        void updateWaypoint(int index, String address);
    }

    interface Presenter {
        void onActivityLoad();
        void onOrigin();
        void onDestination();
        void onContinue();
        void selectOnMap();
        void onTransport(ERouteTransport transport);
        void onActivityResult(int requestCode, int resultCode, android.content.Intent data);
    }
}
