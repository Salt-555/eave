# Measured results

Everything here is reproducible with:

```bash
.venv/bin/python phy/eave_phy.py 1234     # D1 (retired), D2, D3, D4
.venv/bin/python phy/d1b.py               # D1b, the current near-ultrasonic detector
.venv/bin/python phy/mutation_check.py    # proves the isolation test still fails on
                                          # the filter bug it was written for
```

Needs numpy; the codec tests need ffmpeg. CI runs the invariant suite in `phy/`, not
these sweeps, because sweeps print tables and tests assert properties.

**What these numbers describe is the detectors, not a product.** Every figure is a
synthetic broadcast-like surrogate, not a loudspeaker, a microphone, or a room. The
Android probe in `../android` exists to replace that sentence with a measurement, and
until it has run, no number here is a claim about the physical world.

## d1b.txt — current, on the corrected instrument

Generated after the filter fix, and internally consistent in ways the earlier tables
were not: measured tone level equals the requested level to 0.1 dB at six levels,
contrast moves 12.0 dB per 12.0 dB of noise-floor knob, the tracked bin lands on the
injected frequency at all five frequencies tried, and the false-positive rate is
0.000 at every gate from 12 dB to 32 dB. Detection held at −52 dBFS tones against a
−48 dBFS band floor, which is the harshest cell in the table.

Two things to read out of the codec table specifically. The cliff for a 19 kHz tone is
between 96 kbps and 64 kbps AAC, not where the folklore puts it, and at 64 kbps the
contrast *still reads 54.7 dB* on a band whose absolute level has fallen to −107 dBFS.
That cell is the concrete case for requiring both statistics: contrast alone reports a
loudspeaker that is not there.

## Retired figures

Three numbers that appeared in earlier drafts of the README are withdrawn, because an
independent reimplementation reproduced the arithmetic and then disproved the
explanation attached to it:

- MAD of 8.56 dB versus 2.71 dB — reproduced to 0.13 dB on the old instrument, and
  flat at 0.07 dB on the corrected one. It measured filter leakage, not quietness.
- A learned gate at "+29 dB" — an internal per-bin convention 52.1 dB above true dBFS.
- Every "HF floor" row in any table produced before the filter fix. That knob moved
  its own band by 0.49 dB across a 24 dB change, so those rows described one room, not
  four.

## Not results

D3, the ATSC A/334 VP1 receiver, does not decode and is checked into the test suite as
`xfail` with its suspected cause. Its negative control did reproduce: unmarked clips
never produced a header match within tolerance. Presence of a mark is not something
D3 can currently report either way.
