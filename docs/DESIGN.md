# Eave

Acoustic counter-surveillance for a Pixel 10. Detector, not defender: it tells you
when a channel is live, and attributes it to a package where the OS lets it.

Status: theory crafted, PHY validated offline, no device code yet.
Owner: Salt. Prototype: `phy/eave_phy.py`

---

## 1. Threat model

Assume any of these is pointed at the room:

| # | Channel | Band | Real-world status |
|---|---|---|---|
| T1 | Passive acoustic fingerprinting (ACR) | whole audible programme | deployed at scale. Alphonso (now LG Ad Solutions), Vizio ($2.2M FTC order), TV-internal ACR (Texas AG v. Sony/Samsung/LG/Hisense/TCL, Dec 2025; Samsung settled Feb 2026) |
| T2 | In-band masked watermark | 1–6.3 kHz | deployed, standardised. Nielsen NAES2 4.5–6.3k, NAES6 2.99–4.8k, CBET 1–3k, ATSC A/334 VP1 2.5–5k |
| T3 | Echo-modulation mark | broadband, temporal | vendor-claimed robustness, no independent verification found |
| T4 | Near-ultrasonic beacon, locally emitted | 18–20 kHz | real at the last metre (4/35 stores in the Braunschweig survey), dead through streaming codecs |
| T5 | Metadata twin (watermark lifted into ID3 / data PID) | not sound at all | normal on streaming paths; Nielsen DTVR reads ID3 pids |
| T6 | Sensor-side acoustic channel | 20 Hz–1 kHz mechanical, or active sonar | documented attack primitive, no evidence of an ad pipeline using it |

Adversary set: ad-tech SDKs inside apps you installed; broadcasters and streamers;
your television's own firmware; any emitter you don't own.

