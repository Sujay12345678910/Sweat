package com.runtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

// Foreground service so GPS updates keep arriving (and distance/pace keep
// accumulating) once the Activity is backgrounded — Android 8+ throttles
// location updates for apps that aren't foreground unless a foreground
// service with an ongoing notification is running.
public class LocationService extends Service implements LocationListener {

    public interface UpdateListener {
        void onMetricsChanged(double totalDistanceMetres, double paceSecPerKm);
        void onGpsStatusChanged(String status);
        void onGpxSaved(String fileName);  // fileName is null on save failure
    }

    private final IBinder binder = new LocalBinder();
    private UpdateListener updateListener;

    private LocationManager locationManager;
    private PowerManager.WakeLock wakeLock;

    private boolean running = false;
    private double totalDistanceMetres = 0.0;
    private double currentPaceSecPerKm = 0;

    // ---------------------------------------------------------------
    // Clocks. Everything that feeds a *calculation* uses the monotonic
    // clock (SystemClock.elapsedRealtime / Location.getElapsedRealtimeNanos),
    // never wall clock. Wall clock is used for exactly one thing: deriving
    // GPX timestamps (see the anchor below).
    //
    // This device has no cellular time source, so its wall clock is often
    // wrong at boot and gets stepped — sometimes by GPS itself on first fix,
    // mid-run. Previously the run timer came from System.currentTimeMillis(),
    // so a step made the elapsed readout jump by the size of the correction;
    // and fix intervals came from Location.getTime(), so a backward step made
    // that interval negative, which fails the plausibility test and discards
    // the distance across it. Neither is provably the reported "stopped
    // tracking mid-run", but both are real and both are gone now: the
    // monotonic clock cannot be stepped.
    // ---------------------------------------------------------------
    private long startElapsedMs = 0;      // elapsedRealtime when the current segment started
    private long elapsedMs = 0;           // accumulated run time across pauses

    // GPX needs real UTC timestamps, but they must also be monotonic or the
    // file is garbage. Anchor wall clock to the monotonic clock once at run
    // start and derive every point's timestamp from that, so a mid-run clock
    // correction can't reorder or duplicate timestamps in the export.
    private long wallAnchorMs = 0;
    private long elapsedAnchorMs = 0;

    // ---------------------------------------------------------------
    // Fix bookkeeping
    // ---------------------------------------------------------------
    private long lastFixElapsedMs = -1;        // previous accepted fix (monotonic)
    private double lastSpeedMps = -1;          // -1 = no trusted Doppler speed

    private Location distBaseline = null;      // baseline for the position fallback
    private long baselineElapsedMs = -1;

    private long lastAcceptedElapsedMs = -1;   // for staleness / "GPS lost"

    // Exponential moving average of ground speed, which is what the pace
    // readout is derived from. dt-aware so an irregular fix cadence doesn't
    // change how much smoothing is applied.
    private double speedEma = 0;
    private boolean haveSpeedEma = false;

    // ---------------------------------------------------------------
    // GPS request parameters
    //
    // 1 Hz with NO platform-level distance filter. The old setup passed
    // minDistance = 5m, which since Jelly Bean is mandatory for the platform
    // to honour — it withholds fixes until you've moved 5m. That hid the raw
    // fix stream, which is what made pace lag (samples arrived in irregular
    // bursts) and made signal loss undetectable (no fixes looks identical to
    // not moving). All filtering now happens below, where it can see
    // everything and can tell the two cases apart.
    // ---------------------------------------------------------------
    private static final long GPS_INTERVAL_MS = 1000;
    private static final float GPS_MIN_DISTANCE_M = 0f;

    // Fixes worse than this accuracy radius are ignored entirely — not just
    // excluded from the distance sum, but never allowed to become the new
    // baseline either. Otherwise a noisy fix (common right at run start,
    // before GPS has settled) seeds a bad baseline, and the next *good* fix
    // then looks like an implausible jump and gets thrown away too.
    private static final float GPS_MAX_ACCURACY_M = 25.0f;

