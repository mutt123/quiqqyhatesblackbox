package project.listick.fakegps;

import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;

public class NmeaParser {
    public static Location parse(String nmea) {
        if (nmea == null || nmea.isEmpty()) return null;

        String[] tokens = nmea.split(",");
        try {
            if (nmea.startsWith("$GPRMC") || nmea.startsWith("$GNRMC") || nmea.startsWith("$GLRMC")) {
                if (tokens.length < 10 || !tokens[2].equals("A")) return null; // V = Void, A = Active

                Location loc = new Location(LocationManager.GPS_PROVIDER);
                double lat = parseCoordinate(tokens[3], tokens[4]);
                double lon = parseCoordinate(tokens[5], tokens[6]);

                loc.setLatitude(lat);
                loc.setLongitude(lon);

                if (!tokens[7].isEmpty()) {
                    double speedKnots = Double.parseDouble(tokens[7]);
                    loc.setSpeed((float) (speedKnots * 0.514444)); // Knots to m/s
                }

                if (!tokens[8].isEmpty()) {
                    loc.setBearing(Float.parseFloat(tokens[8]));
                }

                loc.setTime(System.currentTimeMillis());
                loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                loc.setAccuracy(10.0f); // Default accuracy

                return loc;
            }
            else if (nmea.startsWith("$GPGGA") || nmea.startsWith("$GNGGA") || nmea.startsWith("$GLGGA")) {
                if (tokens.length < 10 || tokens[6].equals("0")) return null; // 0 = Fix not available

                Location loc = new Location(LocationManager.GPS_PROVIDER);
                double lat = parseCoordinate(tokens[2], tokens[3]);
                double lon = parseCoordinate(tokens[4], tokens[5]);

                loc.setLatitude(lat);
                loc.setLongitude(lon);

                if (!tokens[9].isEmpty()) {
                    loc.setAltitude(Double.parseDouble(tokens[9]));
                }

                loc.setTime(System.currentTimeMillis());
                loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                loc.setAccuracy(10.0f);

                return loc;
            }
        } catch (Exception e) {
            Log.e("NmeaParser", "Failed to parse NMEA: " + nmea, e);
        }
        return null;
    }

    private static double parseCoordinate(String coord, String dir) {
        if (coord == null || coord.isEmpty()) return 0.0;
        int dotIndex = coord.indexOf('.');
        if (dotIndex < 2) return 0.0;

        int degrees = Integer.parseInt(coord.substring(0, dotIndex - 2));
        double minutes = Double.parseDouble(coord.substring(dotIndex - 2));
        double decimal = degrees + (minutes / 60.0);

        if (dir.equals("S") || dir.equals("W")) {
            decimal = -decimal;
        }
        return decimal;
    }
}
