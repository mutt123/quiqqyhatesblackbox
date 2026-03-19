package com.quimodotcom.blackboxcure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


//neuer block

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

// ender neuer block


/**
 * Zeigt Pokéstops, Arenen und Kraftquellen von demap.co auf der Karte.
 *
 * Einbinden in MapsPresenter:
 *   private DemapOverlay mDemapOverlay;
 *
 * Im Konstruktor nach registerReceiver():
 *   mDemapOverlay = new DemapOverlay(context);
 *   mDemapOverlay.setEnabled(false);
 *   mMap.getOverlays().add(mDemapOverlay);
 *
 * In onMapDrag() am Ende:
 *   if (mDemapOverlay != null && mDemapOverlay.isEnabled())
 *       mDemapOverlay.refresh(mMap);
 */
public class DemapOverlay extends Overlay {

    private static final String TAG        = "DemapOverlay";
    private static final String GRAPHQL    = "https://demap.co/graphql";
    private static final double MIN_ZOOM   = 14.0;

    // Teamfarben: 0=Grau, 1=Mystic(blau), 2=Valor(rot), 3=Instinct(gelb)
    private static final int[] GYM_COLORS = {
        0xFFAAAAAA, 0xFF4B78FF, 0xFFFF4444, 0xFFFFCC00
    };

    private static final int COLOR_STOP   = 0xFF3B9EFF;
    private static final int COLOR_STATION = 0xFFAA44FF;
    private static final int COLOR_STROKE  = 0xFFFFFFFF;

    // Layer-Toggles
    private boolean mShowPokestops = true;
    private boolean mShowGyms      = true;
    private boolean mShowStations  = true;

    // Daten
    private final List<PoiItem> mPokestops = new ArrayList<>();
    private final List<GymItem> mGyms      = new ArrayList<>();
    private final List<PoiItem> mStations  = new ArrayList<>();

    private final Context         mContext;
    private final ExecutorService mExecutor    = Executors.newSingleThreadExecutor();
    private final Handler         mMainHandler = new Handler(Looper.getMainLooper());

    private final Paint mFillPaint;
    private final Paint mStrokePaint;
    private final Paint mTextPaint;
    private final Paint mSmallPaint;

    private BoundingBox mLastBox = null;
    private boolean     mLoading = false;
    private boolean     mSessionReady = false;
    private String      mSessionCookie = "";

    // ── Konstruktor ──────────────────────────────────────────────────

    public DemapOverlay(Context context) {
        super();
        mContext = context;

        mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mFillPaint.setStyle(Paint.Style.FILL);

        mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mStrokePaint.setStyle(Paint.Style.STROKE);
        mStrokePaint.setColor(COLOR_STROKE);
        mStrokePaint.setStrokeWidth(3f);

        mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mTextPaint.setColor(0xFFFFFFFF);
        mTextPaint.setTextSize(18f);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setFakeBoldText(true);

        mSmallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mSmallPaint.setColor(0xFFFFFFFF);
        mSmallPaint.setTextSize(13f);
        mSmallPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ── Toggles ──────────────────────────────────────────────────────

    public void setShowPokestops(boolean v) { mShowPokestops = v; }
    public void setShowGyms(boolean v)      { mShowGyms = v; }
    public void setShowStations(boolean v)  { mShowStations = v; }
    public boolean isShowPokestops()        { return mShowPokestops; }
    public boolean isShowGyms()             { return mShowGyms; }
    public boolean isShowStations()         { return mShowStations; }

    // ── Laden ────────────────────────────────────────────────────────

    public void refresh(MapView mapView) {
        if (!isEnabled()) {
            Log.d(TAG, "refresh() – Overlay ist deaktiviert");
            return;
        }
        double zoom = mapView.getZoomLevelDouble();
        if (zoom < MIN_ZOOM) {
            Log.d(TAG, "refresh() – Zoom " + zoom + " < " + MIN_ZOOM + ", nicht geladen");
            return;
        }

        BoundingBox box = mapView.getBoundingBox();
        if (mLastBox != null && boxContains(mLastBox, box)) {
            Log.d(TAG, "refresh() – Box bereits gecacht, übersprungen");
            return;
        }
        if (mLoading) {
            Log.d(TAG, "refresh() – lädt bereits");
            return;
        }

        Log.d(TAG, "refresh() – starte Laden. Box: " + box.getLatSouth() + "," + box.getLonWest()
                + " -> " + box.getLatNorth() + "," + box.getLonEast());

        mLoading = true;
        mLastBox = box;

        mExecutor.submit(() -> {
            try {
                if (!mSessionReady) initSession();
                if (mShowPokestops) loadPokestops(box, mapView);
                if (mShowGyms)      loadGyms(box, mapView);
                if (mShowStations)  loadStations(box, mapView);
            } catch (Exception e) {
                Log.e(TAG, "Ladefehler", e);
            } finally {
                mLoading = false;
            }
        });
    }

    private void loadPokestops(BoundingBox box, MapView mapView) throws Exception {
        Log.d(TAG, "loadPokestops() – sende Anfrage...");
        JSONObject resp = post(pokestopQuery(box));
        if (resp == null) { Log.e(TAG, "loadPokestops() – Antwort ist null"); return; }
        Log.d(TAG, "loadPokestops() – Antwort: " + resp.toString().substring(0, Math.min(200, resp.toString().length())));
        JSONArray arr = getArray(resp, "pokestops");
        if (arr == null) { Log.e(TAG, "loadPokestops() – kein 'pokestops' Array in Antwort"); return; }
        Log.d(TAG, "loadPokestops() – " + arr.length() + " Pokéstops erhalten");

        List<PoiItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            items.add(new PoiItem(o.getDouble("lat"), o.getDouble("lon")));
        }
        mMainHandler.post(() -> {
            synchronized (mPokestops) { mPokestops.clear(); mPokestops.addAll(items); }
            mapView.invalidate();
        });
    }