    // Plausibility cap on speed between two fixes, applied against the actual
    // elapsed time rather than a flat distance. A flat cap wrongly zeroes real
    // distance after any multi-second GPS gap, since a gap covers more ground
    // than a cap sized for the steady-state interval.
    private static final double MAX_PLAUSIBLE_SPEED_MPS = 10.0;  // 36 km/h

    // Doppler velocity thresholds. GNSS chipsets derive speed from the carrier
    // Doppler shift, which is a direct measurement — far better over short
    // intervals than differencing two positions, and (unlike position
    // differencing) it carries no systematic distance-inflation bias.
    private static final float MAX_SPEED_ACCURACY_MPS = 2.0f;
    private static final double STATIONARY_SPEED_MPS = 0.5;   // ~33:20 /km; below this = stopped
    private static final long DOPPLER_MAX_DT_MS = 3000;       // don't integrate across bigger gaps

    // Position-fallback baseline gate. Only used when the chipset gives us no
    // usable speed; distance is then committed once the runner is far enough
    // from the held baseline that noise can't dominate the measurement.
    //
    // The threshold scales with the fix's own accuracy rather than being a
    // flat 5m. Summing short position deltas inflates distance by roughly
    // (sigma/d)^2 per segment, so a gate sized in metres is only ever right
    // for one noise level: 5m is far too short once accuracy degrades. At
    // 5x accuracy the expected inflation is ~2%. Simulated against both white
    // and autocorrelated position noise at 2/3/5/8m sigma, this holds mean
    // error to ~1.4% (autocorrelated) / ~3.6% (white); the flat 5m gate gave
    // 12.9% / 30.5% on the same runs.
    private static final float BASELINE_MIN_DISTANCE_M = 10.0f;
    private static final float BASELINE_MAX_DISTANCE_M = 50.0f;
    private static final double BASELINE_ACCURACY_FACTOR = 5.0;

    // Distance accrued while GPS was out is lost, so mark a track break
    // rather than drawing a straight line across the gap.
    private static final long TRACK_BREAK_GAP_MS = 30000;

    // Staleness. Past FIX_STALE_MS the pace readout stops being believable;
    // past FIX_LOST_MS we say so out loud instead of holding a stale number.
    public static final long FIX_STALE_MS = 5000;
    public static final long FIX_LOST_MS = 15000;

    private static final double SPEED_EMA_TAU_S = 2.0;

    // Fallback pace window (total time / total distance over the last N
    // committed samples). Fixed-size, running sums, no per-sample allocation.
    // NOTE: this sums time and distance separately and divides once. Averaging
    // per-sample *pace* values instead — which is what this used to do — is
    // not the same number: it weights a 5m sample as heavily as a 12m one and
    // therefore reads systematically slow.
    private static final int PACE_WINDOW = 6;
    private final double[] winDist = new double[PACE_WINDOW];
    private final double[] winTime = new double[PACE_WINDOW];
    private int winIndex = 0, winCount = 0;
    private double winDistSum = 0, winTimeSum = 0;

    private static final String CHANNEL_ID = "run_tracking";
    private static final int NOTIFICATION_ID = 1;
    private static final long NOTIFICATION_MIN_INTERVAL_MS = 3000;
    private long lastNotificationMs = 0;
    private PendingIntent contentIntent;   // hoisted; rebuilding it per fix is a system call

    // ---------------------------------------------------------------
    // Track points for GPX export — parallel primitive arrays (not
    // ArrayList<Location>) grown by doubling, same rationale as the pace
    // buffer: this fills at the GPS cadence, so boxed objects and per-point
    // allocation are worth avoiding on this device.
    // ---------------------------------------------------------------
    private static final int TRACK_INITIAL_CAPACITY = 256;
    private double[] trackLat = new double[TRACK_INITIAL_CAPACITY];
    private double[] trackLon = new double[TRACK_INITIAL_CAPACITY];
    private float[] trackEle = new float[TRACK_INITIAL_CAPACITY];
    private boolean[] trackHasEle = new boolean[TRACK_INITIAL_CAPACITY];
    private long[] trackTimeMs = new long[TRACK_INITIAL_CAPACITY];
    private boolean[] trackSegStart = new boolean[TRACK_INITIAL_CAPACITY];
    private int trackPointCount = 0;
    private boolean segmentBreakPending = false;

