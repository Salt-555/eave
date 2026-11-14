#!/usr/bin/env python3
"""
Eave PHY prototype -- offline validation of the acoustic detectors that the
Android counter-surveillance app would run.

Detectors implemented:
  D1  generic near-ultrasonic presence + temporal-constancy (catches ANY beacon
      that sits above the content band; the constancy test is the discriminator)
  D2  M-FSK beacon demod in SilverPush form (26 tones, 18-20 kHz, Goertzel bank,
      5-letter beacon, "no repeat + must contain A" validation)
      and in Lisnr form (2 tones, 18.5-19.5 kHz)
  D3  ATSC A/334 VP1 spec-faithful receiver: 2.5-5 kHz, autocorrelation
      difference with tau = 3 ms, symbol T = 1/106 s, 159-bit cell,
      32-bit header acquisition, both standard and inverse signaling modes
  D4  Nielsen-band occupancy/probing detector: CBET 1-3 kHz,
      NAES6 2.99-4.8 kHz, NAES2 4.5-6.3 kHz + TDM period fingerprints
      (1.87 s NAES2, 4.8 s NAES6/CBET). Presence + timing, NOT a SID decoder.

Signal model: a "program surrogate" that behaves like broadcast TV audio --
harmonic stack + shaped noise + syllabic AM + transients, hard-rolled-off above
15 kHz the way real distribution audio is.

Nothing here needs scipy; filters are FFT-domain, tone detectors are Goertzel.
"""

import numpy as np
import subprocess, os, sys, tempfile

FS = 48000
RNG = np.random.default_rng(20261113)

# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------

def logistic(x):
    return 1.0 / (1.0 + np.exp(-np.clip(x, -60, 60)))


def bandpass(x, lo, hi, fs=FS, edge=0.02):
    """
    FFT-domain bandpass: raised-cosine skirts and an EXACT zero stopband.

    The earlier version shaped its skirts with a logistic, which only reaches
    about -34 dB a few kilohertz past the corner. That is not a measurement
    filter: loud programme audio leaked into the 17-21 kHz band roughly 10 dB
    above the synthetic noise floor a sweep was supposed to be setting, so the
    floor knob barely moved the band it claimed to control. Anything measuring a
    quiet band must not leak a loud band into it, so the stopband is now zero by
    construction rather than merely small.
    """
    x = np.asarray(x, dtype=np.float64)
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1.0 / fs)
    wlo = max(edge * lo, 1.0)
    whi = max(edge * hi, 1.0)
    mask = np.zeros_like(f)
    mask[(f >= lo) & (f <= hi)] = 1.0
    tl = (f > lo - wlo) & (f < lo)
    mask[tl] = 0.5 - 0.5 * np.cos(np.pi * (f[tl] - (lo - wlo)) / wlo)
    th = (f > hi) & (f < hi + whi)
    mask[th] = 0.5 - 0.5 * np.cos(np.pi * ((hi + whi) - f[th]) / whi)
    return np.fft.irfft(X * mask, n=len(x))


def band_energy(x, lo, hi, fs=FS):
    return float(np.sum(bandpass(x, lo, hi, fs) ** 2))


def goertzel_mag(x, freq, fs=FS):
    """Single-bin DFT magnitude (the algorithm SilverPush's SDK used)."""
    n = len(x)
    k = freq * n / fs
    wr = np.cos(2 * np.pi * k / n)
    coeff = 2 * wr
    s1 = s2 = 0.0
    for s in x:                      # scalar loop; fine for prototype sizes
        s0 = s + coeff * s1 - s2
        s2, s1 = s1, s0
    real = s1 - s2 * wr
    imag = s2 * np.sqrt(1 - wr * wr) if abs(wr) < 1 else 0.0
    return float(np.hypot(real, imag))


def goertzel_bank_fast(x, freqs, fs=FS):
    """Vectorised equivalent: correlate against the requested exponentials."""
    n = len(x)
    t = np.arange(n)
    out = np.empty(len(freqs))
    for i, f in enumerate(freqs):
        e = np.exp(-2j * np.pi * f * t / fs)
        out[i] = abs(np.dot(x, e)) / n
    return out


