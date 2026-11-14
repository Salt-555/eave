"""
Regression tests for the Eave physical-layer prototype.

These assert *invariants*, not measurement results. A number in the README is a
claim about a particular synthetic scenario; a number here is a property the code
must keep having. Anything still under verification is marked xfail with the reason
in the test, rather than deleted or quietly dropped.

Run: pytest -q  (needs numpy; the codec test needs ffmpeg and skips without it)
"""

import shutil
import numpy as np
import pytest

from eave_phy import (FS, MFSK, VP1, add_white, bandpass, embed_ultrasonic_tone,
                      embed_vp1, program_surrogate, silverpush_valid, vp1_acquire,
                      aac_roundtrip, read_raw)

CLIP = 2.0                       # seconds; keep small, this box OOMs on big arrays
SILVERPUSH = lambda: MFSK(18000, 20000, 26, 40.0, name="silverpush")
LISNR = lambda: MFSK(18500, 19500, 2, 100.0, name="lisnr")


def sine_amplitude_dbfs(x, freq, fs=FS):
    """Amplitude of a bin-centred sine, stated against a full-scale sine (0 dBFS).
    One second of audio puts every integer Hz exactly on a bin, so there is no
    leakage correction to argue about."""
    n = int(fs)
    k = int(round(freq * n / fs))
    X = np.fft.rfft(x[:n])
    return 20.0 * np.log10(2.0 * np.abs(X[k]) / n + 1e-12)


# ---------------------------------------------------------------- harness itself
# The generator is part of the instrument. If "embed at -40 dBFS" does not mean
# -40 dBFS, every level sweep downstream is meaningless, and this is the failure
# mode that produced a bogus table once already.

def test_program_surrogate_is_deterministic_for_a_seed():
    a = program_surrogate(CLIP, seed=11)
    b = program_surrogate(CLIP, seed=11)
    assert np.array_equal(a, b)
    assert not np.array_equal(a, program_surrogate(CLIP, seed=12))


def test_surrogate_respects_the_hf_floor_it_is_given():
    """The 17-21 kHz floor is a knob the detector sweep turns; it has to work."""
    def floor(dbfs):
        x = program_surrogate(CLIP, seed=3, hf_floor_db=dbfs)
        seg = bandpass(x, 17000, 21000)
        return 10.0 * np.log10(np.mean(seg ** 2) + 1e-30)
    quiet, loud = floor(-84.0), floor(-60.0)
    assert loud - quiet == pytest.approx(24.0, abs=1.5)


@pytest.mark.parametrize("requested", [-40.0, -30.0, -20.0])
def test_injected_tone_matches_its_requested_level(requested):
    """'Embed at X dBFS' must mean X dBFS, or level sweeps measure the harness."""
    y = embed_ultrasonic_tone(np.zeros(FS), 19000.0, requested)
    assert sine_amplitude_dbfs(y, 19000.0) == pytest.approx(requested, abs=0.5)


def test_bandpass_rejects_well_outside_the_band():
    tone = np.sin(2 * np.pi * 1000.0 * np.arange(FS) / FS)
    stopped = bandpass(tone, 17000, 21000)
    assert 20.0 * np.log10(np.sqrt(np.mean(stopped ** 2)) + 1e-12) < -60.0


# ------------------------------------------------------------------------- D2 MFSK

def test_mfsk_round_trips_a_beacon_exactly():
    sp = SILVERPUSH()
    base = program_surrogate(CLIP)
    beacon = [0, 3, 11, 19, 25]
    y = sp.embed(base, beacon, start=int(0.4 * FS), level_dbfs=-24)
    dec, _ = sp.demod(y, start=int(0.4 * FS), nsym=5)
    assert dec == beacon
    assert silverpush_valid(dec)


def test_mfsk_rejects_symbols_without_a_clear_tonality_margin():
    """demod returns -1 when the winning tone is not clearly the winner. Without
    that reject path, 'no false beacons' is only a threshold artefact."""
    sp = SILVERPUSH()
    dec, conf = sp.demod(program_surrogate(1.0, seed=77), start=0, nsym=25)
    assert dec.count(-1) > 0, "a clean clip produced no rejections at all"
    assert all(c == 0.0 for c, d in zip(conf, dec) if d == -1)