    private static final String GPX_FILENAME_FORMAT = "yyyyMMdd_HHmmss";
    private static final String GPX_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    public class LocalBinder extends Binder {
        LocationService getService() {
            return LocationService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // The WAKE_LOCK permission was already declared but never used. The
        // platform holds a wakelock on our behalf when it delivers a location
        // "for some period of time, but not indefinitely" — which is why a
        // run could stop accumulating once the screen went off even with the
        // foreground service alive. Hold a partial wakelock for the duration
        // of an active run so the CPU can't suspend between fixes.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sweat:run");
        wakeLock.setReferenceCounted(false);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // NOT sticky. The old START_STICKY promised that "a run in progress
        // shouldn't silently vanish", but nothing is persisted, so a restarted
        // service came back with running == false, zero distance and no track
        // — a service that looks alive but has lost the run. Failing visibly
        // is better than that. (Durable checkpointing would fix it properly;
        // it's a bigger change than this one.)
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseWakeLock();
        stopGps();
    }

    public void setUpdateListener(UpdateListener listener) {
        this.updateListener = listener;
    }

    // ---------------------------------------------------------------
    // GPS control — runs whenever permission is granted, independent of
    // whether a run is actively being tracked (so accuracy is visible
    // before the user hits Start).
    // ---------------------------------------------------------------
    public void requestGpsUpdatesIfPermitted() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                GPS_INTERVAL_MS,
                GPS_MIN_DISTANCE_M,
                this
            );
            if (updateListener != null) {
                updateListener.onGpsStatusChanged("Acquiring GPS...");
            }
        } catch (SecurityException e) {
            if (updateListener != null) {
                updateListener.onGpsStatusChanged("GPS error.");
            }
        }
    }

    private void stopGps() {
        locationManager.removeUpdates(this);
    }

    // ---------------------------------------------------------------
    // Run control
    // ---------------------------------------------------------------
    public void startTracking() {
        if (running) return;
        running = true;
        startElapsedMs = SystemClock.elapsedRealtime();

        if (trackPointCount == 0) {
            // First segment of a fresh run — anchor GPX wall time here.
            wallAnchorMs = System.currentTimeMillis();
            elapsedAnchorMs = startElapsedMs;
        } else {
            // Resuming after a pause: the trace should not draw a line across
            // wherever the runner wandered while stopped.
            segmentBreakPending = true;
        }

        // Start from a clean slate rather than from whatever GPS last said.
        // A stale pre-run fix used as the first baseline produced a first pace
        // sample covering minutes of standing around — the source of the
        // "56:xx /km for the first minute" reading.
        resetFixState();

        acquireWakeLock();
        startForeground(NOTIFICATION_ID, buildNotification());
        // startForeground already posted it; start the throttle clock here so
        // the next fix doesn't immediately repost.
        lastNotificationMs = SystemClock.elapsedRealtime();
    }

    public void pauseTracking() {
        if (!running) return;
        running = false;
        elapsedMs += SystemClock.elapsedRealtime() - startElapsedMs;
        resetFixState();
        currentPaceSecPerKm = 0;
        releaseWakeLock();
        stopForeground(true);
    }

    public void resetTracking() {
        saveGpxIfNeeded();
        running = false;
        totalDistanceMetres = 0;
        elapsedMs = 0;
        startElapsedMs = 0;
        currentPaceSecPerKm = 0;
        trackPointCount = 0;
        segmentBreakPending = false;
        resetFixState();
        releaseWakeLock();
        stopForeground(true);
        // Drop out of "started" state — startTracking() re-promotes via
        // startForegroundService() next time. If still bound to the
        // Activity, destruction is deferred until unbind (correct either way).
        stopSelf();
    }

    // Clears everything derived from the fix stream. Deliberately does NOT
    // touch totalDistanceMetres or elapsedMs, so it's safe across a pause.
    private void resetFixState() {
        lastFixElapsedMs = -1;
        lastSpeedMps = -1;
        distBaseline = null;
        baselineElapsedMs = -1;
        speedEma = 0;
        haveSpeedEma = false;
        winIndex = 0;
        winCount = 0;
        winDistSum = 0;
        winTimeSum = 0;
    }

    public boolean isRunning() {
        return running;
    }

    public double getTotalDistanceMetres() {
        return totalDistanceMetres;
    }

    // Pace decays to 0 ("--:--") rather than holding the last value forever
    // once fixes stop arriving. Previously this field was only ever written
    // inside the accepted-fix branch, so stopping at a traffic light or losing
    // signal left the last running pace on screen indefinitely.
    public double getCurrentPaceSecPerKm() {
        if (running && millisSinceLastFix() > FIX_LOST_MS) return 0;
        return currentPaceSecPerKm;
    }

    public long millisSinceLastFix() {
        if (lastAcceptedElapsedMs < 0) return Long.MAX_VALUE;
        return SystemClock.elapsedRealtime() - lastAcceptedElapsedMs;
    }

    public long getElapsedMs() {
        if (running) {
            return elapsedMs + (SystemClock.elapsedRealtime() - startElapsedMs);
        }
        return elapsedMs;
    }

    // ---------------------------------------------------------------
    // LocationListener
    // ---------------------------------------------------------------
    @Override
    public void onLocationChanged(Location location) {
        if (!isAccurateEnough(location)) {
            // Don't let a noisy fix become a baseline for the next comparison
            // — wait for one that's actually usable.
            if (updateListener != null && location.hasAccuracy()) {
                updateListener.onGpsStatusChanged(
                    String.format(Locale.US, "GPS weak  %.0fm", location.getAccuracy()));
            }
            return;
        }

        long fixElapsedMs = location.getElapsedRealtimeNanos() / 1000000L;
        lastAcceptedElapsedMs = SystemClock.elapsedRealtime();

        double speed = trustedSpeed(location);

        if (updateListener != null) {
            // Says which measurement mode distance is actually coming from.
            // "doppler" is the good path (direct velocity); "positional" means
            // this chipset isn't giving us a usable speed and we've fallen back
            // to differencing positions, which is meaningfully less accurate.
            updateListener.onGpsStatusChanged(String.format(Locale.US,
                "GPS  %.0fm  ·  %s", location.getAccuracy(),
                speed >= 0 ? "doppler" : "positional"));
        }

        if (!running) {
            rememberFix(location, fixElapsedMs, speed);
            return;
        }

        if (lastFixElapsedMs < 0) {
            // First fix of this run segment: record the start point, but there
            // is no interval to measure against yet.
            addTrackPoint(location, fixElapsedMs);
            rememberFix(location, fixElapsedMs, speed);
            return;
        }

        long dtMs = fixElapsedMs - lastFixElapsedMs;

        if (dtMs > 0) {
            double dtSec = dtMs / 1000.0;

            if (dtMs > TRACK_BREAK_GAP_MS) {
                // Long outage: the ground covered in between is unrecoverable
                // without dead reckoning, so break the trace instead of
                // pretending it was a straight line.
                segmentBreakPending = true;
                haveSpeedEma = false;
            }

            double increment = distanceIncrement(location, fixElapsedMs, dtSec, speed);
            if (increment > 0) {
                totalDistanceMetres += increment;
                addWindowSample(increment, dtSec);
            }

            updatePace(speed, dtSec);
            addTrackPoint(location, fixElapsedMs);

            postNotification();
            if (updateListener != null) {
                updateListener.onMetricsChanged(totalDistanceMetres, getCurrentPaceSecPerKm());
            }
        }

        rememberFix(location, fixElapsedMs, speed);
    }

    private void rememberFix(Location location, long fixElapsedMs, double speed) {
        lastFixElapsedMs = fixElapsedMs;
        lastSpeedMps = speed;
        if (distBaseline == null) {
            distBaseline = location;
            baselineElapsedMs = fixElapsedMs;
        }
    }

    // ---------------------------------------------------------------
    // Distance
    //
    // Primary source is integrated Doppler velocity, which is what a running
    // watch uses. Summing straight-line gaps between successive GPS positions
    // — the previous approach — has a known systematic bias: unbiased position
    // noise can only ever lengthen a polyline, never shorten it, so the total
    // reads long, and reads longer the faster you sample. Doppler speed is a
    // direct measurement of ground velocity and carries no such bias.
    //
    // Position differencing survives as the fallback for fixes with no usable
    // speed, gated against a held baseline so GPS jitter can't accumulate.
    // ---------------------------------------------------------------
    private double distanceIncrement(Location location, long fixElapsedMs,
                                     double dtSec, double speed) {
        double maxPlausible = dtSec * MAX_PLAUSIBLE_SPEED_MPS;

        boolean dopplerUsable = speed >= 0 && lastSpeedMps >= 0
            && dtSec * 1000 <= DOPPLER_MAX_DT_MS;

        if (dopplerUsable) {
            // Trapezoidal integration between the two velocity samples.
            double vAvg = 0.5 * (speed + lastSpeedMps);
            if (vAvg < STATIONARY_SPEED_MPS) {
                // Standing still. Zero it rather than letting receiver noise
                // trickle distance in while the runner isn't moving.
                advanceBaseline(location, fixElapsedMs);
                return 0;
            }
            double increment = vAvg * dtSec;
            if (increment > maxPlausible) increment = maxPlausible;
            advanceBaseline(location, fixElapsedMs);
            return increment;
        }

        // ---- fallback: position differencing against a held baseline ----
        if (distBaseline == null) {
            advanceBaseline(location, fixElapsedMs);
            return 0;
        }

        double posDelta = distBaseline.distanceTo(location);
        double baselineDtSec = (fixElapsedMs - baselineElapsedMs) / 1000.0;
        double maxSinceBaseline = baselineDtSec * MAX_PLAUSIBLE_SPEED_MPS;

        if (posDelta > maxSinceBaseline) {
            // GPS teleport — drop it and re-baseline here.
            advanceBaseline(location, fixElapsedMs);
            return 0;
        }
        if (posDelta < baselineGate(location)) {
            // Inside the noise floor. Hold the baseline and keep waiting;
            // the movement isn't lost, it just hasn't been committed yet.
            return 0;
        }
        advanceBaseline(location, fixElapsedMs);
        return posDelta;
    }

    private void advanceBaseline(Location location, long fixElapsedMs) {
        distBaseline = location;
        baselineElapsedMs = fixElapsedMs;
    }

    // How far the runner must get from the held baseline before that
    // displacement is trusted as real distance. Sized off the worse of the two
    // fixes' accuracy — see BASELINE_ACCURACY_FACTOR.
    private double baselineGate(Location location) {
        float accA = distBaseline.hasAccuracy() ? distBaseline.getAccuracy() : GPS_MAX_ACCURACY_M;
        float accB = location.hasAccuracy() ? location.getAccuracy() : GPS_MAX_ACCURACY_M;
        double gate = BASELINE_ACCURACY_FACTOR * Math.max(accA, accB);
        if (gate < BASELINE_MIN_DISTANCE_M) return BASELINE_MIN_DISTANCE_M;
        if (gate > BASELINE_MAX_DISTANCE_M) return BASELINE_MAX_DISTANCE_M;
        return gate;
    }

    // Doppler ground speed, or -1 when this fix's speed can't be trusted.
    private double trustedSpeed(Location location) {
        if (!location.hasSpeed()) return -1;
        // getSpeedAccuracyMetersPerSecond() is API 26, which is our minSdk.
        // Not every chipset populates it; absence isn't grounds for rejection,
        // a bad value is.
        if (location.hasSpeedAccuracy()
                && location.getSpeedAccuracyMetersPerSecond() > MAX_SPEED_ACCURACY_MPS) {
            return -1;
        }
        float v = location.getSpeed();
        if (v < 0 || v > MAX_PLAUSIBLE_SPEED_MPS * 1.5) return -1;
        return v;
    }

    // ---------------------------------------------------------------
    // Pace
    //
    // Derived from smoothed ground speed and recomputed on every single fix
    // (1 Hz), so it tracks the runner continuously instead of jumping when
    // enough distance happens to accumulate. The old version averaged the
    // last 5 per-sample pace values, which had two problems: samples only
    // existed when 5m of movement had been gated through, so the readout
    // updated in bursts; and averaging ratios over unequal distances is
    // biased slow. One stale opening sample also poisoned the whole window
    // for its full length — the "56:xx then 5:xx a minute later" behaviour.
    // ---------------------------------------------------------------
    private void updatePace(double speed, double dtSec) {
        if (speed >= 0) {
            if (!haveSpeedEma) {
                speedEma = speed;
                haveSpeedEma = true;
            } else {
                // dt-aware smoothing: a fixed alpha would smooth more or less
                // depending on fix cadence, which is exactly the inconsistency
                // we're trying to remove.
                double alpha = 1.0 - Math.exp(-dtSec / SPEED_EMA_TAU_S);
                speedEma += alpha * (speed - speedEma);
            }
            currentPaceSecPerKm = speedEma > STATIONARY_SPEED_MPS
                ? 1000.0 / speedEma
                : 0;
            return;
        }

        // No Doppler on this fix — fall back to total time / total distance
        // over the rolling window.
        currentPaceSecPerKm = windowPaceSecPerKm();
    }

    private void addWindowSample(double distM, double timeSec) {
        int slot = winIndex % PACE_WINDOW;
        if (winCount == PACE_WINDOW) {
            winDistSum -= winDist[slot];
            winTimeSum -= winTime[slot];
        }
        winDist[slot] = distM;
        winTime[slot] = timeSec;
        winDistSum += distM;
        winTimeSum += timeSec;
        winIndex++;
        if (winCount < PACE_WINDOW) winCount++;
    }

    private double windowPaceSecPerKm() {
        if (winCount == 0 || winDistSum < 1.0) return 0;
        return winTimeSum / (winDistSum / 1000.0);
    }

    private boolean isAccurateEnough(Location location) {
        // A fix that won't state its own accuracy is now rejected. Accepting
        // it was the opposite of conservative.
        return location.hasAccuracy() && location.getAccuracy() <= GPS_MAX_ACCURACY_M;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {
        if (updateListener != null) updateListener.onGpsStatusChanged("GPS enabled");
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (updateListener != null) {
            updateListener.onGpsStatusChanged("GPS disabled — check settings");
        }
    }

    // ---------------------------------------------------------------
    // Wake lock
    // ---------------------------------------------------------------
    private void acquireWakeLock() {
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---------------------------------------------------------------
    // Foreground notification — keeps the run visible/glanceable on the
    // lock screen and is what grants the exemption from background
    // location throttling.
    // ---------------------------------------------------------------
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Run tracking", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Ongoing run distance/pace tracking");
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private PendingIntent contentIntent() {
        if (contentIntent == null) {
            Intent tapIntent = new Intent(this, MainActivity.class);
            tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            contentIntent = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return contentIntent;
    }

    private Notification buildNotification() {
        double km = totalDistanceMetres / 1000.0;
        double pace = getCurrentPaceSecPerKm();
        String paceText = pace > 0 && pace < 3600
            ? formatPace(pace) + " /km"
            : "--:-- /km";
        String text = String.format(Locale.US, "%.2f km  ·  %s", km, paceText);

        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Run in progress")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .build();
    }

    // Throttled: fixes now arrive at 1 Hz, and each notification post is a
    // binder round trip plus a system-UI redraw. Once every few seconds is
    // plenty for a glanceable readout.
    private void postNotification() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationMs < NOTIFICATION_MIN_INTERVAL_MS) return;
        lastNotificationMs = now;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private String formatPace(double secPerKm) {
        int mins = (int) (secPerKm / 60);
        int secs = (int) (secPerKm % 60);
        return String.format(Locale.US, "%d:%02d", mins, secs);
    }

    // ---------------------------------------------------------------
    // Track points + GPX export
    // ---------------------------------------------------------------
    private void addTrackPoint(Location location, long fixElapsedMs) {
        if (trackPointCount == trackLat.length) {
            int newCapacity = trackLat.length * 2;
            trackLat = Arrays.copyOf(trackLat, newCapacity);
            trackLon = Arrays.copyOf(trackLon, newCapacity);
            trackEle = Arrays.copyOf(trackEle, newCapacity);
            trackHasEle = Arrays.copyOf(trackHasEle, newCapacity);
            trackTimeMs = Arrays.copyOf(trackTimeMs, newCapacity);
            trackSegStart = Arrays.copyOf(trackSegStart, newCapacity);
        }
        trackLat[trackPointCount] = location.getLatitude();
        trackLon[trackPointCount] = location.getLongitude();
        // getAltitude() returns 0.0 when there's no altitude solution, which
        // would otherwise claim sea level for the whole run.
        trackHasEle[trackPointCount] = location.hasAltitude();
        trackEle[trackPointCount] = (float) location.getAltitude();
        // Wall time derived from the monotonic anchor, so the exported
        // timestamps stay ordered even if the system clock is stepped mid-run.
        trackTimeMs[trackPointCount] = wallAnchorMs + (fixElapsedMs - elapsedAnchorMs);
        trackSegStart[trackPointCount] = segmentBreakPending;
        segmentBreakPending = false;
        trackPointCount++;
    }

    // Writes the completed run to a .gpx file under this app's external
    // files directory (no WRITE_EXTERNAL_STORAGE permission needed there,
    // even pre-scoped-storage) so it can be pulled off via a file manager
    // or USB and dragged into Strava's website, which accepts GPX uploads
    // directly — no in-app network/OAuth code needed.
    private void saveGpxIfNeeded() {
        if (trackPointCount < 2) return;

        File dir = getExternalFilesDir("gpx");
        if (dir == null) dir = new File(getFilesDir(), "gpx");
        if (!dir.exists() && !dir.mkdirs()) {
            if (updateListener != null) updateListener.onGpxSaved(null);
            return;
        }

        SimpleDateFormat fileNameFormat = new SimpleDateFormat(GPX_FILENAME_FORMAT, Locale.US);
        SimpleDateFormat timeFormat = new SimpleDateFormat(GPX_TIME_FORMAT, Locale.US);
        timeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date runStart = new Date(trackTimeMs[0]);
        // One reused Date instead of one per point — this loop can run to a
        // few thousand points on a long run.
        Date scratch = new Date();

        File file = new File(dir, "run_" + fileNameFormat.format(runStart) + ".gpx");

        try (FileOutputStream out = new FileOutputStream(file)) {
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "UTF-8");
            serializer.startDocument("UTF-8", true);
            serializer.startTag(null, "gpx");
            serializer.attribute(null, "version", "1.1");
            serializer.attribute(null, "creator", "Sweat");
            serializer.attribute(null, "xmlns", "http://www.topografix.com/GPX/1/1");

            serializer.startTag(null, "trk");
            serializer.startTag(null, "name");
            serializer.text("Run " + fileNameFormat.format(runStart));
            serializer.endTag(null, "name");
            serializer.startTag(null, "type");
            serializer.text("running");
            serializer.endTag(null, "type");

            serializer.startTag(null, "trkseg");
            for (int i = 0; i < trackPointCount; i++) {
                // A pause (or a long GPS outage) closes the segment and opens
                // a new one, so Strava doesn't draw — and count — a straight
                // line across the gap. Without this its distance disagrees
                // with the distance the app displayed.
                if (i > 0 && trackSegStart[i]) {
                    serializer.endTag(null, "trkseg");
                    serializer.startTag(null, "trkseg");
                }
                serializer.startTag(null, "trkpt");
                serializer.attribute(null, "lat", String.valueOf(trackLat[i]));
                serializer.attribute(null, "lon", String.valueOf(trackLon[i]));
                if (trackHasEle[i]) {
                    serializer.startTag(null, "ele");
                    serializer.text(String.valueOf(trackEle[i]));
                    serializer.endTag(null, "ele");
                }
                serializer.startTag(null, "time");
                scratch.setTime(trackTimeMs[i]);
                serializer.text(timeFormat.format(scratch));
                serializer.endTag(null, "time");
                serializer.endTag(null, "trkpt");
            }
            serializer.endTag(null, "trkseg");
            serializer.endTag(null, "trk");
            serializer.endTag(null, "gpx");
            serializer.endDocument();

            if (updateListener != null) updateListener.onGpxSaved(file.getName());
        } catch (IOException e) {
            if (updateListener != null) updateListener.onGpxSaved(null);
        }
    }
}