def db(x):
    return 20.0 * np.log10(max(x, 1e-12))


def add_noise_to_snr(x, noise, target_snr_db):
    """Scale `noise` so that x is `target_snr_db` above it (x treated as signal)."""
    ps = float(np.sum(x ** 2)) / len(x)
    pn = float(np.sum(noise ** 2)) / len(noise)
    scale = np.sqrt(ps / pn / (10 ** (target_snr_db / 10.0)))
    return noise * scale


# ----------------------------------------------------------------------------
# program surrogate -- what the detector must NOT trip on
# ----------------------------------------------------------------------------

def program_surrogate(seconds=8.0, fs=FS, seed=None, hf_floor_db=-72.0):
    """
    Broadcast-like audio: harmonic stack, shaped noise, syllabic AM, transients,
    rolled off above 15 kHz the way real distribution audio is. `hf_floor_db` is
    the RMS of broadband noise left in 17-21 kHz, standing in for mic self-noise
    and room HF -- without it the beacon band is empty and detection looks free.
    """
    rng = np.random.default_rng(seed if seed is not None else RNG.integers(1 << 30))
    n = int(seconds * fs)
    t = np.arange(n) / fs

    # voiced harmonic stack with a moving fundamental
    f0 = 110 + 25 * np.sin(2 * np.pi * 0.21 * t) + 8 * np.sin(2 * np.pi * 1.7 * t)
    ph = np.cumsum(2 * np.pi * f0 / fs)
    x = np.zeros(n)
    for h in range(1, 60):
        amp = (1.0 / h ** 1.15) * (1.0 + 0.35 * rng.standard_normal())
        x += amp * np.sin(h * ph * (1.0 + 0.002 * rng.standard_normal()))

    # shaped noise bed (pink-ish), plus hiss that dies above 15 kHz like real audio
    N = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1.0 / fs)
    shape = 1.0 / np.sqrt(np.maximum(f, 30.0) / 30.0 + 1e-6)
    shape *= logistic((7000 - f) / 1200) * 0.7 + 0.3
    shape *= (1.0 - logistic((f - 14000) / 500))          # hard codec-like ceiling
    x += np.fft.irfft(N * shape, n=n) * 0.22

    # syllabic amplitude modulation + sentence pauses
    syl = 0.55 + 0.45 * (0.5 + 0.5 * np.sin(2 * np.pi * 3.4 * t + np.sin(t * 0.9) * 2))
    sent = ((np.sin(2 * np.pi * 0.22 * t) > -0.35).astype(float))
    env = syl * sent
    env = np.convolve(env, np.ones(int(0.008 * fs)) / (0.008 * fs), mode="same")
    x *= env

    # transients (percussion / scene changes)
    for _ in range(14):
        p = int(rng.integers(0, n - 1000))
        x[p:p + 900] += rng.standard_normal(900) * np.exp(
            -np.arange(900) / 140) * 0.5

    x = bandpass(x, 60, 14500)                            # broadcast ceiling

    # high-frequency floor: mic self-noise + room HF, flat across 17-21 kHz
    if hf_floor_db is not None:
        Nf = np.fft.rfft(rng.standard_normal(n))
        fl = np.fft.rfftfreq(n, 1.0 / fs)
        m = (fl >= 16500) & (fl <= 23000)
        hf = np.fft.irfft(Nf * m, n=n)
        hf /= (np.sqrt(np.mean(hf ** 2)) + 1e-15)
        x += hf * 10 ** (hf_floor_db / 20.0)

    x /= np.max(np.abs(x)) + 1e-12
    return x * 0.85


def add_white(snr_db, n, fs=FS):
    noise = RNG.standard_normal(n)
    noise /= np.sqrt(np.mean(noise ** 2))
    ref = np.ones(n) * 0.3
    return add_noise_to_snr(ref, noise, snr_db)