def test_silverpush_validation_rules_reject_structured_garbage():
    assert not silverpush_valid([1, 2, 3, 4, 5])        # no 'A'
    assert not silverpush_valid([0, 4, 4, 9, 17])       # repeat
    assert not silverpush_valid([0, 1, 2, 3, -1])       # a rejected symbol
    assert not silverpush_valid([0, 1, 2, 3])           # wrong length
    assert silverpush_valid([0, 7, 8, 12, 22])


@pytest.mark.parametrize("seed", range(30))
def test_clean_program_audio_yields_no_valid_beacon(seed):
    """Regression guard, not a false-positive-rate claim: 30 clips, one shape."""
    sp = SILVERPUSH()
    y = program_surrogate(1.0, seed=1000 + seed)
    dec, _ = sp.demod(y, start=0, nsym=5)
    assert not silverpush_valid(dec)


def test_lisnr_two_shape_round_trips():
    li = LISNR()
    seq = [1, 0, 1, 1, 0, 0, 1, 0]
    y = li.embed(program_surrogate(2.0), seq, start=int(0.5 * FS), level_dbfs=-24)
    dec, _ = li.demod(y, start=int(0.5 * FS), nsym=8)
    assert dec == seq


# ---------------------------------------------------------------------- D3 VP1

def vp1_header_errors(x):
    """Header bit-errors from a single acquisition attempt. 99 means no match."""
    score = vp1_acquire(x)["score"]
    assert isinstance(score, int)
    return score


@pytest.mark.xfail(reason="receiver does not decode: ~6 of 32 header bits wrong at "
                          "20 dB SNR, and std/inv mode selection flips with chirp "
                          "noise, which is not a physical relationship. Suspected "
                          "cause: T = 452.83 samples at 48 kHz rounded to an integer, "
                          "so the sampling grid drifts ~27 samples across a cell. "
                          "Open bug; no VP1 capability is claimed.",
                   strict=False)
def test_vp1_acquisition_recovers_a_marked_header():
    payload = np.concatenate([VP1["header"], np.zeros(127, int)]).astype(np.int8)
    y = embed_vp1(program_surrogate(3.0, seed=5), payload, sigma=0.05)
    y = y + add_white(20, len(y))
    assert vp1_header_errors(y) <= 1, "marked clip did not yield a clean header"


def test_vp1_acquisition_does_not_lock_onto_clean_audio():
    """The one VP1 result that did reproduce: unmarked clips never matched the
    header within tolerance. A no-false-acquisition property, so it belongs here."""
    assert all(vp1_header_errors(program_surrogate(3.0, seed=500 + k)) > 3
               for k in range(10))


# ----------------------------------------------------------------------- the codec

@pytest.mark.skipif(shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
                    reason="ffmpeg not available")
def test_low_bitrate_aac_destroys_an_ultrasonic_tone(tmp_path):
    """The physical reason ultrasonic ad tracking lost to fingerprinting. If this
    ever stops being true, the whole threat model needs re-reading."""
    marked = embed_ultrasonic_tone(
        program_surrogate(6.0, seed=42, hf_floor_db=-72.0), 19000.0, -22.0)
    w = tmp_path / "in.wav"
    from eave_phy import write_wav
    write_wav(str(w), marked)

    def level_17_21(raw):
        seg = bandpass(read_raw(raw), 17000, 21000)
        return 10.0 * np.log10(np.mean(seg ** 2) + 1e-30)

    src = 10.0 * np.log10(np.mean(bandpass(marked, 17000, 21000) ** 2) + 1e-30)
    kept, dead = [], []
    for br, bucket in (("128k", kept), ("32k", dead)):
        raw = str(tmp_path / f"{br}.raw")
        aac_roundtrip(str(w), raw, br)
        bucket.append(level_17_21(raw))
    assert kept[0] - src > -12.0, "128k should carry a hot 19 kHz tone"
    assert dead[0] - src < -25.0, "32k should have thrown the band away"
