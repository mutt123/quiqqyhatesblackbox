package com.quimodotcom.blackboxcure;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private static final String TAG     = "DemapOverlay";
    private static final String GRAPHQL = "https://demap.co/graphql";
    private static final String BASE_URL = "https://demap.co/";
    private static final double MIN_ZOOM = 14.0;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Pixel 4) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.6099.210 Mobile Safari/537.36";

    // Teamfarben: 0=Grau, 1=Mystic(blau), 2=Valor(rot), 3=Instinct(gelb)
    private static final int[] GYM_COLORS = {
            0xFFAAAAAA, 0xFF4B78FF, 0xFFFF4444, 0xFFFFCC00
    };

    private static final int COLOR_STOP    = 0xFF3B9EFF;
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

    private BoundingBox mLastBox     = null;
    private boolean     mLoading     = false;
    private volatile boolean mSessionReady = false;

    // ── Konstruktor ──────────────────────────────────────────────────

    // Session-Warmup läuft auf eigenem Thread um den Executor nicht zu blockieren
    private volatile boolean mSessionWarming = false;

    public DemapOverlay(Context context) {
        super();
        mContext = context;

        // Session sofort im Hintergrund vorwärmen – bevor der User den Layer aktiviert
        mSessionWarming = true;
        new Thread(() -> {
            initSessionViaWebView();
            mSessionWarming = false;
        }, "DemapSessionWarmup").start();

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
        if (!isEnabled()) return;
        if (mapView.getZoomLevelDouble() < MIN_ZOOM) {
            Log.d(TAG, "Zoom zu klein, nicht geladen");
            return;
        }

        BoundingBox box = mapView.getBoundingBox();
        if (mLastBox != null && boxContains(mLastBox, box)) {
            Log.d(TAG, "Box bereits gecacht");
            return;
        }
        if (mLoading) return;

        Log.d(TAG, "refresh() – Box: " + box.getLatSouth() + "," + box.getLonWest()
                + " -> " + box.getLatNorth() + "," + box.getLonEast());

        mLoading = true;
        mLastBox = box;

        mExecutor.submit(() -> {
            try {
                if (!mSessionReady) {
                    // Warmup-Thread läuft möglicherweise noch (gestartet im Konstruktor)
                    // Warten bis er fertig ist (max. 10s)
                    long deadline = System.currentTimeMillis() + 10000;
                    while ((mSessionWarming || !mSessionReady) && System.currentTimeMillis() < deadline) {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    }
                    // Falls Warmup fehlschlug: eigene Session starten
                    if (!mSessionReady) {
                        initSessionViaWebView();
                    }
                }
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

    // ── Session via WebView (löst Cloudflare JS-Challenge) ───────────

    /**
     * Lädt demap.co in einem unsichtbaren WebView, um den cf_clearance-Cookie
     * zu erhalten. Blockiert den aufrufenden (Hintergrund-)Thread bis zu 10s.
     */
    private void initSessionViaWebView() {
        Log.d(TAG, "initSession – starte WebView für Cloudflare-Cookie...");

        // Alten CookieManager-Zustand zurücksetzen
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().setAcceptCookie(true);

        // CountDownLatch: gibt Hintergrundthread frei sobald Cookie da ist
        CountDownLatch latch = new CountDownLatch(1);

        mMainHandler.post(() -> {
            try {
                WebView wv = new WebView(mContext);
                WebSettings s = wv.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(USER_AGENT);

                wv.setWebViewClient(new WebViewClient() {

                    private int mRetries = 0;

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        Log.d(TAG, "WebView onPageFinished: " + url);
                        checkCookieAndRelease(view, latch);
                    }

                    private void checkCookieAndRelease(WebView view, CountDownLatch latch) {
                        String cookies = CookieManager.getInstance()
                                .getCookie(BASE_URL);
                        Log.d(TAG, "WebView cookies nach Load: " + cookies);

                        // demap.co nutzt reactmap1 als Session-Cookie (kein Cloudflare)
                        if (cookies != null && cookies.contains("reactmap1")) {
                            Log.d(TAG, "reactmap1 Session-Cookie erhalten!");
                            mSessionReady = true;
                            latch.countDown();
                        } else if (mRetries < 5) {
                            // Kurz warten und nochmal prüfen (JS läuft async)
                            mRetries++;
                            Log.d(TAG, "Warte auf reactmap1 Cookie... Versuch " + mRetries);
                            mMainHandler.postDelayed(() ->
                                    checkCookieAndRelease(view, latch), 1500);
                        } else {
                            Log.w(TAG, "reactmap1 nicht erhalten nach " + mRetries + " Versuchen – trotzdem versuchen");
                            mSessionReady = true;
                            latch.countDown();
                        }
                    }
                });

                wv.loadUrl(BASE_URL);

            } catch (Exception e) {
                Log.e(TAG, "WebView-Fehler", e);
                mSessionReady = true;
                latch.countDown();
            }
        });

        // Hintergrundthread wartet max. 12 Sekunden
        try {
            boolean ok = latch.await(12, TimeUnit.SECONDS);
            if (!ok) {
                Log.w(TAG, "WebView-Timeout – versuche trotzdem GraphQL");
                mSessionReady = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── GraphQL-Requests ─────────────────────────────────────────────

    private void loadPokestops(BoundingBox box, MapView mapView) throws Exception {
        Log.d(TAG, "loadPokestops()...");
        JSONObject resp = post(pokestopQuery(box));
        if (resp == null) { Log.e(TAG, "loadPokestops – null Antwort"); return; }
        JSONArray arr = getArray(resp, "pokestops");
        if (arr == null) { Log.e(TAG, "loadPokestops – kein Array"); return; }
        Log.d(TAG, arr.length() + " Pokéstops erhalten");

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
        Log.d(TAG, "loadGyms()...");
        JSONObject resp = post(gymQuery(box));
        if (resp == null) { Log.e(TAG, "loadGyms – null Antwort"); return; }
        JSONArray arr = getArray(resp, "gyms");
        if (arr == null) { Log.e(TAG, "loadGyms – kein Array"); return; }
        Log.d(TAG, arr.length() + " Arenen erhalten");

        List<GymItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            // Erstes Gym vollständig loggen um Feldtypen zu prüfen
            if (i == 0) Log.d(TAG, "Gym[0] raw: " + o.toString());
            int teamId    = o.optInt("team_id",   0);
            // defenders kann ein Integer ODER ein Array sein – beide Fälle abfangen
            int defenders;
            if (o.optJSONArray("defenders") != null) {
                defenders = o.optJSONArray("defenders").length();
            } else {
                defenders = o.optInt("defenders", 0);
            }
            int availableSlots = o.optInt("available_slots", 0);
            items.add(new GymItem(o.getDouble("lat"), o.getDouble("lon"), teamId, defenders, availableSlots));
        }
        mMainHandler.post(() -> {
            synchronized (mGyms) { mGyms.clear(); mGyms.addAll(items); }
            mapView.invalidate();
        });
    }

    private void loadStations(BoundingBox box, MapView mapView) throws Exception {
        Log.d(TAG, "loadStations()...");
        JSONObject resp = post(stationQuery(box));
        if (resp == null) { Log.e(TAG, "loadStations – null Antwort"); return; }
        JSONArray arr = getArray(resp, "stations");
        if (arr == null) { Log.e(TAG, "loadStations – kein Array"); return; }
        Log.d(TAG, arr.length() + " Stationen erhalten");

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

    // ── HTTP POST ─────────────────────────────────────────────────────

    private JSONObject post(String body) {
        try {
            // Cookies aus WebView CookieManager holen
            String cookies = CookieManager.getInstance().getCookie(BASE_URL);
            Log.d(TAG, "POST mit Cookies: " + (cookies != null ? cookies.substring(0, Math.min(80, cookies.length())) : "keine"));

            URL url = new URL(GRAPHQL);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Referer",  BASE_URL);
            c.setRequestProperty("Origin",   "https://demap.co");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7");
            if (cookies != null && !cookies.isEmpty()) {
                c.setRequestProperty("Cookie", cookies);
            }
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);

            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();
            Log.d(TAG, "HTTP-Antwort: " + code);

            if (code == 511 || code == 403) {
                Log.w(TAG, "HTTP " + code + " – Cloudflare blockiert. Session zurücksetzen.");
                mSessionReady = false;
                return null;
            }
            if (code != 200) {
                Log.w(TAG, "Unerwarteter HTTP-Code: " + code);
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

    // ── Zeichnen ─────────────────────────────────────────────────────

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || !isEnabled()) return;

        Projection proj = mapView.getProjection();
        Point      pt   = new Point();
        float      r    = dpToPx(7f);

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
                    canvas.drawText("A", pt.x, pt.y + r * 0.4f, mTextPaint);
                    if (g.defenders > 0) {
                        // Dunkles Badge oben rechts: Anzahl Verteidiger
                        float bx = pt.x + r * 0.9f;
                        float by = pt.y - r * 0.9f;
                        float br = r * 0.55f;
                        Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
                        badgeBg.setStyle(Paint.Style.FILL);
                        badgeBg.setColor(0xFF333333);
                        canvas.drawCircle(bx, by, br, badgeBg);
                        canvas.drawCircle(bx, by, br, mStrokePaint);
                        canvas.drawText(String.valueOf(g.defenders),
                                bx, by + br * 0.4f, mSmallPaint);
                    }
                    if (g.availableSlots > 0) {
                        // Grünes Badge unten rechts: freie Plätze
                        float bx = pt.x + r * 0.9f;
                        float by = pt.y + r * 0.9f;
                        float br = r * 0.55f;
                        Paint slotBg = new Paint(Paint.ANTI_ALIAS_FLAG);
                        slotBg.setStyle(Paint.Style.FILL);
                        slotBg.setColor(0xFF2E7D32); // dunkelgrün
                        canvas.drawCircle(bx, by, br, slotBg);
                        canvas.drawCircle(bx, by, br, mStrokePaint);
                        canvas.drawText("+" + g.availableSlots,
                                bx, by + br * 0.4f, mSmallPaint);
                    }
                }
            }
        }

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

        // Exakter Filter wie ihn demap.co selbst sendet (aus Browser DevTools)
        JSONObject filters = new JSONObject();
        filters.put("onlyLinkGlobal", false);
        filters.put("onlyAreas",      new JSONArray());
        filters.put("onlyAllGyms",    true);
        filters.put("onlyLevels",     "all");
        filters.put("onlyRaids",      false);
        filters.put("onlyExEligible", false);
        filters.put("onlyInBattle",   false);
        filters.put("onlyArEligible", false);
        filters.put("onlyGymBadges",  false);
        filters.put("onlyBadge",      "all");
        filters.put("onlyRaidTier",   "all");

        // Team-Filter: alle Teams aktivieren
        JSONObject allTrue  = new JSONObject(); allTrue.put("all",  true);  allTrue.put("adv",  "");
        JSONObject allFalse = new JSONObject(); allFalse.put("all", false); allFalse.put("adv", "");
        filters.put("onlyStandard", new JSONObject().put("enabled", false).put("size", "md").put("all", false).put("adv", ""));
        filters.put("t0-0", allTrue);
        filters.put("t1-0", allTrue);
        filters.put("t2-0", allTrue);
        filters.put("t3-0", allTrue);

        vars.put("filters", filters);

        String query =
                "fragment CoreGym on Gym {\n" +
                        "  id\n  lat\n  lon\n  __typename\n}\n\n" +
                        "fragment GymInfo on Gym {\n" +
                        "  available_slots\n  team_id\n  in_battle\n  defenders\n  __typename\n}\n\n" +
                        "query Gyms($minLat:Float!,$minLon:Float!,$maxLat:Float!,$maxLon:Float!,$filters:JSON!){\n" +
                        "  gyms(minLat:$minLat,minLon:$minLon,maxLat:$maxLat,maxLon:$maxLon,filters:$filters){\n" +
                        "    ...CoreGym\n    ...GymInfo\n    __typename\n  }\n}";

        return wrap("Gyms", vars, query);
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
        // Session NICHT zurücksetzen – reactmap1 Cookie bleibt gültig
        // mSessionReady = false; wäre nur nötig nach Logout/Session-Ablauf
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
        final int teamId, defenders, availableSlots;
        GymItem(double lat, double lon, int teamId, int defenders, int availableSlots) {
            this.lat = lat; this.lon = lon;
            this.teamId = teamId; this.defenders = defenders;
            this.availableSlots = availableSlots;
        }
    }
}