# ----------------------------------------------------------------------------
# D1  generic near-ultrasonic presence + constancy
# ----------------------------------------------------------------------------

BEACON_LO, BEACON_HI = 17000.0, 21000.0
REF_LO, REF_HI = 13000.0, 16000.0
# The content-change test must run on the band where programme audio actually
# lives. Measuring it at 13-16 kHz degenerates on band-limited material: the band
# is nearly empty, its texture is near-stationary, and the correlation gate sits
# right on the threshold so tiny level changes flip it.
CONTENT_LO, CONTENT_HI = 300.0, 12000.0


def d1_scores(x, fs=FS, win=1.0, hop=0.25):
    """
    Per-window: dB ratio of beacon band (17-21k) to reference band (13-16k),
    plus the absolute dBFS level of the beacon band. Both are needed; a ratio
    alone false-positives when a codec collapses the reference band.
    """
    n = len(x)
    W, H = int(win * fs), int(hop * fs)
    f = np.fft.rfftfreq(W, 1.0 / fs)
    in_beacon = (f >= BEACON_LO) & (f <= BEACON_HI)
    mask_ref = (f >= REF_LO) & (f <= REF_HI)
    mask_cont = (f >= CONTENT_LO) & (f <= CONTENT_HI)
    taper = np.hanning(W)
    nb = max(int(in_beacon.sum()), 1)
    ratio, level, cont = [], [], []
    for s in range(0, n - W, H):
        X = np.fft.rfft(x[s:s + W] * taper)
        P = np.abs(X) ** 2
        b = float(P[in_beacon].sum()) + 1e-20
        r = float(P[mask_ref].sum()) + 1e-20
        ratio.append(10.0 * np.log10(b / r))
        level.append(10.0 * np.log10(b / nb / 0.25 + 1e-30))
        cont.append(np.fft.irfft(X * mask_cont, n=W))
    return np.array(ratio), np.array(level), cont


def _robust(v, floor=1.0):
    med = float(np.median(v))
    mad = float(np.median(np.abs(v - med))) * 1.4826
    return med, max(mad, floor)


def learn_ambient(clips, fs=FS, win=1.0, hop=0.25):
    """
    Long-run ambient baseline. This is what a per-clip baseline gets wrong: a
    carrier constant over the whole clip equals its own median, so the reference
    must be ambient learned over a far longer span (TV off, other programmes,
    previous days) than the alarm window.
    """
    rr, ll = [], []
    for c in clips:
        a, b, _ = d1_scores(c, fs, win, hop)
        rr.append(a); ll.append(b)
    rm, rs = _robust(np.concatenate(rr))
    lm, ls = _robust(np.concatenate(ll), floor=2.0)
    return dict(ratio_med=rm, ratio_sig=rs, level_med=lm, level_sig=ls)


def d1_detect(x, baseline, fs=FS, k=6.0, run=4, content_corr_max=0.85,
              win=1.0, hop=0.25):
    """
    Alarm requires all three conditions:
      1. the 17-21 kHz / 13-16 kHz ratio is elevated by k*sigma over the LEARNED
         ambient ratio baseline
      2. the absolute 17-21 kHz level is also elevated by k*sigma over ambient --
         this is what stops a codec that gutted the reference band from reading
         as a beacon
      3. it holds for `run` windows while the audible content is not identical,
         because a beacon stays put while programme audio moves
    """
    r, lvl, ref = d1_scores(x, fs, win, hop)
    if len(r) < run + 1:
        return np.zeros(max(len(r), 1), dtype=bool), r, lvl
    hot = ((r > baseline["ratio_med"] + k * baseline["ratio_sig"]) &
           (lvl > baseline["level_med"] + k * baseline["level_sig"]))
    alarms = np.zeros(len(r), dtype=bool)
    for i in range(run - 1, len(r)):
        if not np.all(hot[i - run + 1:i + 1]):
            continue
        a, b = ref[i - run + 1], ref[i]
        c = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))
        if c < content_corr_max:
            alarms[i] = True
    return alarms, r, lvl