Goal: detect, attribute, log evidence with defensible timestamps.
Non-goals: defeating a privileged/rooted adversary; network deanonymisation of the
TV from the phone alone (that is the gateway probe's job, §M8).

---

## 2. The constraint that shapes the whole design

Found in AOSP `main`, not inferred:

`services/core/java/com/android/server/appop/AppOpsService.java`
```java
public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
    int watchedUid = Process.INVALID_UID;
    final int callingUid = Binder.getCallingUid();
    if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
            != PackageManager.PERMISSION_GRANTED) {
        watchedUid = callingUid;          // <-- silently narrowed to your own app
    }
```

`core/res/AndroidManifest.xml`
```xml
<!-- Allows an application to watch changes and/or active state of app ops.
     @hide <p>Not for use by third-party applications. -->
<permission android:name="android.permission.WATCH_APPOPS"
    android:protectionLevel="signature|privileged" />
```

And `isOperationActive()` is worse than refusing:

```java
public boolean isOperationActive(int code, int uid, String packageName) {
    if (Binder.getCallingUid() != uid) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;                 // <-- a lie, not an error
        }
    }
```

Consequences, both load-bearing:

1. **A normal sideloaded app cannot see anybody else's microphone.** The API that
   looks like the right answer returns an empty feed instead of an error. Any
   "who's spying on me" app built on `isOperationActive` is reporting "all clear"
   while being denied. Treat that as the first trap in the project.
2. **The privileged half has to run as a uid that holds WATCH_APPOPS.**
   `packages/Shell/AndroidManifest.xml` grants `com.android.shell`
   `WATCH_APPOPS`, `MANAGE_APP_OPS_MODES` and `READ_LOGS`. A Shizuku remote service
   started over wireless debugging runs at shell uid, so it should pass the check
   without root. That is inference from source and must be confirmed on the device
   in P2; if it fails, the fallback is a companion daemon polling
   `dumpsys appops` / `dumpsys audio` over adb.

---

## 3. Three planes, and why none of them substitutes

- **Plane A, acoustic** (no privileges): what is physically in the air.
- **Plane B, OS truth** (shell uid): who actually opened the mic, when, attributed
  to which package and attribution tag.
- **Plane C, network** (own traffic on-device; whole LAN via gateway): who is
  uploading, and how regularly.

They are orthogonal and the gaps are not small.

**T1 passive fingerprinting has no acoustic signature whatsoever.** It is Shazam on
the room. Plane A can never see it, at any sensitivity. It is visible only as a mic
open (Plane B) plus a periodic small upload (Plane C).

**T4 ultrasonic beacons have no network signature until something reports.** Plane
A sees the beacon; the tracker is only caught when it goes to tell. Plane A alone
finds the emitter, not the eavesdropper.

**T5 costs nothing to ignore and everything to notice.** No sound is involved on
the app side. The phone-side acoustic mark is the fallback for linear/OTA paths;
where content arrives over HTTP the tag is read instead. Any purely acoustic threat
model has a hole the size of streaming in it.

---

## 4. Modules

**M1 Capture.** `AudioRecord`, 48 kHz mono, 16-bit. Source ladder: `UNPROCESSED` →
`VOICE_RECOGNITION` → `MIC`, recording which one was actually granted, and whether
the device advertises `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`. Foreground
service, type `microphone`, persistent notification.
Two notes. The CDD §5.11 requirement for a device to claim `UNPROCESSED` allows
±30 dB of droop across 7 kHz–22 kHz, so the ultrasonic band can legally arrive 30 dB
down and the threshold must be per-device calibrated, not hardcoded. And Eave's own
mic notification is a tripwire: if Android shows a second mic indicator while only
Eave is recording, something else is holding the mic, and that observation needs no
privilege at all.

**M2 Detectors** (offline-validated, §5):
- `D1` generic near-ultrasonic presence + temporal constancy
- `D2` M-FSK beacon demod, SilverPush shape (26 tones 18–20 kHz, 5-symbol beacon,
  the SDK's own "no repeats + must contain A" validation) and Lisnr shape
  (2 tones 18.5–19.5 kHz)
- `D3` ATSC A/334 VP1 receiver, spec-faithful: 2.5–5 kHz, autocorrelation
  difference, τ = 3 ms, symbol T = 1/106 s, 159-bit cell, 32-bit header acquisition,
  both standard and inverse signaling modes
- `D4` Nielsen-band occupancy + TDM periodicity (1.87 s NAES2, 4.8 s NAES6/CBET).
  Presence and timing, **not** a SID decoder, which would need licensed codebooks
- `D5` IMU coherence: accelerometer spectrum against mic spectrum in 20 Hz–1 kHz
  while the TV is driven. Tests the structure-borne and sonar family, T6
- `D6` HF calibration sweep: play a known comb, measure this unit's actual droop
  curve, store it as the device profile

**M3 Trap broadcast.** The strongest single idea in the design, and cheap.
Eave plays a synthetic mark carrying a fresh random ID from its own speaker, at a
moment it recorded. Ground truth is Eave's, so any event in the response window is
a correlate, and correlates can be charged to a package from Plane B and a socket
from Plane C. Rotate the ID per trial so legacy validation rules never see a
repeat. Run a control trial with the TV off to measure the false-correlate rate.
This turns passive monitoring into an experiment with a known answer, which is the
only way to say "app X reported" rather than "app X might report".

**M4 OS-truth watcher** (Shizuku). Subscribe `OPSTR_RECORD_AUDIO` active-changes for
all uids. Log uid, package, **attribution tag**, active, timestamps (boot + wall).
The attribution tag is the tell: an SDK attributing to `com.something.pulse` inside a
game's process is a different animal than the game itself asking.

**M5 Traffic periodicity.** `TrafficStats` per uid every 5 s (Usage access). Flag
small payload, high period regularity; correlate against M4 open-mic events and M3
trap times. ACR looks like a few hundred bytes every 30–120 s, which is a very
distinctive signature next to real app traffic.

**M6 Surface map** (no privileges). Enumerate holders of `RECORD_AUDIO`,
`FOREGROUND_SERVICE_MICROPHONE`, `MODIFY_AUDIO_SETTINGS`, with `QUERY_ALL_PACKAGES`
for visibility. Static SDK signature scanning cannot run on-device because another
app's APK is not readable there; run it over adb on the companion and match against
known signatures (Alphonso, SilverPush, Lisnr, Shopkick, Vubify/SyncNow, SecondSync).

**M7 Evidence store.** Append-only JSONL, monotonic and wall clock paired, export as
a single file. Screens: live spectrogram, event timeline, per-app verdict, self-test.
If it can't export, it isn't evidence.

**M8 Gateway companion** (separate, optional). DNS and SNI logging per LAN device on
the TV VLAN. This is the only way to see the television's *own* ACR uploads, which
the phone can never hear. Runs on the Halo or the router, not the phone.

---

## 5. Offline PHY validation

`eave_phy.py` runs four experiments against a broadcast-like programme surrogate
(harmonic stack, shaped noise, syllabic AM, transients, hard roll-off above 15 kHz,
plus a controllable 17–21 kHz noise floor standing in for mic self-noise).
Results are appended below only when actually measured.

Results are withheld until the unexplained sweep in the current run is accounted for; see
`results/README.md`.

---

## 6. Build phases

- **P0, one evening, on-device feasibility.** Pixel 10: does it advertise
  `UNPROCESSED`? Play known marks through a speaker, capture, measure real droop and
  real noise floor, confirm D1/D2 fire on real air. Nothing else is worth building
  until this returns numbers, because every threshold in §5 is synthetic.
- **P1 Plane A app.** M1 + D1 + D2 + spectrogram + evidence log. Useful alone.
- **P2 Plane B.** Shizuku service, M4, timeline correlation with Plane A.
  The experiment: does shell-uid actually satisfy the WATCH_APPOPS check.
- **P3 Active probes.** M3 trap broadcast + M5 traffic periodicity. This is where
  attribution starts.
- **P4 Hard detectors.** D3 VP1 decode, D4 Nielsen-band, D5 IMU coherence.
- **P5 Gateway.** M8.

---

## 7. Honest limits

- TV-internal ACR is invisible acoustically. Only M8 or the TV's own settings see it.
- The ID3-tag path involves no sound and cannot be detected as sound.
- A passive ACR SDK that only samples while foregrounded, hashes on device, and
  uploads on a jittered schedule is indistinguishable from an ordinary app on Plane
  C. Plane B still sees the mic open, which is why B is not optional.
- Eave can only hear while it is recording, and Android blocks background mic for it
  exactly as it does for everyone. An "always listening" mode with a hidden
  foreground service would be technically reachable and would make Eave
  indistinguishable from the thing it hunts. Keeping the notification is a feature.
- Every sensitivity number in §5 is synthetic until P0 runs. The ±30 dB CDD droop
  allowance and real room noise will move the ultrasonic threshold by an unknown
  amount, possibly a lot.
- Eave must never upload audio, and must have no server. If it does, it is a
  fingerprinting SDK with better branding.
