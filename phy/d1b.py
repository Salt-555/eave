#!/usr/bin/env python3
"""
Eave D1b -- spectral peakiness with bin persistence, plus an absolute floor.

Two statistics, both chosen because they survive an instrument you do not fully
understand, which is what a phone microphone is until measured:

  contrast   the winning bin against the median of its own candidate band. Scale
             free, so no learned baseline and nothing that can drift with the room.
  level      that bin's amplitude in calibrated dBFS against a full-scale sine. A
             constant per device, to be measured by the P2 loopback sweep.

Both are needed. Contrast alone can read a strong line in a band the codec has
emptied: at 64 kbps AAC the contrast of a 19 kHz tone still measures 54.7 dB while
the absolute level of the band has fallen to -107 dBFS, which is a loudspeaker that
is not reproducing anything. Persistence is what separates a beacon from a
coincidence: a beacon holds the same bin across seconds, a noise peak does not.

History, corrected. This detector was originally sold as the cure for a learned
mean + k*sigma threshold that supposedly went deaf in a quiet room. That story was
wrong. An independent reimplementation reproduced the original figures to 0.13 dB and
then showed the "quiet" band had been filled by a measurement filter whose stopband
reached only -34 dB: the learned spread is flat at 0.07 dB once the stopband is truly
zero, and the learned gate it replaced detects everything it was said to miss. D1b is
retained on its actual merits, namely that neither statistic can be *set* by
contamination from a neighbouring band, not on the old justification. Its gate and
floor were tuned against the contaminated band and are pending a re-tune on the
corrected one, and then on hardware.
"""

import numpy as np
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from eave_phy import (program_surrogate, embed_ultrasonic_tone, aac_roundtrip,
                      read_raw, write_wav, FS, BEACON_LO, BEACON_HI)

WIN, HOP = 1.0, 0.25
GATE_DB = 20.0        # contrast over the band median
RUN = 4               # consecutive windows holding the same bin
FLOOR_DBFS = -95.0    # calibrated placeholder; P2 loopback measures the real value


def d1b_scan(x, fs=FS, win=WIN, hop=HOP, lo=BEACON_LO, hi=BEACON_HI):
    """
    Per window: best peak-to-band-median contrast in dB, which bin won it, and that
    bin's absolute level stated as an equivalent full-scale sine in dBFS.

    Contrast is measured against the median of the whole candidate band. No guard
    neighbourhood is needed: a beacon occupies one to three bins out of roughly two
    thousand, far too few to move a median, so the reference is already clean and
    the comparison costs O(n).
    """
    W, H = int(win * fs), int(hop * fs)
    f = np.fft.rfftfreq(W, 1.0 / fs)
    band = np.where((f >= lo) & (f <= hi))[0]
    taper = np.hanning(W)
    mw, cw = taper.mean(), np.mean(taper ** 2)
    contrast, bins, levels = [], [], []
    for s in range(0, len(x) - W, H):
        X = np.fft.rfft(x[s:s + W] * taper)
        P = np.abs(X) ** 2 / (fs * W * cw)
        Pb = P[band]
        c = 10.0 * np.log10(Pb / (np.median(Pb) + 1e-30))
        j = int(np.argmax(c))
        # a bin-centred sine of amplitude A gives X[k] = (A/2) * sum(taper)
        amp = 2.0 * np.abs(X[band[j]]) / (W * mw)
        contrast.append(c[j])
        bins.append(int(band[j]))
        levels.append(20.0 * np.log10(amp + 1e-12))
    return np.array(contrast), np.array(bins), np.array(levels)


def d1b_detect(x, gate_db=GATE_DB, run=RUN, floor_dbfs=FLOOR_DBFS, fs=FS):
    """Alarm when one bin holds >= gate_db contrast AND >= floor_dbFS across `run`
    consecutive windows. Returns alarm vector, contrast, bins, levels."""
    c, b, lv = d1b_scan(x, fs)
    if len(c) < run:
        return np.zeros(max(len(c), 1), bool), c, b, lv
    hot = (c >= gate_db) & (lv >= floor_dbfs)
    alarms = np.zeros(len(c), bool)
    for i in range(run - 1, len(c)):
        if hot[i - run + 1:i + 1].all() and (b[i - run + 1:i + 1].max()
                                             - b[i - run + 1:i + 1].min()) <= 1:
            alarms[i] = True
    return alarms, c, b, lv


def rule(t):
    print("\n" + "=" * 74)
    print(t)
    print("=" * 74)