def embed_ultrasonic_tone(x, freq, level_dbfs, fs=FS, fade_ms=10):
    n = len(x)
    t = np.arange(n) / fs
    tone = np.sin(2 * np.pi * freq * t)
    peak = 10 ** (level_dbfs / 20.0)
    f = int(fade_ms * fs / 1000)
    ramp = np.ones(n)
    ramp[:f] = np.linspace(0, 1, f)
    ramp[-f:] = np.linspace(1, 0, f)
    return x + tone * peak * ramp


# ----------------------------------------------------------------------------
# D2  M-FSK beacon demod (SilverPush / Lisnr)
# ----------------------------------------------------------------------------

class MFSK:
    def __init__(self, lo, hi, m, sym_ms, fs=FS, name="mfsk"):
        self.f = lo + (np.arange(m) + 0.5) * (hi - lo) / m
        self.m, self.name = m, name
        self.N = int(sym_ms * fs / 1000)
        self.fs = fs

    def embed(self, x, symbols, start=0, level_dbfs=-26):
        """symbols: list of alphabet indices. Returns modified copy."""
        y = x.copy()
        peak = 10 ** (level_dbfs / 20.0)
        for i, s in enumerate(symbols):
            s0 = start + i * self.N
            s1 = min(s0 + self.N, len(y))
            if s0 >= len(y):
                break
            seg = np.arange(s1 - s0)
            # continuous phase within the symbol, cos ramp at the edges
            env = 0.5 - 0.5 * np.cos(2 * np.pi * (seg + 0.5) / self.N)
            tt = seg / self.fs
            y[s0:s1] += peak * env * np.sin(2 * np.pi * self.f[s] * tt + 0.3 * s)
        return y

    def demod(self, x, start=0, nsym=None, gate=2.0):
        """Returns (symbols, confidences); -1 == reject."""
        n = len(x)
        nsym = nsym or ((n - start) // self.N)
        out, conf = [], []
        for i in range(nsym):
            s0 = start + i * self.N
            if s0 + self.N > n:
                break
            seg = x[s0:s0 + self.N] * np.hanning(self.N)
            mag = goertzel_bank_fast(seg, self.f, self.fs)
            order = np.argsort(mag)[::-1]
            best, second = mag[order[0]], mag[order[1]]
            if second > 0 and best / second >= gate:
                out.append(int(order[0]))
                conf.append(float(best / (np.median(mag) + 1e-12)))
            else:
                out.append(-1)
                conf.append(0.0)
        return out, conf


def silverpush_valid(sym, alphabet=26):
    """The two error-detection rules found in the SDK: no repeats, 'A' present."""
    if -1 in sym or len(sym) != 5:
        return False
    if len(set(sym)) != len(sym):
        return False
    return 0 in sym                        # 'A' is index 0


# ----------------------------------------------------------------------------
# D3  ATSC A/334 VP1 receiver (spec-faithful detector)
# ----------------------------------------------------------------------------

VP1 = dict(
    band=(2500.0, 5000.0),
    tau_ms=3.0,
    T=1.0 / 106.0,
    header=np.array([int(b) for b in "10101110000010101011100111100100"]),
    cell_bits=159,
)


def vp1_rd(s_prime, offsets, fs=FS, tau_ms=3.0, T=1.0 / 106.0):
    """
    Autocorrelation difference R_d per A/334 sec 5.1.1:
        R_d(t) = sum_u s'[u] s'[u+tau]  -  sum_u s'[u] s'[u-tau]
    evaluated in a window of length T centred at t.
    `offsets` are sample positions; returns len(offsets) x nsym.
    """
    tau = int(round(tau_ms * 1e-3 * fs))
    W = int(round(T * fs))
    n = len(s_prime)
    sp = np.concatenate([np.zeros(tau + W), s_prime, np.zeros(tau + W)])
    shift = tau + W
    out = np.empty((len(offsets), max(1, (n - offsets[0]) // W)))
    for oi, o in enumerate(offsets):
        col = 0
        p = shift + o
        while p + W + tau < len(sp) and col < out.shape[1]:
            w = sp[p:p + W]
            rp = float(np.dot(w, sp[p + tau:p + tau + W]))
            rm = float(np.dot(w, sp[p - tau:p - tau + W]))
            out[oi, col] = rp - rm
            p += W
            col += 1
    return out[:, :col] if col else out


def vp1_bits(rd, mode="std"):
    """Table 5.1. std: R_d>=0 -> 1. inverse: R_d>=0 -> 0."""
    b = (rd >= 0).astype(np.int8)
    if mode == "inv":
        b = 1 - b
    return b


def vp1_find_header(bits, header):
    """Return (index, n_errors) of best header match, or (-1, -1)."""
    h = header.astype(np.int8)
    L = len(h)
    if len(bits) < L:
        return -1, -1
    # sliding Hamming distance via convolution
    win = np.lib.stride_tricks.sliding_window_view(bits, L)
    d = np.count_nonzero(win != h, axis=1)
    i = int(np.argmin(d))
    return i, int(d[i])


def embed_vp1(x, payload_bits, fs=FS, sigma=0.25, start_frac=0.15):
    """
    Embedder is a RECONSTRUCTION from the A/334 equations (the standard fixes the
    detector maths and the band, and leaves embedder strength content-dependent).
    Per symbol interval we add a band-limited copy of the content delayed by tau,
    with sign set by the bit. Sign convention is measured, not assumed.
    """
    lo, hi = VP1["band"]
    tau = int(round(VP1["tau_ms"] * 1e-3 * fs))
    T = int(round(VP1["T"] * fs))
    n = len(x)
    s_bp = bandpass(x, lo, hi, fs)
    scale = sigma * np.sqrt(np.mean(s_bp ** 2)) / (np.sqrt(np.mean(s_bp ** 2)) + 1e-12)
    y = x.copy()
    start = int(start_frac * fs)
    for i, bit in enumerate(payload_bits):
        s0 = start + i * T
        s1 = min(s0 + T, n)
        if s0 + tau >= n:
            break
        sign = 1.0 if bit else -1.0
        delayed = np.zeros(s1 - s0)
        src_lo = max(0, s0 - tau)
        seg = s_bp[src_lo:src_lo + (s1 - s0)]
        delayed[:len(seg)] = seg[:len(delayed)]
        env = np.hanning(s1 - s0)
        y[s0:s1] += sign * scale * 0.9 * env * delayed
    return y


def vp1_acquire(x, fs=FS, tol=3, ovs=6, sign_table=None):
    """Full acquisition: bandpass, R_d on an offset grid, header search both modes."""
    lo, hi = VP1["band"]
    sp = bandpass(x, lo, hi, fs)
    T = VP1["T"] * fs
    offsets = np.arange(0, int(T), max(1, int(T / ovs)))
    best = dict(score=99, oi=-1, mode=None, idx=-1, bits=None)
    for mode in ("std", "inv"):
        rd_all = vp1_rd(sp, offsets, fs, VP1["tau_ms"], VP1["T"])
        for oi in range(len(offsets)):
            bits = vp1_bits(rd_all[oi], mode)
            idx, err = vp1_find_header(bits, VP1["header"])
            if err < best["score"]:
                best.update(score=err, oi=int(offsets[oi]), mode=mode, idx=idx,
                            bits=bits, rd=rd_all[oi])
            if best["score"] <= tol and best["score"] == 0:
                break
        if best["score"] == 0:
            break
    return best


# ----------------------------------------------------------------------------
# D4  Nielsen-band probing detector (presence + TDM periodicity, not a decoder)
# ----------------------------------------------------------------------------

NIELSEN_BANDS = {"CBET ~1-3k": (1000, 3000), "NAES6 2.99-4.8k": (2990, 4800),
                 "NAES2 4.5-6.3k": (4500, 6300)}
TDM_PERIODS = {"NAES2 4.5-6.3k": 1.87, "NAES6 2.99-4.8k": 4.8, "CBET ~1-3k": 4.8}


def d4_scan(x, fs=FS, win=0.5):
    out = {}
    for name, (lo, hi) in NIELSEN_BANDS.items():
        seg = bandpass(x, lo, hi, fs)
        W = int(win * fs)
        m = len(seg) // W
        if m < 4:
            continue
        e = (seg[:m * W].reshape(m, W) ** 2).mean(axis=1) + 1e-12
        peakiness = float(np.median(e / (np.roll(e, 1) + 1e-12)))
        cv = float(np.std(e) / np.mean(e))
        env = e - np.median(e)
        per = TDM_PERIODS[name]
        lag = int(per / win)
        if len(env) > 2 * lag:
            a = env[:-lag] - np.mean(env[:-lag]); b = env[lag:] - np.mean(env[lag:])
            corr = float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))
        else:
            corr = 0.0
        out[name] = dict(cv=cv, period_corr=corr)
    return out


# ----------------------------------------------------------------------------
# codec survival
# ----------------------------------------------------------------------------

def aac_roundtrip(wav_in, raw_out, bitrate):
    """AAC-encode, then decode back to raw mono f32 at FS (what the app would see)."""
    tmpdir = os.path.dirname(raw_out)
    m4a = os.path.join(tmpdir, "hop.m4a")
    enc = subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", wav_in,
         "-c:a", "aac", "-b:a", str(bitrate), m4a],
        capture_output=True, text=True)
    if enc.returncode != 0:
        raise RuntimeError("encode: " + enc.stderr.strip())
    dec = subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", m4a,
         "-af", f"aresample={FS}", "-ac", "1", "-c:a", "pcm_f32le",
         "-f", "f32le", raw_out],
        capture_output=True, text=True)
    if dec.returncode != 0:
        raise RuntimeError("decode: " + dec.stderr.strip())


