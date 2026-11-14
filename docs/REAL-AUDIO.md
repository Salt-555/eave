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
cited a surrogate max of 2.23 (white-noise background). Real programme
in the NAES2 band reaches at least 6.1 on this corpus. The gate is not
surrogate-safe, and the two bands below it have not yet earned anything
either — same corpus says "quiet so far", not "safe".

(The setting this section describes, 4.0/0.05, was replaced by the
calibration below the same day. Read this section as the finding, not the
current configuration.)

## Gate recalibration (gatecal run, later the same day)

The finding above forced a calibration run: 32 real files (57.1 min; OBS
recordings, film/trailer audio, speech transcodes, bed tracks), fed through
the shipped detector as continuous streams in two arrangements (per-file
double-pass, and one mixed 57-min sequence x 4), 2.59 h of banded
evaluations per band, logging every z/rise pair. Harness, corpus manifest
and the per-evaluation CSVs: `scratch/eave/gatecal/` (zlogs/*.csv;
`analyze_fa.py` recomputes the whole gate grid offline).

Clean-programme tails per band (kept rows, n=4661 each): NAES2 p99 4.03 /
max 6.00 (allmind); NAES6 p99 5.38 / max 10.60 (obs18 double-pass 9.35,
wish); CBET p99 2.91 / max 5.19. These are real content, not filter
artifacts, and the two worst cases were dissected:

- unt.mp4 genuinely repeats its 4.5-6.3 kHz envelope every 1.94 s
  (whole-file envelope autocorr 0.49 at that lag) — 3.7% off this
  detector's 1.87 s target, comfortably inside its +/-15% acceptance
  window. Its kept-row max is a modest 4.90, but in first-pass rows
  (excluded from the table) the same rhythm drives z to 28.0.
- obs18's worst NAES6 row (z 9.35) has its neighbouring-lag null peaking at
  4.83 s, 30 ms from the 4.8 s target — the null distribution is
  contaminated by the same rhythm it is supposed to discount. Its strongest
  envelope repeat overall is 1.00 s; integer-second programme rhythms are
  exactly what a +/-15% window around 4.8 s swallows.

A +/-15% lag window is wide enough to catch genuine content periodicity by
construction, and this calibration papers over that rather than fixing it;
narrowing the window is measured territory only the loopback P2 phase can
buy.

What actually separates marks from the tail is the absolute-rise term, not
z. Corpus tail rows all sit at rise <= 0.21. Mark evaluations that are
visible at all sit at rise ~0.87 (saturated; measured on bedA at both
-34 dBFS and -40 dBFS marks, and the -40 mark scored identically to -34 —
the statistic saturates long before that range matters). The z-gate is
nearly inert: the corpus FA/hr at rise>=0.2 is 0.0 at every z gate from 4
to 8.

Adopted, per band (with measured FA/hr over 2.59 h):

| band | Z_GATE | MIN_ABS_RISE | corpus FA at adopted setting |
|---|---|---|---|
| NAES2 4.5-6.3 kHz @1.87 s | 8.0 | 0.2 | 0.00/h (was 18.5/h at 4.0/0.05) |
| NAES6 3.0-4.8 kHz @4.8 s  | 8.0 | 0.2 | 0.00/h (was 27.8/h) |
| CBET 1-3 kHz @4.8 s       | 4.0 | 0.2 | 0.00/h (was 1.5/h) |

Honest cost, measured in the same run: a -34 dBFS mark on `mssp` — a file
whose own 4.5-6.3 kHz content runs ~13 dB above the mark — is invisible at
every gate on the grid (rise 0.02). Marks buried under loud in-band
programme cannot be found by a periodicity statistic on the band envelope;
no gate setting buys them. And the six synthetic clips at 14 dB under
programme (old straddle: 3-4 of 6 alarming at gate 4.0) now all sit under
gate 8.0 (best-z 2.63-7.79) and alarm zero — the DetectorTest that used to
celebrate the straddle now pins its disappearance.

Bounds stated properly: 0/2.59 h supports a 95% upper bound of ~1.2 FA/h
at the adopted gates, not zero-as-in-never. The wide lag window is a known
open risk this calibration papers over rather than fixes.

## Rules this run leaves behind

- A false-alarm study must check that the detector actually evaluated
  during the clips it was fed; report z-readings alongside alarms.
- Never state a margin from statistics pooled across different windows;
  state the closest window that was simultaneously close on every term.
- The shipped surrogate bound (2.23) understates the real-programme
  tail by roughly 4 dB in the NAES2 band. Synthetic backgrounds are for
  arithmetic checks, never for gate placement.
