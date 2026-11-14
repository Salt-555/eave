# Real-audio false alarms: the shipped detectors against real programme

Measured 2026-11-14 (audit2 run). Detectors under test: `Fft.java`,
`Report.java`, `ToneDetector.java`, `MarkDetector.java` copied
byte-identical from git `462a9d6` (the only delta anywhere was a
logging-only patch on the harness's copy of `MarkDetector`; detection
math untouched). Harness, full tables and the two exact commands that
produced every number: `scratch/eave/audit2/real_summary.txt`
(`RealAudio2`, `RealAudio3`).

Material: five real recordings, 48 kHz mono PCM — film soundtrack
(MrsDrGirlfriend, 16.4 s), anime OST (frieren, 22.1 s), film audio
(11.5 s), two MP3 speech transcodes (2 x 30 s). Programme audio that was
in the wild; none of it synthesised, none of it quiet-room.

## ToneDetector: zero false alarms, and a wider margin than believed

0 windows pass the two-gate rule, 0 hold-4 runs, 0 alarms, on all five
files at +0, +6 and +12 dB input gain. At +6/+12 dB the level gate alone
is crossed by three files (winning-bin levels up to -61 dBFS) but band
contrast never exceeds 15.1 dB against a 20 dB gate, so nothing gets
near the joint rule. No window within 3 dB of both gates at any gain.

The earlier verifier's scary number — "a real recording approached the
gate to within 0.1 dB" — is refuted. It conflated two different windows
of the same file: the -95.1 dBFS window had 0.8 dB of contrast, and the
27.6 dB window sat at -109.7 dBFS. The true closest joint approach is a
14.7 dB deficit. This is a caution about verifier prose as much as about
detectors: a headline margin assembled from two windows that never
coexisted still reads like a measurement.

What this does NOT bound: microphone self-noise and narrow spurs (the
Pixel's own 21645/22500/17122 Hz peaks, -90 to -115 dBFS, are the live
risk, and they are a device property, see DEVICE-P0.md), and the -95
dBFS placeholder's correctness as a threshold. Programme content is
bounded; the capture chain is not. That is the P0 sweep.

## MarkDetector: gate 4.0 is inside real-programme territory

Solo runs are vacuous — every file is shorter than the 32768 ms envelope
history, so `analyse()` never fires. A detector that structurally cannot
evaluate is not "clean"; 0 alarms there is a definitional zero, and any
clip under 32.8 s gives the same fake zero today.

On a continuous stream (five cyclic rotations, ~110 s each, 585
band-evaluations): **1 false alarm.** frieren OST, NAES2-class 4.5-6.3
kHz band, t=34.0 s, z=6.07 with rise 0.0607 over the MIN_ABS_RISE 0.05
clamp. The follow-up reading at t=36.0 s, z=4.97, was blocked from a
second alarm only by the absolute-rise clamp (0.0497 vs 0.05). Two MP3
speech files reach z 3.7-3.8 in the same band.

Global band maxima: NAES2 6.07, NAES6 1.50, CBET 0.36. The gate comment
cites a surrogate max of 2.23 (white-noise background). Real programme
in the NAES2 band reaches at least 6.1 on this corpus. The gate is not
surrogate-safe, and the two bands below it have not yet earned anything
either — same corpus says "quiet so far", not "safe".

Until the gate is recalibrated against a real corpus, any NAES2-class
`PERIODIC MARK` alarm should be read as z>=4 content coincidence at
least as often as a watermark.

## Rules this run leaves behind

- A false-alarm study must check that the detector actually evaluated
  during the clips it was fed; report z-readings alongside alarms.
- Never state a margin from statistics pooled across different windows;
  state the closest window that was simultaneously close on every term.
- The shipped surrogate bound (2.23) understates the real-programme
  tail by roughly 4 dB in the NAES2 band. Synthetic backgrounds are for
  arithmetic checks, never for gate placement.