def read_raw(path):
    return np.fromfile(path, dtype="<f4").astype(np.float64)


def write_wav(path, x, fs=FS):
    import wave
    a = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(fs)
        w.writeframes(a.tobytes())


# ----------------------------------------------------------------------------
# experiments
# ----------------------------------------------------------------------------

def rule(label):
    print("\n" + "=" * 74)
    print(label)
    print("=" * 74)


def exp_D1():
    rule("D1  generic near-ultrasonic presence + temporal constancy")
    print("  alarm = ratio AND absolute level >6 sigma over LEARNED ambient,")
    print("          sustained 4 s, while the 13-16 kHz content is not static")

    print(f"\n  {'HF noise floor':>15} {'tone level':>11} {'TPR':>6} {'FPR':>6}")
    for hf in (-78.0, -66.0, -54.0):
        ambient = [program_surrogate(9.0, seed=900 + k, hf_floor_db=hf)
                   for k in range(8)]
        base = learn_ambient(ambient)
        for lv in (-40, -34, -28, -22):
            tp = 0
            for k in range(12):
                y = embed_ultrasonic_tone(
                    program_surrogate(9.0, seed=200 + k, hf_floor_db=hf),
                    19000.0, float(lv))
                a, _, _ = d1_detect(y, base)
                tp += int(a.any())
            fp = 0
            for k in range(24):
                a, _, _ = d1_detect(
                    program_surrogate(9.0, seed=1500 + k, hf_floor_db=hf), base)
                fp += int(a.any())
            print(f"  {hf:>12.0f} dB {lv:>8} dBFS {tp / 12:>6.2f} {fp / 24:>6.2f}")

    print("\n  --- content-change gate diagnostic (programme band 0.3-12 kHz) ---")
    cs = []
    for k in range(12):
        y = program_surrogate(9.0, seed=1500 + k)
        _, _, cw = d1_scores(y)
        a, b = cw[0], cw[min(16, len(cw) - 1)]
        cs.append(float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12)))
    print(f"  programme correlation 4 s apart: median {np.median(cs):.3f}, "
          f"max {max(cs):.3f}, gate 0.850")
    _, _, cw = d1_scores(program_surrogate(9.0, seed=1500))
    a, b = cw[0], cw[1]
    print(f"  programme correlation 0.25 s apart: "
          f"{float(np.dot(a,b)/(np.linalg.norm(a)*np.linalg.norm(b))):.3f}")

    ambient = learn_ambient([program_surrogate(9.0, seed=900 + k)
                             for k in range(8)])
    print("\n  --- self-referential baseline, the trap I built first ---")
    tp = 0
    for k in range(12):
        y = embed_ultrasonic_tone(program_surrogate(9.0, seed=200 + k), 19000.0, -14.0)
        r_self = d1_scores(y)[0]
        med, sig = _robust(r_self, floor=1.0)
        selfish = dict(ratio_med=med, ratio_sig=sig,
                       level_med=ambient["level_med"], level_sig=1e9)
        a, _, _ = d1_detect(y, selfish)
        tp += int(a.any())
    print(f"  a 19 kHz tone at -14 dBFS trips {tp}/12 when the ratio baseline is")
    print(f"  the same clip's own median. A constant carrier IS its own median.")

    print("\n  --- does it survive an AAC hop? (the failure that killed 18-20 kHz) ---")
    tmp = tempfile.mkdtemp()
    a = os.path.join(tmp, "a.wav")
    src = program_surrogate(11.0, seed=42, hf_floor_db=None)
    write_wav(a, embed_ultrasonic_tone(src, 19000.0, -22.0))
    print(f"  {'path':>9} {'17-21k band dBFS':>17} {'13-16k band dBFS':>17} {'alarm':>6}")
    def bands(y):
        return (db(np.sqrt(np.mean(bandpass(y, BEACON_LO, BEACON_HI) ** 2))),
                db(np.sqrt(np.mean(bandpass(y, REF_LO, REF_HI) ** 2))))
    b0, r0 = bands(src)
    print(f"  {'source':>9} {b0:>17.1f} {r0:>17.1f}")
    for br in ("256k", "128k", "64k", "32k"):
        p = os.path.join(tmp, f"x_{br}.raw")
        aac_roundtrip(a, p, br)
        y = read_raw(p)
        det, _, _ = d1_detect(y, ambient)
        b1, r1 = bands(y)
        print(f"  {br:>9} {b1:>17.1f} {r1:>17.1f} {str(bool(det.any())):>6}")