    private void loadGyms(BoundingBox box, MapView mapView) throws Exception {
        Log.d(TAG, "loadGyms() – sende Anfrage...");
        JSONObject resp = post(gymQuery(box));
        if (resp == null) { Log.e(TAG, "loadGyms() – Antwort ist null"); return; }
        JSONArray arr = getArray(resp, "gyms");
        if (arr == null) { Log.e(TAG, "loadGyms() – kein 'gyms' Array in Antwort"); return; }
        Log.d(TAG, "loadGyms() – " + arr.length() + " Arenen erhalten");

        List<GymItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            items.add(new GymItem(
                o.getDouble("lat"), o.getDouble("lon"),
                o.optInt("team_id", 0),
                o.optInt("defenders", 0)));
        }
        mMainHandler.post(() -> {
            synchronized (mGyms) { mGyms.clear(); mGyms.addAll(items); }
            mapView.invalidate();
        });
    }

    private void loadStations(BoundingBox box, MapView mapView) throws Exception {
        Log.d(TAG, "loadStations() – sende Anfrage...");
        JSONObject resp = post(stationQuery(box));
        if (resp == null) { Log.e(TAG, "loadStations() – Antwort ist null"); return; }
        JSONArray arr = getArray(resp, "stations");
        if (arr == null) { Log.e(TAG, "loadStations() – kein 'stations' Array in Antwort"); return; }
        Log.d(TAG, "loadStations() – " + arr.length() + " Stationen erhalten");

        List<PoiItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            items.add(new PoiItem(o.getDouble("lat"), o.getDouble("lon")));
        }
        mMainHandler.post(() -> {
            synchronized (mStations) { mStations.clear(); mStations.addAll(items); }
            mapView.invalidate();
        });
    }

    // ── Zeichnen ─────────────────────────────────────────────────────

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || !isEnabled()) return;

        Projection proj   = mapView.getProjection();
        Point      pt     = new Point();
        float      r      = dpToPx(7f);

        // Pokéstops – blauer Kreis "P"
        if (mShowPokestops) {
            mFillPaint.setColor(COLOR_STOP);
            synchronized (mPokestops) {
                for (PoiItem p : mPokestops) {
                    proj.toPixels(new GeoPoint(p.lat, p.lon), pt);
                    canvas.drawCircle(pt.x, pt.y, r, mFillPaint);
                    canvas.drawCircle(pt.x, pt.y, r, mStrokePaint);
                    canvas.drawText("P", pt.x, pt.y + r * 0.4f, mTextPaint);
                }
            }
        }

        // Arenen – Sechseck in Teamfarbe + Verteidiger-Zahl
        if (mShowGyms) {
            synchronized (mGyms) {
                for (GymItem g : mGyms) {
                    proj.toPixels(new GeoPoint(g.lat, g.lon), pt);
                    int color = (g.teamId >= 0 && g.teamId < GYM_COLORS.length)
                            ? GYM_COLORS[g.teamId] : GYM_COLORS[0];
                    mFillPaint.setColor(color);
                    Path hex = hexPath(pt.x, pt.y, r * 1.1f);
                    canvas.drawPath(hex, mFillPaint);
                    canvas.drawPath(hex, mStrokePaint);
                    canvas.drawText("A", pt.x, pt.y - r * 0.1f, mTextPaint);
                    if (g.defenders > 0)
                        canvas.drawText(String.valueOf(g.defenders),
                                pt.x, pt.y + r * 1.2f, mSmallPaint);
                }
            }
        }

        // Kraftquellen – lila Stern "K"
        if (mShowStations) {
            mFillPaint.setColor(COLOR_STATION);
            synchronized (mStations) {
                for (PoiItem p : mStations) {
                    proj.toPixels(new GeoPoint(p.lat, p.lon), pt);
                    Path star = starPath(pt.x, pt.y, r * 1.2f, r * 0.5f);
                    canvas.drawPath(star, mFillPaint);
                    canvas.drawPath(star, mStrokePaint);
                    canvas.drawText("K", pt.x, pt.y + r * 0.4f, mTextPaint);
                }
            }
        }
    }

    // ── Session-Initialisierung ──────────────────────────────────────

    private void initSession() {
        try {
            Log.d(TAG, "initSession() – rufe demap.co ab um Session-Cookie zu holen...");
            URL url = new URL("https://demap.co/");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);

            int code = c.getResponseCode();
            Log.d(TAG, "initSession() – HTTP " + code);

            // Cookies aus Set-Cookie Header lesen
            StringBuilder cookieBuilder = new StringBuilder();
            java.util.Map<String, java.util.List<String>> headers = c.getHeaderFields();
            for (java.util.Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                if ("Set-Cookie".equalsIgnoreCase(entry.getKey())) {
                    for (String cookie : entry.getValue()) {
                        String cookieName = cookie.split(";")[0];
                        if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                        cookieBuilder.append(cookieName);
                        Log.d(TAG, "initSession() – Cookie: " + cookieName);
                    }
                }
            }
            mSessionCookie = cookieBuilder.toString();
            mSessionReady = true;
            Log.d(TAG, "initSession() – fertig. Cookies: " + mSessionCookie);

            // Body lesen und schließen
            try {
                InputStream is = c.getInputStream();
                byte[] buf = new byte[4096];
                while (is.read(buf) != -1) {}
                is.close();
            } catch (Exception ignored) {}

        } catch (Exception e) {
            Log.e(TAG, "initSession() – Fehler: " + e.getMessage(), e);
            mSessionReady = true; // trotzdem weiterversuchen
        }
    }

    // ── HTTP / GraphQL ────────────────────────────────────────────────

    private JSONObject post(String body) {
        try {
            Log.d(TAG, "POST → " + GRAPHQL);
            URL url = new URL(GRAPHQL);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Referer", "https://demap.co/");
            c.setRequestProperty("Origin",  "https://demap.co");
            c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            if (!mSessionCookie.isEmpty())
                c.setRequestProperty("Cookie", mSessionCookie);
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);

            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();
            Log.d(TAG, "HTTP-Antwort: " + code);
            if (code == 511) {
                Log.w(TAG, "HTTP 511 – Session abgelaufen, wird neu initialisiert");
                mSessionReady = false;
                mSessionCookie = "";
                return null;
            }
            if (code != 200) {
                Log.w(TAG, "HTTP-Fehler " + code);
                return null;
            }

            InputStream is = c.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            is.close();

            String responseStr = baos.toString("UTF-8");
            Log.d(TAG, "Antwort (" + responseStr.length() + " Zeichen): "
                    + responseStr.substring(0, Math.min(300, responseStr.length())));
            return new JSONObject(responseStr);
        } catch (Exception e) {
            Log.e(TAG, "HTTP-Fehler: " + e.getMessage(), e);
            return null;
        }
    }

    // ── Query-Builder ────────────────────────────────────────────────

    private String pokestopQuery(BoundingBox b) throws Exception {
        JSONObject vars = bbox(b);
        vars.put("filters", new JSONObject("{\"onlyAllPokestops\":true}"));
        return wrap("Pokestops", vars,
            "query Pokestops($minLat:Float!,$minLon:Float!,$maxLat:Float!,$maxLon:Float!,$filters:JSON!){" +
            "pokestops(minLat:$minLat,minLon:$minLon,maxLat:$maxLat,maxLon:$maxLon,filters:$filters){" +
            "id lat lon __typename}}");
    }

    private String gymQuery(BoundingBox b) throws Exception {
        JSONObject vars = bbox(b);
        vars.put("filters", new JSONObject("{\"onlyAllGyms\":true}"));
        return wrap("Gyms", vars,
            "query Gyms($minLat:Float!,$minLon:Float!,$maxLat:Float!,$maxLon:Float!,$filters:JSON!){" +
            "gyms(minLat:$minLat,minLon:$minLon,maxLat:$maxLat,maxLon:$maxLon,filters:$filters){" +
            "id lat lon team_id defenders __typename}}");
    }

    private String stationQuery(BoundingBox b) throws Exception {
        JSONObject vars = bbox(b);
        vars.put("filters", new JSONObject("{\"onlyAllStations\":true}"));
        return wrap("Stations", vars,
            "query Stations($minLat:Float!,$minLon:Float!,$maxLat:Float!,$maxLon:Float!,$filters:JSON!){" +
            "stations(minLat:$minLat,minLon:$minLon,maxLat:$maxLat,maxLon:$maxLon,filters:$filters){" +
            "id lat lon __typename}}");
    }

    private JSONObject bbox(BoundingBox b) throws Exception {
        JSONObject v = new JSONObject();
        v.put("minLat", b.getLatSouth());
        v.put("maxLat", b.getLatNorth());
        v.put("minLon", b.getLonWest());
        v.put("maxLon", b.getLonEast());
        return v;
    }

    private String wrap(String op, JSONObject vars, String query) throws Exception {
        JSONObject b = new JSONObject();
        b.put("operationName", op);
        b.put("variables", vars);
        b.put("query", query);
        return b.toString();
    }

    // ── Geometrie ────────────────────────────────────────────────────

    private Path hexPath(float cx, float cy, float r) {
        Path p = new Path();
        for (int i = 0; i < 6; i++) {
            double a = Math.PI / 180.0 * (60 * i - 30);
            float x = cx + r * (float) Math.cos(a);
            float y = cy + r * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    private Path starPath(float cx, float cy, float outer, float inner) {
        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            double a = Math.PI / 180.0 * (36 * i - 90);
            float r = (i % 2 == 0) ? outer : inner;
            float x = cx + r * (float) Math.cos(a);
            float y = cy + r * (float) Math.sin(a);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    // ── Hilfsmethoden ────────────────────────────────────────────────

    public void clearCache() {
        synchronized (mPokestops) { mPokestops.clear(); }
        synchronized (mGyms)      { mGyms.clear(); }
        synchronized (mStations)  { mStations.clear(); }
        mLastBox = null;
    }

    private JSONArray getArray(JSONObject resp, String key) {
        try { return resp.getJSONObject("data").getJSONArray(key); }
        catch (Exception e) { return null; }
    }

    private boolean boxContains(BoundingBox outer, BoundingBox inner) {
        return outer.getLatNorth() >= inner.getLatNorth()
            && outer.getLatSouth() <= inner.getLatSouth()
            && outer.getLonEast()  >= inner.getLonEast()
            && outer.getLonWest()  <= inner.getLonWest();
    }

    private float dpToPx(float dp) {
        return dp * mContext.getResources().getDisplayMetrics().density;
    }

    // ── Datenklassen ─────────────────────────────────────────────────

    private static class PoiItem {
        final double lat, lon;
        PoiItem(double lat, double lon) { this.lat = lat; this.lon = lon; }
    }

    private static class GymItem {
        final double lat, lon;
        final int teamId, defenders;
        GymItem(double lat, double lon, int teamId, int defenders) {
            this.lat = lat; this.lon = lon;
            this.teamId = teamId; this.defenders = defenders;
        }
    }
}
