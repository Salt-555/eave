#!/usr/bin/env python3
"""Mutation check: does the new isolation test actually fail on the broken filter?

A regression test is only worth what it would have caught. This re-runs the test's
own measurement against the previous bandpass, logistic skirts and all, and prints
both verdicts. If the old instrument passes this test, the test is decoration.
"""
import numpy as np
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, "/home/salt/CodingProjects/eave/phy")
from eave_phy import program_surrogate, logistic, FS   # noqa: E402
import eave_phy                                          # noqa: E402


def leaky_bandpass(x, lo, hi, fs=FS, edge=0.08):
    """The pre-fix filter, verbatim."""
    x = np.asarray(x, dtype=np.float64)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / fs)
    wlo = np.maximum(edge * lo, 1.0)
    whi = np.maximum(edge * hi, 1.0)
    mask = logistic((f - lo) / wlo) * (1.0 - logistic((f - hi) / whi))
    return np.fft.irfft(X * mask, n=len(x))


def spread_db(bp, dbfs):
    x = program_surrogate(8.0, seed=71, hf_floor_db=dbfs)
    seg = bp(x, 17000, 21000)
    W = FS // 2
    levels = np.array([10.0 * np.log10(np.mean(seg[s:s + W] ** 2) + 1e-30)
                       for s in range(0, len(seg) - W, W)])
    return float(1.4826 * np.median(np.abs(levels - np.median(levels))))


FLOORS = (-90.0, -72.0, -54.0)
print("Isolation test, same measurement, two instruments:")
for name, bp in (("old logistic skirts", leaky_bandpass),
                 ("new zero stopband  ", eave_phy.bandpass)):
    # program_surrogate calls bandpass() internally for its 14.5 kHz ceiling, so the
    # instrument under test has to be installed for generation as well as measuring.
    original = eave_phy.bandpass
    eave_phy.bandpass = bp
    try:
        s = np.array([spread_db(bp, f) for f in FLOORS])
    finally:
        eave_phy.bandpass = original
    verdict = "PASS" if (s.max() < 2.0 and s.max() - s.min() < 0.8) else "FAIL"
    print(f"  {name}  spread dB at {FLOORS} = "
          f"[{s[0]:5.2f} {s[1]:5.2f} {s[2]:5.2f}]  ->  {verdict}")