def exp_D2():
    rule("D2  M-FSK beacon demod (SilverPush 26-tone / Lisnr 2-tone)")
    base = program_surrogate(4.0)
    sp = MFSK(18000, 20000, 26, 40.0, name="silverpush")
    rng = np.random.default_rng(7)
    ok = 0
    trials = 12
    print("  NOTE the demodulator is handed the exact symbol start the embedder")
    print("       used, so acquisition is NOT tested by this block. An ML start")
    print("       search over offsets moves the usable floor from about -80 dBFS")
    print("       to about -60 dBFS; see the sweep below for the oracle-sync cliff.")
    print(f"  {'level':>9}  {'symbol-for-symbol':>18}  {'pass syntax only':>18}")
    for lv in (-30, -50, -60, -70, -75, -80, -84, -90):
        exact = valid = 0
        for k in range(trials):
            beacon = [0] + list(rng.choice(np.arange(1, 26), 4, replace=False))
            y = sp.embed(base, beacon, start=int(0.4 * FS), level_dbfs=lv)
            d5, _ = sp.demod(y, start=int(0.4 * FS), nsym=5)
            exact += int(d5 == beacon)
            valid += int(silverpush_valid(d5))
        print(f"  {lv:>6} dBFS  {exact:>12}/{trials}  {valid:>14}/{trials}")
    print("  The two columns differ because silverpush_valid() only checks shape")
    print("  (five symbols, no repeats, 'A' present). A wrong decode with the right")
    print("  shape would score in the right column and must not be called decoded.")

    print("  --- false beacons from ordinary program audio (should be 0) ---")
    fp = 0
    for k in range(200):
        y = program_surrogate(1.0, seed=1000 + k)
        d5, _ = sp.demod(y, start=0, nsym=5)
        if silverpush_valid(d5):
            fp += 1
    print(f"  {fp}/200 clean program clips produced a syntactically valid beacon")

    li = MFSK(18500, 19500, 2, 100.0, name="lisnr")
    seq = [1, 0, 1, 1, 0, 0, 1, 0]
    y = li.embed(base, seq, start=int(0.5 * FS), level_dbfs=-24)
    dec, conf = li.demod(y, start=int(0.5 * FS), nsym=8)
    print(f"  Lisnr-style 2-tone FSK  : sent {seq}")
    print(f"                            got  {dec}   match={seq == dec}")


