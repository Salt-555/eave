# P0 device measurements: Pixel 10 Pro input path

Measured 2026-11-13 with the `probe` harness before it was replaced by the live
detector. These are the only numbers in this repository that came from a real
microphone rather than a synthetic generator. Everything in `results/` is
synthetic until this file grows a stimulus row.

Device: Pixel 10 Pro, Android 17, `ro.board.platform=laguna`.
Nothing here is a detection claim. It is a statement of what the input path
passes, which is the feasibility gate for the near-ultrasonic detector.

## Policy ceiling

`dumpsys media.audio_policy`: 96000 Hz and 88200 Hz appear only as Bluetooth
A2DP **output** profiles. Declared input profiles top out at 48000 Hz. Declared
inputs: bottom mic, back mic, echo reference. So Nyquist for capture is 24 kHz
and nothing above 24 kHz is reachable on this handset at all, by any means.

Policy is not response. The Android CDD allows a large high-frequency droop
while still reporting `UNPROCESSED`, so the measured sweeps below are the
evidence, not the dump.

## Measured spectra, quiet room, no stimulus

Room was unremarkable domestic background. Levels are mean per-bin level inside
each band, referenced to a full-scale sine (0 dBFS). Each clip is the same room,
seconds apart, so the spread between rows includes both the room and the
processing chain.

| AudioSource | rms | 1-6k | 12-16k | 17-21k | 21-23.9k |
|---|---|---|---|---|---|
| DEFAULT             | -54.6 | -96.0 | -104.7 | -108.7 | -103.0 |
| MIC (10 s)          | -56.2 | -102.7 | -123.7 | -123.0 | -115.6 |
| MIC (3 s)           | -59.4 | -103.3 | -132.8 | -129.4 | -121.2 |
| CAMCORDER           | -65.9 | -106.4 | -124.2 | -122.2 | -122.8 |
| VOICE_RECOGNITION   | -64.2 | -104.3 | -113.6 | -119.5 | -120.4 |
| VOICE_COMMUNICATION | -45.4 | -98.2 | -92.4 | -99.7 | -109.1 |
| UNPROCESSED         | -78.1 | -122.0 | -121.4 | -132.4 | -133.6 |

What this settles:

1. **There is no 16 kHz cliff.** The 17-21 kHz band is present in every source,
   typically 10-30 dB below the low band, not gone. The near-ultrasonic plan is
   feasible on this handset.
2. **`UNPROCESSED` is the quietest**, by 15-35 dB everywhere, and it is the
   source that skips noise suppression and AGC, which are the two processings
   most likely to eat or move a narrow near-ultrasonic tone. It is the capture
   source for the live detector, with MIC and DEFAULT as fallbacks.
3. `VOICE_COMMUNICATION` is 33 dB louder in the band than `UNPROCESSED` only
   because it runs make-up gain. Higher level here is self-noise, not
   sensitivity, and it comes with AEC and NS on top. Do not use it.

## Narrow spurs above 16 kHz, which synthetic audio never had

| AudioSource | loudest bin > 16 kHz | level |
|---|---|---|
| DEFAULT   | 21645 Hz | -93.8 dBFS |
| MIC (10 s)| 22500 Hz | -107.3 dBFS |
| VOICE_COMMUNICATION | 17122 Hz | -90.3 dBFS |
| CAMCORDER | 21418 Hz | -114.9 dBFS |

Consequence for the detector, and the reason the published false-alarm rate of
`0.000` cannot be carried over: the surrogate generator's 17-21 kHz band was
near-empty by construction, so any narrowbin rule looked clean on it. A real
capture chain has narrow, persistent, non-acoustic peaks, some of them within a
few hundred Hz of the middle of the band. A near-ultrasonic detector must be
tuned against the handset's own spur map, which is why the live app reports the
band median and the best contrast continuously instead of only reporting
alarms.

## Floor measurement: what replaced the -95 dBFS placeholder

Measured 2026-11-14 16:37-16:41 local, same room as the spectra above, with
the P0 recorder reinstalled from `199bd3d` (one type-name fix to compile
against current compileSdk) and analysed by feeding the captures through
the shipped `ToneDetector` itself, so window, padding and calibration
conventions are the detector's own, not a re-derivation.

Quiet room, 30 s per source, band = 17-21 kHz, levels per 1 s window:

| AudioSource | band median p50 | band median p99 | best-bin p99 | best-bin max | gate-pass windows @-100 |
|---|---|---|---|---|---|
| DEFAULT       | -128.1 | -97.5  | -84.3  | -79.6  | 0/117 |
| MIC           | -130.3 | -99.1  | -85.0  | -80.8  | 0/117 |
| CAMCORDER     | -121.0 | -98.9  | -82.7  | -82.3  | 14/117 (!) |
| VOICE_RECOGNITION | -116.3 | -85.0 | -72.3 | -67.5 | 0/117 (contrast never >= 20) |
| VOICE_COMMUNICATION | -118.4 | -105.1 | -89.3 | -85.0 | 11/117 (!) |
| UNPROCESSED   | -130.3 | -103.7 | -91.0  | -90.8  | 0/117 |
| UNPROCESSED 120 s | -132.6 | -111.2 | -99.5 | -94.5 | 0/477 (@-105: 2/477) |

Two facts the synthetic work could not have predicted:

1. CAMCORDER and VOICE_COMMUNICATION pass the two-gate window test 11-14
   times per 117 windows on a silent-ish room, purely on level. A floor at
   -100 cannot run those sources at all; the service's ladder
   (UNPROCESSED -> MIC -> DEFAULT) is not a preference, it is a
   requirement, and the fallback order should never reach them.
2. The room is not stationary: UNPROCESSED p99 improved 7.6 dB between the
   30 s and 120 s captures, and the 2/477 transients at -105 are room
   events, not chain artifacts. The floor therefore protects the quiet
   case only; a loud room raises the band median and the contrast term
   takes over the defense.

Adopted: `FLOOR_DBFS = -100.0`, five dB above the first transient the room
produced in 1017 quiet-room windows, and 5 dB more sensitive than the
placeholder it replaces. Cross-checked against the 68-minute programme
corpus with the shipped rule before adoption (see docs/REAL-AUDIO.md;
final alarm count in the adopting commit).

Caveats kept honest: one room, one handset, daytime. The 10-minute
continuous capture attempted for this section stalled twice at ~11 minutes
with the input thread going Standby under wake lock (power management
reclaiming the UNPROCESSED stream) - itself a P0 finding: long listens on
this device need the live service's stalled-read path, which exists, and
a long-capture reliability question filed to the roadmap.

## Not yet measured

- Transmit response. No stimulus has been played in the room while the handset
  recorded, so the mic-plus-input-chain gain at 19 kHz relative to 1 kHz is
  still unknown. A null at 19 kHz would have been ambiguous anyway, because a
  phone loudspeaker is port-limited well below 19 kHz.
- `AudioRecord` at 96 kHz was attempted and is expected to fail, since no input
  profile declares it. Not confirmed as a failure: that run was superseded by
  the rewrite.
- A single continuous capture longer than ~12 minutes has not completed; both
  attempts went Standby mid-stream. The floor section's longest clean capture
  is 120 s. Overnight-length reliability is an open question, not a measured
  capability.
