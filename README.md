# Eave

Acoustic counter-surveillance for Android. It answers one question: **is there a
covert audio channel in my room right now?**

Detector, not defender. Eave tells you a channel is live, where it sits, and what
shape it has. It does not block, jam, or mislead, and in this scope it does not try
to name the application that reports it either.

> Status: threat model and architecture settled, physical-layer detectors validated
> offline against synthetic signals. A first live Android detector (foreground
> service, held-tone + periodic-mark) is in `android/` and its JVM tests pass.
> Real-microphone feasibility is measured in [docs/DEVICE-P0.md](docs/DEVICE-P0.md);
> false-alarm behaviour against real programme audio in
> [docs/REAL-AUDIO.md](docs/REAL-AUDIO.md) — the held-tone gate holds there
> (0/5 recordings, 14.7 dB margin); the mark gate was recalibrated on a
> 57-minute corpus after the original one produced a false alarm. The tone
> floor is now the measured −100 dBFS, not a placeholder.
> Nothing has yet been measured over the air. See [Limits](#limits).

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

**Eave is currently built on Plane A only.** That is a deliberate narrowing, and its
cost is stated in the next section rather than buried.

---

## Scope: listen for emitters, nothing else

Eave detects devices *transmitting* over sound. It does not inspect which apps hold
the microphone, does not read per-application traffic, and does not require any
privileged setup: no root, no Shizuku, no adb companion, no usage-access grant. One
runtime permission, one foreground service, one APK anyone can install.

That removes the hardest problem in the design. See [the permission wall](#the-constraint-that-shapes-everything)
for what we are *not* having to fight, kept in the README because it is a trap for
anyone building the other kind of tool.

It also removes the ability to see the most widely deployed threat, and pretending
otherwise would make this tool useless for its actual purpose.

| Threat | Emission | Can Eave see it? |
|---|---|---|
| Retail/kiosk ultrasonic beacons (Lisnr, Shopkick shape) | 18–20 kHz from a local speaker | **Yes**, best case: no codec in the path |
| Set-top, soundbar or TV firmware injecting after the decoder | 18–20 kHz from its own amp | **Yes**, same reason |
| A device in your home emitting a pairing/cross-device tone | 18–20 kHz from its own speaker | **Yes**, and no privilege needed to hear it |
| Broadcast/streaming watermark carried by the TV loudspeaker (VP1, NAES, CBET) | 1–6.3 kHz, under masking | **Partly**: presence and timing yes; decoding the identifier needs licensed codebooks |
| Television-internal content recognition (ACR) | none, it samples internally | **No** |
| Passive phone-side fingerprinting (Alphonso/SecondSync shape) | none, it only listens | **No, ever** |

The last two rows are the whole reason Plane B exists, and they are simply outside
what an emitter detector can know. Passive fingerprinting is Shazam run in reverse:
it consumes sound and never produces any, so there is no signal to detect, no
matter how good the receiver is. A house can be fully instrumented this way and
Eave will report a quiet room, correctly.

The trade is worth making anyway. Plane A is the only part that works on hardware
you don't control, needs nobody's permission, and can be published where anyone can
install it. It is also where the interesting signal-processing failures live.

[arp]: https://mlsec.org/docs/2017a-eurosp.pdf
[nyt]: https://www.nytimes.com/2017/12/28/business/media/alphonso-app-tracking.html

---

## The wall we no longer have to climb

Retained because it is the most common dead end for anyone attempting the *other*
kind of tool, the kind that reports which app holds the microphone. That design is
closed to ordinary apps, and it fails silently rather than loudly:

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
- **A measurement filter must not leak into the band it measures.** Found by a test,
  not by reading. The bandpass used logistic-shaped skirts, which only reach about
  −34 dB a few kilohertz past the corner, and the synthetic programme audio has wideband
  transient content out there. Result: the 17–21 kHz band was mostly leakage from the
  programme, and the noise-floor knob that the whole sensitivity sweep turned moved
  the band it claimed to control by **0.49 dB for a 24 dB change**. Every floor row in
  any table produced before this fix was measuring the same room. The skirts are now
  raised-cosine with an exactly zero stopband, and a test asserts the knob tracks its
  own band, because a knob that silently does nothing makes the sweep look like it
  covered conditions it never touched.
- **A content-change test must run where the content lives.** Measuring it at 13–16
  kHz on band-limited material gives an empty, near-stationary band whose
  correlation sits on the decision threshold, so tiny level changes flip it.
- **A learned threshold is only as good as the isolation of the band it is learned
  from.** I wrote this bullet three times, and the first two versions were wrong.
  What I originally reported was that `ambient_mean + k·sigma` in the dB domain is
  "deaf in a quiet room", backed by a MAD of 8.56 dB against 2.71 dB and a gate
  landing at +29 dB. An independent reimplementation reproduced that arithmetic to
  within 0.13 dB and then destroyed the explanation: after the filter fix above, the
  spread is *flat* at 0.07 dB from −96 dBFS to −48 dBFS, the same learned gate
  detects every tone level it had supposedly missed, and the asymmetry vanishes. The
  band was never quiet and never "a relative measurement of nothing"; it was full of
  leaked programme audio, and `med + 6σ` had faithfully reported the contamination as
  a giant threshold. A learned dB gate is in fact *most* sensitive in a genuinely
  quiet room, because its gate sits lowest there. My headline was backwards twice.
- **Quote the convention with the number.** That "+29 dB" gate was an internal per-bin
  normalisation sitting a fixed +52.1 dB above true band dBFS, so it read as an
  impossible level to anyone who did not know the offset. Every level in this repo is
  now either dBFS against a full-scale sine, or labelled.

---

## What an independent verification pass changed

Five of the corrections in this section came from measurements I did not run myself.
The pattern is consistent enough to be the point of the section: my errors were not
arithmetic, they were claims about what a measurement *meant*.

- **"0 of 200 program clips produced a valid beacon" survives, for a different reason
  than I gave.** The zero comes from the per-symbol tonality gate (`best/second ≥ 2.0`),
  which a clean symbol clears only about 0.1% of the time, so five in a row is
  essentially impossible. The two SilverPush syntax rules contribute nothing: dropping
  the `'A'` rule changed the count by zero. Worse, my own test generator built beacons
  as "A plus four distinct letters", so both rules were satisfied by construction
  2000 out of 2000 runs.
- **The sample was too small to deserve the word zero.** 0/200 single-window tests
  bounds the rate at roughly 5,400 false beacons per hour at the search density a real
  receiver needs. A wider pass over 10,902 windows of real recorded audio bounds it at
  ≤99/hour. The number is entirely a property of the gate: relaxed to 1.25 it is
  ~360/hour, and with the gate removed, ~59,000/hour on synthetic audio and
  ~11,700/hour on real recordings, because about 13% of arbitrary five-symbol windows
  happen to pass the syntax rules.
- **"-30 dBFS: 12/12 decoded" was overstated three ways.** The demodulator was handed
  the exact symbol start the embedder used, so acquisition was never tested. −30 dBFS
  sits roughly 66 dB above the modelled per-bin noise floor, so it measured nothing
  near a limit. And the assertion tested *shape*, not content: a wrong decode that
  happened to be repeat-free and contain `'A'` would have scored as decoded. Now the
  sweep reports symbol-for-symbol recovery against level, and states the sync
  dependency: the cliff is about −80 dBFS with the start supplied and about −60 dBFS
  once the receiver must find it.
- **Clock offset was never tested and it is the binding constraint.** 26 tones across
  2 kHz means 76.9 Hz spacing, so ±38 Hz tolerance, about 2000 ppm at 19 kHz. Exact
  recovery holds to 1000 ppm and is gone at 1800 ppm, and 1% offset is total loss. It
  fails *safe*, rejecting every symbol rather than reporting a confident wrong beacon,
  and real clocks are ±10–40 ppm, so this is survivable. It is now pinned by tests
  because there is no sync tone in the modelled beacon to rescue it.
- **Real recordings measured 18–20 kHz content at −100 to −136 dBFS.** Ordinary audio
  genuinely has nothing in this band, which is good for the detector and embarrassing
  for the test: neither run ever contained content that actually lives where the
  beacon is assumed to be.

---

## Running the checks

```bash
uv venv .venv && uv pip install --python .venv/bin/python numpy pytest
.venv/bin/python -m pytest -q phy
```

Needs numpy; the codec-survival test needs ffmpeg and skips without it. Whole suite
runs in about three seconds, which is the point: the filter-leak bug above cost hours
of plausible-looking tables and this suite now catches it in one.

CI runs the same suite on every push. Tests assert **invariants**, not results: a
number in this README is a claim about one synthetic scenario, whereas a number in a
test is a property the code must keep having. The known-broken VP1 receiver is checked
in as `xfail` with its suspected cause in the reason string, so the repo states the bug
rather than hiding it.

---

## Roadmap

Emitter-only scope, so nothing here needs a privileged partner process.

| Phase | Deliverable |
|---|---|
| P0 | On-device feasibility, **the gate for everything else**: does the phone advertise `UNPROCESSED`, what is its measured high-frequency droop curve, do D1b/D2 fire through a real loudspeaker and microphone at realistic distances |
| P1 | The simple app: capture ladder, D1b, live spectrogram, alarm with time-stamped evidence log. Genuinely useful alone |
| P2 | Loopback calibration: Eave plays its own marks and checks what its own receiver recovers, producing a per-device sensitivity profile and a self-test that proves the chain still works |
| P3 | D2 beacon demodulation and classification, so "something is emitting" becomes "that is a SilverPush-shaped / Lisnr-shaped beacon at 19.2 kHz" |
| P4 | In-band receivers: D3 VP1 header acquisition, D4 Nielsen-band occupancy and TDM periodicity. Presence and timing, not identifier decoding |
| P5 | Optional LAN gateway, outside the app, for television-side uploads that never touch the air |

**Loopback calibration** replaces what was going to be the trap broadcast. The
original design played a synthetic mark with a fresh random ID and watched which
application woke up and uploaded, which required per-app visibility. Without it the
trial can attribute nothing, so the idea is rebuilt around what an emitter-only tool
*can* verify: play a known mark through the phone's own speaker, confirm the receiver
recovers it at the expected level, and sweep frequency to map where this particular
unit stops hearing. That converts "Eave says the room is clean" from a claim into a
measurement, and catches the failure mode where the mic path quietly low-passes the
band before Eave ever sees it.

---

## Limits

Stated plainly, because a tool that overstates its reach is worse than none.

- **A smart television's own content recognition is invisible acoustically.** It
  samples internally and never puts anything in the air. Only the LAN gateway or the
  television's own settings can see it.
- **The streaming metadata path involves no sound.** Where a watermark is lifted into
  an ID3 box or data PID, the app reads the tag and there is no acoustic event to find.
- **Phone-side passive fingerprinting is invisible to this tool, permanently.** It
  emits nothing, so no receiver sensitivity and no detector design changes that. In
  the full three-plane design the microphone-occupancy plane was the answer, and this
  scope deliberately does not include it. The honest statement for the shipping tool
  is: Eave detects channels, not listeners.
- **Correlating an emission to a reporting application needs per-app visibility**, which
  this scope excludes. Eave can prove a beacon is in the room and name its shape and
  frequency. It cannot, on its own, name the application that forwards it.
- **Eave can only hear while it is recording**, and Android blocks background
  microphone access for Eave exactly as it does for the trackers. An always-on mode
  with a concealed foreground service is technically reachable and would make Eave
  indistinguishable from what it hunts. The persistent notification stays.
- **Mark sensitivity figures are still synthetic; tone and feasibility
  figures are not.** P0 has run: what the Pixel 10 Pro input path passes
  above 16 kHz, and the quiet-room floor the −100 dBFS constant is set
  from, are measured ([docs/DEVICE-P0.md](docs/DEVICE-P0.md)). That floor
  is one room and one handset; the Android compatibility definition
  permits up to ±30 dB of droop between 7 kHz and 22 kHz on a device that
  still claims `UNPROCESSED` support, so on other hardware the floor will
  move by an amount that is currently unknown.
- **No server, ever.** Eave uploads nothing and holds no accounts. A counter-surveillance
  tool with a backend is just a fingerprinting SDK with better branding.

---

## Licence

Apache-2.0. Chosen over MIT for the patent grant, on the theory that ad-tech is the
most likely source of a contribution and the least likely to deserve one. Changeable
in one commit if you disagree.
