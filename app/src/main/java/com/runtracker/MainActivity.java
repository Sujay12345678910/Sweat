package com.runtracker;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements LocationService.UpdateListener {

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------
    private TextView tvDistance, tvPace, tvTime, tvGpsStatus, tvLockHint;
    private Button btnStartStop, btnReset, btnSync;

    // Constructed in onCreate, not as a field initializer — Context
    // methods aren't safe to call on an Activity before attach() has run.
    private NasSync nasSync;

    // Touch-lock while a run is active — disables the buttons so a stray
    // tap (in a pocket/armband) can't pause or reset mid-run. The only way
    // to pause while locked is a long-press on the physical Volume Down
    // key (see onKeyLongPress), which is far less likely to trigger by
    // accident than an on-screen button.
    private boolean locked = false;

    // ---------------------------------------------------------------
    // Location service binding — GPS handling and run-state live in
    // LocationService (a foreground service) so tracking survives the
    // Activity being backgrounded. See LocationService for the pipeline.
    // ---------------------------------------------------------------
    private LocationService locationService;
    private boolean serviceBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            LocationService.LocalBinder localBinder = (LocationService.LocalBinder) binder;
            locationService = localBinder.getService();
            serviceBound = true;
            locationService.setUpdateListener(MainActivity.this);

            // Permission may well have been granted before the bind completed
            // — the two are independent async paths. Whichever finishes last
            // is the one that actually starts GPS.
            maybeStartGps();
            syncButtonAndTimerWithServiceState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            locationService = null;
        }
    };

    // ---------------------------------------------------------------
    // Display tick — polls the service rather than waiting to be pushed to.
    // Pace and distance used to update only when the service happened to
    // accept a fix, which is what made the pace readout feel like it moved
    // in jumps. Polling on a fixed 500ms cadence means the readout is always
    // current, and it's also what surfaces a GPS dropout (see updateSignal)
    // instead of leaving a stale number on screen.
    // ---------------------------------------------------------------
    private static final long TICK_MS = 500;
    private final Handler timerHandler = new Handler();
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!serviceBound || !locationService.isRunning()) return;
            tvTime.setText(formatElapsed(locationService.getElapsedMs()));
            updateDisplay(locationService.getTotalDistanceMetres(),
                          locationService.getCurrentPaceSecPerKm());
            updateSignal();
            timerHandler.postDelayed(this, TICK_MS);
        }
    };

    private static final int PERMISSION_REQUEST = 1;
    private static final int BACKGROUND_PERMISSION_REQUEST = 2;

    // Not Manifest.permission.ACCESS_BACKGROUND_LOCATION — that constant was
    // added in the API 29 platform and this project compiles against 28.
    // The string is stable and is what the runtime matches on anyway.
    private static final String ACCESS_BACKGROUND_LOCATION =
        "android.permission.ACCESS_BACKGROUND_LOCATION";

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep screen on while the activity is visible
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        tvDistance   = findViewById(R.id.tvDistance);
        tvPace       = findViewById(R.id.tvPace);
        tvTime       = findViewById(R.id.tvTime);
        tvGpsStatus  = findViewById(R.id.tvGpsStatus);
        tvLockHint   = findViewById(R.id.tvLockHint);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnReset     = findViewById(R.id.btnReset);
        btnSync      = findViewById(R.id.btnSync);

        nasSync = new NasSync(this);

        btnStartStop.setOnClickListener(v -> toggleRun());
        btnReset.setOnClickListener(v -> resetRun());
        btnSync.setOnClickListener(v -> syncToNas());

        bindService(new Intent(this, LocationService.class), connection, BIND_AUTO_CREATE);

        requestLocationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Picks up a permission the user granted from system Settings after
        // denying it here, without re-prompting them.
        if (serviceBound) {
            maybeStartGps();
            syncButtonAndTimerWithServiceState();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Do NOT unbind or stop GPS here — user may lock screen mid-run.
        // LocationService keeps running as a foreground service so
        // tracking continues; we stay bound for as long as the Activity
        // exists so the UI resyncs instantly on return.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            locationService.setUpdateListener(null);
            unbindService(connection);
            serviceBound = false;
        }
        timerHandler.removeCallbacks(timerRunnable);
    }

    // ---------------------------------------------------------------
    // Permissions
    //
    // Two stages, because since Android 10 background location has to be
    // requested separately and only after foreground location is already
    // granted — asking for both at once gets the whole request denied.
    // On the G1 (Android 8.1) ACCESS_BACKGROUND_LOCATION doesn't exist:
    // the single Allow covers background use, and it's the foreground
    // service that actually keeps GPS running there. Requesting it anyway
    // on newer platforms means this app behaves correctly on both.
    // ---------------------------------------------------------------
    private boolean hasFineLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        if (!hasFineLocation()) {
            requestPermissions(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                PERMISSION_REQUEST
            );
            return;
        }
        requestBackgroundLocationIfNeeded();
        maybeStartGps();
    }

    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) return;   // permission doesn't exist below Q
        if (checkSelfPermission(ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{ACCESS_BACKGROUND_LOCATION},
                           BACKGROUND_PERMISSION_REQUEST);
    }

    // Starts GPS once BOTH prerequisites hold. Called from every path that
    // can satisfy either one, so their completion order doesn't matter.
    private void maybeStartGps() {
        if (!serviceBound || !hasFineLocation()) return;
        locationService.requestGpsUpdatesIfPermitted();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        boolean granted = grantResults.length > 0
            && grantResults[0] == PackageManager.PERMISSION_GRANTED;

        if (requestCode == PERMISSION_REQUEST) {
            if (granted) {
                requestBackgroundLocationIfNeeded();
                maybeStartGps();
            } else {
                tvGpsStatus.setText("Location permission denied.");
            }
        } else if (requestCode == BACKGROUND_PERMISSION_REQUEST) {
            // Foreground location alone is still enough to track a run with
            // the screen on, so this isn't fatal — just note it.
            if (!granted) {
                Toast.makeText(this,
                    "Allow location \"all the time\" in Settings to keep tracking with the screen off",
                    Toast.LENGTH_LONG).show();
            }
            maybeStartGps();
        }
    }

    // ---------------------------------------------------------------
    // Run control
    // ---------------------------------------------------------------
    private void toggleRun() {
        if (!serviceBound) return;

        if (!locationService.isRunning()) {
            // START / RESUME — startForegroundService ensures the service
            // keeps running (and tracking) even once we unbind/background.
            startForegroundService(new Intent(this, LocationService.class));
            locationService.startTracking();
            btnStartStop.setText("PAUSE");
            startTicking();
            setLocked(true);
        } else {
            // PAUSE (only reachable via this button when not locked — see
            // setLocked; the volume-key path calls pauseFromVolumeKey directly)
            locationService.pauseTracking();
            btnStartStop.setText("RESUME");
            stopTicking();
            setLocked(false);
        }
    }

    private void resetRun() {
        if (serviceBound) {
            locationService.resetTracking();
        }
        stopTicking();
        setLocked(false);
        btnStartStop.setText("START");
        tvDistance.setText("0.00 km");
        tvPace.setText("--:-- /km");
        tvTime.setText("00:00:00");
        tvGpsStatus.setText("Ready");
    }

    // Always clear before posting — onResume and onServiceConnected can both
    // resync while a run is live, and two queued copies would double the
    // tick rate every time.
    private void startTicking() {
        timerHandler.removeCallbacks(timerRunnable);
        timerHandler.post(timerRunnable);
    }

    private void stopTicking() {
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void syncButtonAndTimerWithServiceState() {
        if (locationService.isRunning()) {
            btnStartStop.setText("PAUSE");
            startTicking();
            setLocked(true);
        } else {
            btnStartStop.setText(locationService.getElapsedMs() > 0 ? "RESUME" : "START");
            tvTime.setText(formatElapsed(locationService.getElapsedMs()));
            stopTicking();
            setLocked(false);
        }
        updateDisplay(locationService.getTotalDistanceMetres(),
                      locationService.getCurrentPaceSecPerKm());
    }

    // ---------------------------------------------------------------
    // Touch lock — engaged for the duration of an active run
    // ---------------------------------------------------------------
    private void setLocked(boolean isLocked) {
        locked = isLocked;
        btnStartStop.setEnabled(!isLocked);
        btnReset.setEnabled(!isLocked);
        btnSync.setEnabled(!isLocked);
        // INVISIBLE (not GONE) so the button below doesn't shift position
        // when the hint appears/disappears on every start/pause.
        tvLockHint.setVisibility(isLocked ? View.VISIBLE : View.INVISIBLE);
    }

    // ---------------------------------------------------------------
    // NAS sync — manual only, see NasSync.java
    // ---------------------------------------------------------------
    private void syncToNas() {
        if (nasSync.isSyncing()) return;
        btnSync.setEnabled(false);
        btnSync.setText("SYNCING");
        nasSync.syncPendingGpxFiles(new NasSync.Listener() {
            @Override
            public void onProgress(String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onComplete(int uploadedCount, int failedCount) {
                runOnUiThread(() -> {
                    btnSync.setText("SYNC");
                    btnSync.setEnabled(!locked);
                    Toast.makeText(MainActivity.this,
                        "Synced " + uploadedCount + ", failed " + failedCount,
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void pauseFromVolumeKey() {
        if (!serviceBound || !locationService.isRunning()) return;
        locationService.pauseTracking();
        btnStartStop.setText("RESUME");
        stopTicking();
        setLocked(false);
        Toast.makeText(this, "Paused", Toast.LENGTH_SHORT).show();
    }

    // Swallow Volume Down while locked so it doesn't just adjust media
    // volume — onKeyLongPress below decides whether a long-press pauses.
    // event.startTracking() is required here or onKeyLongPress never
    // fires at all — easy to miss since there's no error, it just silently
    // never calls back. getRepeatCount() == 0 avoids re-arming tracking on
    // every auto-repeat KeyEvent the system sends while the key is held.
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && locked) {
            if (event.getRepeatCount() == 0) {
                event.startTracking();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && locked) {
            pauseFromVolumeKey();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    // ---------------------------------------------------------------
    // LocationService.UpdateListener — called on the main thread whenever
    // the service processes an accepted GPS fix
    // ---------------------------------------------------------------
    @Override
    public void onMetricsChanged(double totalDistanceMetres, double paceSecPerKm) {
        updateDisplay(totalDistanceMetres, paceSecPerKm);
    }

    @Override
    public void onGpsStatusChanged(String status) {
        tvGpsStatus.setText(status);
    }

    @Override
    public void onGpxSaved(String fileName) {
        if (fileName != null) {
            Toast.makeText(this, "Saved " + fileName, Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Couldn't save GPX file", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------
    // Display
    // ---------------------------------------------------------------
    private void updateDisplay(double totalDistanceMetres, double paceSecPerKm) {
        double km = totalDistanceMetres / 1000.0;
        tvDistance.setText(String.format("%.2f km", km));

        if (paceSecPerKm > 0 && paceSecPerKm < 3600) {
            tvPace.setText(formatPace(paceSecPerKm) + " /km");
        } else {
            tvPace.setText("--:-- /km");
        }
    }

    // A run that quietly stops accumulating should say so. The per-fix status
    // text can't do this on its own: when fixes stop arriving, nothing fires,
    // so whatever it last said just stays on screen looking healthy.
    private void updateSignal() {
        long age = locationService.millisSinceLastFix();
        if (age > LocationService.FIX_LOST_MS) {
            tvGpsStatus.setText("GPS LOST — distance paused");
        } else if (age > LocationService.FIX_STALE_MS) {
            tvGpsStatus.setText("GPS weak — reacquiring");
        }
        // Otherwise the service's own per-fix accuracy readout stands.
    }

    // ---------------------------------------------------------------
    // Formatters
    // ---------------------------------------------------------------
    private String formatPace(double secPerKm) {
        int mins = (int) (secPerKm / 60);
        int secs = (int) (secPerKm % 60);
        return String.format("%d:%02d", mins, secs);
    }

    private String formatElapsed(long ms) {
        long totalSec = ms / 1000;
        long h   = totalSec / 3600;
        long m   = (totalSec % 3600) / 60;
        long s   = totalSec % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
