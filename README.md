# Sweat — Build & Install Guide

A barebones GPS pace tracker for Android 8.1 (Innioasis G1), shown on the
device as "Sweat" (`com.sweat`). The project folder/repo and Java package
are still named RunTracker internally — only the user-facing name and
installed package id changed, deliberately, so this build installs
alongside an older `com.runtracker`-signed copy instead of colliding with it.

## What it shows
- Distance (km)
- Current pace (min/km) — smoothed over last 5 GPS readings
- Elapsed time (hh:mm:ss)
- GPS accuracy in metres

## Building the APK

### Option A: Android Studio (recommended)
1. Open Android Studio → File → Open → select the RunTracker folder
2. Wait for Gradle sync to finish
3. Build → Build Bundle(s) / APK(s) → Build APK(s)
4. APK will be at: app/build/outputs/apk/debug/app-debug.apk

### Option B: Command line (Linux/Mac)
```bash
cd RunTracker
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

On Windows:
```
gradlew.bat assembleDebug
```

You may need to add a local.properties file pointing to your Android SDK:
```
sdk.dir=/path/to/your/Android/Sdk
```

## Installing on the Innioasis G1 via SD card

1. Copy the APK to the root of your microSD card
2. Insert the SD card into the G1
3. On the G1: Settings → Security → enable "Unknown sources"
4. Open the G1's file manager app
5. Navigate to the SD card → tap the APK → tap Install
6. Open RunTracker from the app drawer

## First run
- Tap START — the app will ask for Location permission. Grant it.
- Stand outside or near a window for a few seconds until GPS acquires
   (accuracy shown in yellow — wait until it shows a low number like "5m" or "10m")
- Tap START again to begin tracking

## Touch lock during a run
Once tracking starts, the PAUSE and RESET buttons are disabled so a stray
tap (phone in a pocket or armband) can't interrupt your run. A small
"🔒 HOLD VOLUME DOWN TO PAUSE" hint appears. To actually pause, hold the
phone's physical **Volume Down** button for about a second. This unlocks
the buttons again — tap RESUME to continue (which re-locks) or RESET to
end and save the run.

## Backing up to your NAS over SFTP

RunTracker can push saved `.gpx` files to a NAS over SFTP, but only when you
tap the **SYNC** button — it never does this automatically or in the
background, to keep the app's network use fully within your control.

**One-time setup, on your computer:**
```bash
# Dedicated key for this, no passphrase (it needs to run unattended from
# the SYNC button — restrict what this key can do on the NAS side instead
# of protecting it with a passphrase you'd have to type on the G1)
ssh-keygen -t rsa -b 4096 -f runtracker_nas_key -N ""
```
This produces `runtracker_nas_key` (private key) and `runtracker_nas_key.pub`
(public key).

**On the NAS:**
1. Enable the SSH/SFTP service (exact menu depends on your NAS — Synology:
   Control Panel → Terminal & SNMP → Enable SSH service; QNAP: Control
   Panel → Network & File Services → Telnet/SSH; a plain Linux box just
   needs `sshd` running with SFTP enabled, which it almost always already is).
2. Add the contents of `runtracker_nas_key.pub` to that user's
   `~/.ssh/authorized_keys` on the NAS.
3. Create/pick a destination folder for the GPX files, e.g. `/volume1/RunTracker`.

**On the G1 (via microSD/file manager, same as installing the APK):**
Create `Android/data/com.runtracker/files/nas/` and drop in:
- `id_rsa` — your `runtracker_nas_key` private key file, renamed to `id_rsa`
- `nas_config.properties`, containing:
  ```properties
  host=192.168.1.XXX
  port=22
  username=your-nas-username
  remotePath=/volume1/RunTracker
  ```

After that, tap **SYNC** any time you're on the same network as the NAS
(e.g. back home on WiFi) to push whatever runs have accumulated since the
last sync. Already-uploaded files are moved to `gpx/uploaded/` so repeat
taps don't re-send them.

## Getting a run into Strava
When you tap RESET after a run with at least a couple of recorded points,
the app writes a `.gpx` file (one per run, timestamped) and shows a "Saved
run_....gpx" toast. Files live in the app's own storage folder:
`Android/data/com.runtracker/files/gpx/` — browse there with a file
manager or over USB, then upload the file at strava.com/upload (Strava
accepts GPX uploads directly on their website, no app integration needed).
This is deliberate: the G1 is meant to be usable with no connectivity
during a run, so RunTracker itself never talks to the network.

## How pace is calculated
GPS reports your position every ~2 seconds (minimum 5m movement).
Each interval: pace = time_delta / distance_delta
The displayed pace is a 5-sample moving average to smooth out GPS jitter.
Readings with implausible jumps (>200m in one interval) are discarded.

## Battery note
GPS is active whenever the app is open. Close the app when not running.
The screen stays on while the app is in the foreground (intentional — so you
can glance at pace mid-run without unlocking).

Once you tap START, tracking runs as a foreground service — recording
continues (and shows in an ongoing notification) even if you lock the
screen, switch apps, or the app gets swiped away in recents. Tap PAUSE
or RESET to actually stop it; otherwise it keeps tracking and draining
battery in the background, same as any dedicated running app.
