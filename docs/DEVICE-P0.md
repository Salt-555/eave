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

## Not yet measured

- Transmit response. No stimulus has been played in the room while the handset
  recorded, so the mic-plus-input-chain gain at 19 kHz relative to 1 kHz is
  still unknown. A null at 19 kHz would have been ambiguous anyway, because a
  phone loudspeaker is port-limited well below 19 kHz.
- `AudioRecord` at 96 kHz was attempted and is expected to fail, since no input
  profile declares it. Not confirmed as a failure: that run was superseded by
  the rewrite.
- Microphone self-noise in a genuinely quiet room, which the `-95 dBFS`
  absolute floor in the detector is standing in for until measured.