def exp_D3():
    rule("D3  ATSC A/334 VP1 receiver (2.5-5 kHz, tau=3 ms, T=1/106 s)")
    payload = np.concatenate([VP1["header"],
                              RNG.integers(0, 2, 127)]).astype(np.int8)
    print(f"  cell = {len(payload)} bits = {len(payload) * VP1['T']:.2f} s of audio")
    secs = (len(payload) + 4) * VP1["T"] + 0.4
    clean = program_surrogate(secs)

    print(f"  {'sigma':>6} {'SNR dB':>7} {'hdr errs':>9} {'payload bits':>13} "
          f"{'bit acc':>8}  mode")
    for sigma in (0.40, 0.25, 0.12, 0.06, 0.03):
        for snr in (20, 10, 3):
            y = embed_vp1(clean, payload, sigma=sigma)
            noise = add_white(snr, len(y))
            y = y + noise
            b = vp1_acquire(y)
            idx = b["idx"] + len(VP1["header"])
            acc = float(np.mean(b["bits"][idx:idx + 127] == payload[len(VP1["header"]):])) \
                if b["idx"] >= 0 and len(b["bits"]) >= idx + 127 else float("nan")
            print(f"  {sigma:>6.2f} {snr:>7} {b['score']:>9} "
                  f"{('from ' + str(b['oi'])):>13} {acc:>8.3f}  {b['mode']}")

    print("  --- negative control: same acquisition on unmarked program audio ---")
    fp = 0
    for k in range(30):
        y = program_surrogate(secs, seed=500 + k)
        b = vp1_acquire(y)
        if b["score"] <= 3:
            fp += 1
    print(f"  {fp}/30 unmarked clips yielded a header match within 3 bit errors")

    print("  --- codec survival (sigma=0.25, clean) ---")
    tmp = tempfile.mkdtemp()
    a = os.path.join(tmp, "a.wav")
    y = embed_vp1(clean, payload, sigma=0.25)
    write_wav(a, y)
    for br in ("256k", "128k", "64k", "32k"):
        r = os.path.join(tmp, f"v_{br}.raw")
        aac_roundtrip(a, r, br)
        z = read_raw(r)
        b = vp1_acquire(z)
        print(f"    AAC {br:>5} -> header errors {b['score']:>2}  mode {b['mode']}")


def exp_D4():
    rule("D4  Nielsen-band probing detector (presence + TDM periodicity)")
    for name, (lo, hi) in NIELSEN_BANDS.items():
        clean = program_surrogate(20.0)
        s = d4_scan(clean)
        # inject a comb of narrowband carriers in that band to mimic a mark
        t = np.arange(len(clean)) / FS
        inj = clean.copy()
        for f in np.linspace(lo, hi, 7):
            inj += 0.004 * np.sin(2 * np.pi * f * t + RNG.random())
        s2 = d4_scan(inj)
        print(f"  {name:18} clean CV {s[name]['cv']:.3f} -> marked CV "
              f"{s2[name]['cv']:.3f} | period corr {s[name]['period_corr']:+.2f} "
              f"-> {s2[name]['period_corr']:+.2f}")


if __name__ == "__main__":
    np.set_printoptions(suppress=True, linewidth=120)
    which = sys.argv[1] if len(sys.argv) > 1 else "1234"
    if "1" in which: exp_D1()
    if "2" in which: exp_D2()
    if "3" in which: exp_D3()
    if "4" in which: exp_D4()
    print()
