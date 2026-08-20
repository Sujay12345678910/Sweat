package com.runtracker;

import android.content.Context;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

// Manual "sync to NAS" over SFTP — never automatic. This device is meant
// to work with no connectivity during a run, so the app only ever touches
// the network when the user explicitly taps Sync (see MainActivity).
//
// Connection details + the SSH private key live as plain files under this
// app's own external files dir (Android/data/com.runtracker/files/nas/),
// not baked into the APK — so no storage permission is needed (it's the
// app's own directory) and the user can update NAS details or rotate keys
// by dropping new files in via USB/microSD, without rebuilding the app.
// See README.md for the exact file layout expected there.
public class NasSync {

    public interface Listener {
        void onProgress(String message);
        void onComplete(int uploadedCount, int failedCount);
    }

    private static final String CONFIG_DIR = "nas";
    private static final String CONFIG_FILE = "nas_config.properties";
    private static final String KEY_FILE = "id_rsa";
    private static final String UPLOADED_SUBDIR = "uploaded";

    private final Context context;
    private volatile boolean syncing = false;

    public NasSync(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isSyncing() {
        return syncing;
    }

    public void syncPendingGpxFiles(Listener listener) {
        if (syncing) return;
        syncing = true;

        new Thread(() -> {
            int uploaded = 0;
            int failed = 0;
            Session session = null;
            ChannelSftp sftp = null;
            try {
                Properties config = loadConfig();
                if (config == null) {
                    listener.onProgress("No NAS config found — see README");
                    return;
                }

                File gpxDir = context.getExternalFilesDir("gpx");
                File[] pending = gpxDir != null
                    ? gpxDir.listFiles((d, name) -> name.endsWith(".gpx"))
                    : null;
                if (pending == null || pending.length == 0) {
                    listener.onProgress("Nothing to sync");
                    return;
                }

                File keyFile = new File(context.getExternalFilesDir(CONFIG_DIR), KEY_FILE);
                if (!keyFile.exists()) {
                    listener.onProgress("No SSH key found at nas/" + KEY_FILE);
                    return;
                }

                JSch jsch = new JSch();
                jsch.addIdentity(keyFile.getAbsolutePath());

                session = jsch.getSession(
                    config.getProperty("username"),
                    config.getProperty("host"),
                    Integer.parseInt(config.getProperty("port", "22")));
                // Trusting the NAS's host key without verification —
                // acceptable for a personal device talking to a NAS on
                // your own home LAN, but it is a real MITM weakening.
                // Swap this for proper known-hosts pinning if that matters
                // to you.
                Properties sessionConfig = new Properties();
                sessionConfig.put("StrictHostKeyChecking", "no");
                session.setConfig(sessionConfig);
                session.connect(15000);

                sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect(10000);

                String remotePath = config.getProperty("remotePath", "/");
                File uploadedDir = new File(gpxDir, UPLOADED_SUBDIR);
                if (!uploadedDir.exists()) uploadedDir.mkdirs();

                for (File f : pending) {
                    try {
                        listener.onProgress("Uploading " + f.getName() + "...");
                        try (FileInputStream in = new FileInputStream(f)) {
                            sftp.put(in, remotePath + "/" + f.getName());
                        }
                        // Move aside on success so re-running Sync doesn't
                        // re-upload it.
                        f.renameTo(new File(uploadedDir, f.getName()));
                        uploaded++;
                    } catch (Exception e) {
                        failed++;
                    }
                }
            } catch (Exception e) {
                listener.onProgress("Sync failed: " + e.getMessage());
            } finally {
                if (sftp != null) sftp.disconnect();
                if (session != null) session.disconnect();
                syncing = false;
                listener.onComplete(uploaded, failed);
            }
        }).start();
    }

    private Properties loadConfig() {
        File dir = context.getExternalFilesDir(CONFIG_DIR);
        File configFile = dir != null ? new File(dir, CONFIG_FILE) : null;
        if (configFile == null || !configFile.exists()) return null;
        try (FileInputStream in = new FileInputStream(configFile)) {
            Properties props = new Properties();
            props.load(in);
            return props;
        } catch (Exception e) {
            return null;
        }
    }
}