if __name__ == "__main__":
    rule("D1b  scale-invariant peakiness + bin persistence + absolute floor")
    print(f"  gate: one bin >= {GATE_DB:.0f} dB over its own band median and >="
          f" {FLOOR_DBFS:.0f} dBFS, held {RUN} windows")

    print(f"\n  {'HF floor':>9} {'tone':>9} {'TPR':>6} {'FPR':>6} "
          f"{'contrast dB':>12} {'level dBFS':>11}")
    for hf in (-84.0, -72.0, -60.0, -48.0):
        for lv_in in (-52, -46, -40, -34, -28, -22):
            hit, cc, ll = 0, [], []
            for k in range(12):
                y = embed_ultrasonic_tone(
                    program_surrogate(9.0, seed=200 + k, hf_floor_db=hf),
                    19000.0, float(lv_in))
                a, c, _, l = d1b_detect(y)
                hit += int(a.any()); cc.append(np.median(c)); ll.append(np.median(l))
            fp = 0
            for k in range(24):
                a, _, _, _ = d1b_detect(
                    program_surrogate(9.0, seed=1500 + k, hf_floor_db=hf))
                fp += int(a.any())
            print(f"  {hf:>7.0f} dB {lv_in:>6} dBFS {hit / 12:>6.2f} {fp / 24:>6.2f}"
                  f" {np.mean(cc):>12.1f} {np.mean(ll):>11.1f}")

    rule("D1b  FPR against gate, no signal present (24 clean clips x 4 floors)")
    for g in (12.0, 16.0, 20.0, 26.0, 32.0):
        fp, n = 0, 0
        for hf in (-84.0, -72.0, -60.0, -48.0):
            for k in range(24):
                a, _, _, _ = d1b_detect(
                    program_surrogate(9.0, seed=1500 + k, hf_floor_db=hf), gate_db=g)
                fp += int(a.any()); n += 1
        print(f"  gate {g:>4.0f} dB -> {fp}/{n} clips alarmed ({fp / n:.3f})")

    rule("D1b vs the codec hop: why ultrasonic tracking died")
    tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tmp_aac")
    os.makedirs(tmp, exist_ok=True)
    marked = embed_ultrasonic_tone(
        program_surrogate(11.0, seed=42, hf_floor_db=-72.0), 19000.0, -22.0)
    w = os.path.join(tmp, "a.wav")
    write_wav(w, marked)
    a0, c0, _, l0 = d1b_detect(marked)
    print(f"  {'path':>9} {'contrast':>10} {'level':>8} {'alarm':>7}")
    print(f"  {'source':>9} {np.median(c0):>10.1f} {np.median(l0):>8.1f} "
          f"{str(bool(a0.any())):>7}")
    for br in ("256k", "128k", "96k", "64k", "32k"):
        raw = os.path.join(tmp, f"x{br}.raw")
        aac_roundtrip(w, raw, br)
        det, c, _, l = d1b_detect(read_raw(raw))
        print(f"  {br:>9} {np.median(c):>10.1f} {np.median(l):>8.1f} "
              f"{str(bool(det.any())):>7}")
    print("  Read the 64k row carefully: contrast still reads 54.7 dB while the band's")
    print("  absolute level has fallen to -107 dBFS. Contrast alone reports a beacon")
    print("  coming from a loudspeaker that is not reproducing it. The absolute floor")
    print("  is what turns that alarm off, which is why both terms are required.")

    rule("D1b  19 kHz tone surviving 128 kbps AAC, by input level")
    for lv_in in (-40, -34, -28, -22, -16, -10):
        w = os.path.join(tmp, f"l{lv_in}.wav")
        write_wav(w, embed_ultrasonic_tone(
            program_surrogate(11.0, seed=42, hf_floor_db=-72.0), 19000.0, float(lv_in)))
        raw = os.path.join(tmp, f"l{lv_in}.raw")
        aac_roundtrip(w, raw, "128k")
        det, c, _, l = d1b_detect(read_raw(raw))
        print(f"  input {lv_in:>4} dBFS -> contrast {np.median(c):6.1f} dB,"
              f"  level {np.median(l):7.1f} dBFS,  alarm {bool(det.any())}")

    rule("D1b  frequency sweep: tuned to 19 kHz, or to the band?")
    for fc in (17200, 18000, 19000, 20000, 20800):
        y = embed_ultrasonic_tone(
            program_surrogate(9.0, seed=77, hf_floor_db=-72.0), float(fc), -34.0)
        det, c, b, l = d1b_detect(y)
        print(f"  tone {fc:>6} Hz -> contrast {np.median(c):5.1f} dB, tracked bin "
              f"{int(np.median(b)):>6} Hz, alarm {bool(det.any())}")
