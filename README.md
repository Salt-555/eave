# Eave

Acoustic counter-surveillance for Android. It answers one question: **is there a
covert audio channel in my room right now, and which app is reporting it?**

Detector, not defender. Eave tells you a channel is live and attributes it where
the OS permits. It does not block, jam, or mislead.

> Status: threat model and architecture settled, physical-layer detectors validated
> offline against synthetic signals, no Android code yet. Nothing here has been
> measured on real hardware — see [Limits](#limits).

---

## Why not just look for ultrasound

The 2015-era story is that televisions emit an inaudible 18–20 kHz tone, your phone
hears it, and an ad server learns which TV you own. That happened, and it mostly
failed. Lossy codecs delete a perfectly masked ultrasonic tone because the
psychoacoustic model that hides it from you is the same model that tells the codec
the tone is expendable. The only study that went looking for beacons in real TV
streams ([Arp et al., IEEE EuroS&P 2017][arp]) scanned about six days of audio from
seven countries and found none, while finding live beacons in 4 of 35 physical shops.

What replaced it has no inaudible carrier to find. **Passive acoustic fingerprinting**
is Shazam pointed at your living room: sample short windows, hash on device, match
against a reference library of broadcast audio. Nothing above 16 kHz is involved, so
no detector sensitivity catches it. It is deployed at scale — Alphonso (now LG Ad
Solutions) reached hundreds of apps including games for children ([NYT, Dec 2017][nyt]),
Vizio paid a $2.2M FTC order for TV-internal collection on 11M sets, and the Texas
Attorney General sued Sony, Samsung, LG, Hisense and TCL in December 2025 over
automatic content recognition, with Samsung settling in February 2026.

So Eave watches three planes, because none of them covers the others.

| Plane | What it observes | Privilege needed | Catches |
|---|---|---|---|
| A — acoustic | what is physically in the air | none | ultrasonic beacons, in-band watermarks |
| B — OS truth | which package actually opened the microphone | shell uid | passive fingerprinting |
| C — network | who is uploading, and how regularly | usage access / gateway | reporting behaviour, TV-side uploads |

Passive fingerprinting has no signature on Plane A at any sensitivity. Ultrasonic
beacons have no signature on Plane C until something reports. These are not
substitutable and an acoustic-only tool is looking at half the problem.

[arp]: https://mlsec.org/docs/2017a-eurosp.pdf
[nyt]: https://www.nytimes.com/2017/12/28/business/media/alphonso-app-tracking.html

---

## The constraint that shapes everything

Verified in AOSP `main`, not inferred:

```java
// services/core/java/com/android/server/appop/AppOpsService.java
public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
    int watchedUid = Process.INVALID_UID;
    final int callingUid = Binder.getCallingUid();
    if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
            != PackageManager.PERMISSION_GRANTED) {
        watchedUid = callingUid;          // narrowed to your own app, no error raised
    }
```

```xml
<!-- core/res/AndroidManifest.xml -->
<!-- Allows an application to watch changes and/or active state of app ops.
     @hide <p>Not for use by third-party applications. -->
<permission android:name="android.permission.WATCH_APPOPS"
    android:protectionLevel="signature|privileged" />
```

`isOperationActive()` is worse, because it does not even fail loudly:

```java
public boolean isOperationActive(int code, int uid, String packageName) {
    if (Binder.getCallingUid() != uid) {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;                 // a false answer, not an exception
        }
    }
```

Two consequences worth writing on the wall:

1. **An ordinary app cannot see anyone else's microphone.** The API that looks like
   the obvious answer returns an empty feed or a silent `false`. A "who is spying on
   me" app built naively on `isOperationActive` reports *all clear* while being
   denied. If such an app tells you nothing is recording, that may be a fact about
   its privileges rather than about your phone.
2. **The privileged half must run as a uid that holds `WATCH_APPOPS`.**
   `packages/Shell/AndroidManifest.xml` grants `com.android.shell` `WATCH_APPOPS`,
   `MANAGE_APP_OPS_MODES` and `READ_LOGS`. A [Shizuku](https://shizuku.rikid.jp/)
   remote service started over wireless debugging runs at shell uid, so it should
   satisfy the check without root. That last step is inference from source and is
   the first thing the build has to prove on a real device.

---

## Frequencies, for reference

| System | Band | Structure | Survives |
|---|---|---|---|
| Nielsen CBET | 1–3 kHz | frequency division, 4.8 s message | 32 kbps AAC, decodes at 8 kHz sample rate |
| Nielsen NAES 6 | 2.99–4.8 kHz | TDM, 4.8 s, three slots | down to 16 kbps by codec |
| Nielsen NAES 2 | 4.5–6.3 kHz | TDM, 1.87 s, two slots | 96 kbps |
| ATSC A/334 VP1 | 2.5–5 kHz | autocorrelation difference, τ = 3 ms, T = 1/106 s, 159-bit cell | lossy compression generally |
| Kantar SNAP / INK | audible band | phase modulation, SMPTE-standardised 2017 | — |
| SilverPush | 18–20 kHz | M-FSK, 26 tones, 5 letters per beacon | not through streaming codecs |
| Lisnr | 18.5–19.5 kHz | FSK, continuous repeat | not through streaming codecs |
| Médiamétrie | 18–22 kHz | ultrasonic, the public-broadcast holdout | — |

Sources: Nielsen *Encoder Installation and Configuration Policy* Rev I (Jan 2026),
ATSC A/334:2016, Arp et al. 2017.

The pattern is the whole lesson. Every measurement system that has to survive
distribution put its mark in the **1–6.3 kHz** band, buried under auditory masking,
because that is where the pipeline is contractually obliged to preserve content.
ATSC says so directly: VP1 is "co-resident with audio energy in a region of the
audio frequency spectrum containing perceptually important components and is
therefore retained through audio distribution paths."

---

## The physical layer

`phy/eave_phy.py` implements and stress-tests the detectors against a broadcast-like
programme surrogate — harmonic stack, shaped noise, syllabic amplitude modulation,
transients, hard roll-off above 15 kHz, plus a controllable 17–21 kHz floor standing
in for microphone self-noise.

- **D1** generic near-ultrasonic presence with a temporal-constancy test
- **D2** M-FSK beacon demodulation in the SilverPush shape (26 tones, Goertzel bank,
  the SDK's own error rules) and the Lisnr shape (2 tones)
- **D3** an ATSC A/334 VP1 receiver: bandpass 2.5–5 kHz, autocorrelation difference
  at τ = 3 ms over a 1/106 s symbol, header acquisition, both signalling modes
- **D4** Nielsen-band occupancy and TDM periodicity — presence and timing, not a SID
  decoder, which needs licensed codebooks

```bash
python3 phy/eave_phy.py 1234      # D1, D2, D3, D4
python3 phy/eave_phy.py 3         # just the VP1 receiver
```

Measured output lands in [`results/`](results/). Read that directory's note before
quoting any figure from it: one sweep is currently unexplained and unpublished, and
none of it describes real hardware yet.

Three traps this project already fell into, recorded so the next one doesn't:

- **A self-referential baseline cannot see a constant carrier.** Normalising a clip
  against its own median makes a steady beacon its own reference. The baseline must
  be ambient learned over a far longer span than the alarm window.
- **A ratio-only detector false-alarms on codec collapse.** At 32 kbps AAC the alarm
  fired on a signal whose target band had *dropped* to −89 dBFS: the codec gutted the
  reference band harder than the target band, so the ratio rose on a dead signal.
  Absolute elevation is required alongside ratio.
- **A content-change test must run where the content lives.** Measuring it at 13–16
  kHz on band-limited material gives an empty, near-stationary band whose
  correlation sits on the decision threshold, so tiny level changes flip it.

---

## Roadmap

| Phase | Deliverable |
|---|---|
| P0 | On-device feasibility: does the target phone advertise `UNPROCESSED`, what is its actual high-frequency droop, do D1/D2 fire through a real speaker and mic |
| P1 | Plane A app: capture, D1 + D2, live spectrogram, evidence log |
| P2 | Plane B: Shizuku service, system-wide `OPSTR_RECORD_AUDIO` watch, timeline correlation |
| P3 | Active probes: trap broadcast plus per-UID traffic periodicity. Attribution starts here |
| P4 | D3 VP1 decode, D4 Nielsen band, D5 accelerometer/mic coherence |
| P5 | LAN gateway companion for television-side uploads |

**The trap broadcast** is the centrepiece of P3. Eave plays a synthetic mark carrying
a fresh random ID from its own speaker at a moment it recorded, then watches for any
package that wakes and uploads inside the response window. Ground truth is Eave's, so
a correlate is attributable and the trial is repeatable per app. A control run with
the television off measures the false-correlate rate. It converts watching into an
experiment with a known answer, which is the only route to "app X reported" rather
than "app X might report".

---

## Limits

Stated plainly, because a tool that overstates its reach is worse than none.

- **A smart television's own content recognition is invisible acoustically.** It
  samples internally and never puts anything in the air. Only the LAN gateway or the
  television's own settings can see it.
- **The streaming metadata path involves no sound.** Where a watermark is lifted into
  an ID3 box or data PID, the app reads the tag and there is no acoustic event to find.
- **A well-behaved fingerprinting SDK looks like a normal app on the network.**
  Foreground-only sampling, on-device hashing, jittered upload: Plane C cannot
  distinguish it. Plane B still sees the microphone open, which is why B is not optional.
- **Eave can only hear while it is recording**, and Android blocks background
  microphone access for Eave exactly as it does for the trackers. An always-on mode
  with a concealed foreground service is technically reachable and would make Eave
  indistinguishable from what it hunts. The persistent notification stays.
- **Every sensitivity figure is synthetic until P0.** The Android compatibility
  definition permits up to ±30 dB of droop between 7 kHz and 22 kHz on a device that
  still claims `UNPROCESSED` support, so real thresholds will move by an amount that
  is currently unknown.
- **No server, ever.** Eave uploads nothing and holds no accounts. A counter-surveillance
  tool with a backend is just a fingerprinting SDK with better branding.

---

## Licence

Apache-2.0. Chosen over MIT for the patent grant, on the theory that ad-tech is the
most likely source of a contribution and the least likely to deserve one. Changeable
in one commit if you disagree.
