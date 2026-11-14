package dev.salt.eave.probe;

import java.util.Arrays;

/**
 * Periodic in-band mark detector, the live form of the D4b experiment.
 *
 * Broadcast watermarks that live in the audible band (Nielsen NAES2 around
 * 4.5-6.3 kHz, NAES6 and CBET lower down) are not tones. They are short bursts
 * of broadband energy repeated on a fixed schedule, roughly 1.87 s and roughly
 * 4.8 s, masked under the programme. The programme itself is also bursty, on
 * every timescale from a syllable to a scene, so "loud things happen regularly"
 * is worth nothing on its own.
 *
 * Two things make the period visible through the programme:
 *   - high-pass the envelope well above the programme's own rhythm but far below
 *     the mark's repetition rate, so what is left is the mark's on-off keying;
 *   - compare the autocorrelation at the target period against its neighbouring
 *     lags rather than against an absolute threshold. Programme smoothness
 *     inflates every lag together; a real repeat lifts one lag against its
 *     neighbourhood. That comparison is a z-score, and a z-score survives the
 *     fact that different content has wildly different autocorrelation.
 *
 * An earlier version measured periodicity with one correlation at one lag over a
 * 0.5 s envelope and reported +0.97 for both clean and marked clips: it was
 * measuring how smooth the programme was, not whether anything repeated. This
 * class exists because that metric could not have worked at any threshold.
 */
final class MarkDetector {

    /** Where findings go. Silent unless someone installs a listener, which is
     *  why this arithmetic can be exercised on a JVM with no Android in sight. */
    Report report = Report.SILENT;

    static final double FS = 48000.0;
    static final int BLOCK = 48;           // 1 ms of audio per envelope sample
    static final int HISTORY = 32768;      // 32.8 s of envelope
    static final long EVAL_MS = 2000;
    static final double HP_HZ = 0.8;
    static final double LP_HZ = 40.0;
    /**
     * Gates are per-band and calibrated, not per-instinct.
     *
     * History: 4.0/0.05 came from a white-noise surrogate (worst z 2.23,
     * Instrument.java). That surrogate understated real programme. The corpus
     * run (docs/REAL-AUDIO.md: 32 real files, 2.59 h of evaluations through
     * this exact code) put the clean-programme tail at z 6.0 / rise 0.21 for
     * NAES2, z 10.6 / rise 0.17 for NAES6 (unt.mp4 genuinely repeats at
     * 1.94 s - inside this detector's +/-15% window around its 1.87 s target),
     * and z 5.2 for CBET. The gate that actually separates the tail from
     * marks is the absolute-rise term, not z: mark evaluations that are
     * visible at all sit at rise ~0.87 (saturated), every corpus tail row
     * sits at rise <= 0.21. 8.0 / 0.2 is 0 corpus false alarms per band over
     * 2.59 h with the detected-mark population untouched (100% of bedA mark
     * evaluations kept).
     *
     * The honest cost, measured in the same run: a -34 dBFS mark on a file
     * with loud in-band programme (mssp: 4.5-6.3 kHz content ~13 dB above
     * the mark) is invisible at ANY gate on this list - TPR 0.00, rise 0.02.
     * This detector sees marks that dominate their band, not marks buried
     * under real 5 kHz content.
     */
    static final double Z_GATE_NAES2 = 8.0;
    static final double Z_GATE_NAES6 = 8.0;
    static final double Z_GATE_CBET = 4.0;
    static final double MIN_ABS_RISE = 0.2;
    static final double MIN_MAD = 0.01;

    private static final class Band {
        final String name;
        final double hz0;
        final double hz1;
        final double periodSec;
        final String periodName;
        final double zGate;
        final Biquad[] filter;
        final double[] env = new double[HISTORY];
        int pos = 0;
        double sum = 0.0;
        int filled = 0;
        double maxZ = -999.0;

        Band(String name, double hz0, double hz1, double periodSec, String periodName,
                double zGate) {
            this.name = name;
            this.hz0 = hz0;
            this.hz1 = hz1;
            this.periodSec = periodSec;
            this.periodName = periodName;
            this.zGate = zGate;
            double f0 = Math.sqrt(hz0 * hz1);
            double q = f0 / Math.max(1.0, hz1 - hz0);
            // Two second-order sections: one is too wide a skirt to keep an
            // adjacent band's programme out.
            this.filter = new Biquad[]{new Biquad(f0, q), new Biquad(f0, q)};
        }
    }

    private final Band[] bands = {
            new Band("NAES2-class 4.5-6.3 kHz", 4500, 6300, 1.870, "1.87 s", Z_GATE_NAES2),
            new Band("NAES6-class 3.0-4.8 kHz", 3000, 4800, 4.800, "4.80 s", Z_GATE_NAES6),
            new Band("CBET-class 1-3 kHz", 1000, 3000, 4.800, "4.80 s", Z_GATE_CBET),
    };

    private final double[] re = new double[HISTORY];
    private final double[] im = new double[HISTORY];
    private final double[] env = new double[HISTORY];
    private final double[] scratch = new double[512];
    private final double[] dev = new double[512];
    private final double[] ys = new double[3];
    private long sinceEval = 0;
    private int blockCount = 0;

    /**
     * z from the most recent evaluation, not the best ever seen.
     *
     * This used to be a max hold, and the first field run showed why that is
     * wrong inside four minutes: a single reading of 5.9 at 23:09 left the
     * ambient line claiming "strongest mark z=5.9 (NAES2-class)" for every
     * evaluation afterwards, so the screen reported a standing suspicion long
     * after the statistic had come back down. A held maximum on a display that
     * is meant to describe the present tense is a lie with good memory.
     */
    volatile double lastZ = 0;
    volatile String lastZLabel = "none";
    /** The all-time high, kept apart from the current reading, because whether
     *  the alarm gate is set correctly really is a session-wide question. */
    volatile double sessionMaxZ = 0;
    volatile String sessionMaxLabel = "none";
    volatile int alarms = 0;

    /** Highest z this band has ever produced, which is the number that decides
     *  whether a gate is a gate or a guess. */
    double maxZ(int band) {
        return bands[band].maxZ;
    }

    /** Audio from the capture thread, any block size. */
    void feed(short[] pcm, int len) {
        for (int i = 0; i < len; i++) {
            double x = pcm[i] / 32768.0;
            for (int b = 0; b < bands.length; b++) {
                Biquad[] f = bands[b].filter;
                ys[b] = Math.abs(f[1].process(f[0].process(x)));
            }
            for (int b = 0; b < bands.length; b++) {
                bands[b].sum += ys[b];
            }
            if (++blockCount >= BLOCK) {
                for (int b = 0; b < bands.length; b++) {
                    Band band = bands[b];
                    band.env[band.pos] = band.sum / blockCount;
                    band.pos = (band.pos + 1) % HISTORY;
                    if (band.pos == 0) {
                        band.filled = 1;
                    }
                    band.sum = 0.0;
                }
                blockCount = 0;
            }
        }
        sinceEval += (long) (1000.0 * len / FS);
        if (sinceEval >= EVAL_MS) {
            sinceEval = 0;
            analyse();
        }
    }

    private void analyse() {
        double evalTop = Double.NaN;
        String evalTopLabel = "none";
        for (Band band : bands) {
            if (band.filled == 0) {
                continue;
            }
            // newest-last, chronological
            for (int i = 0; i < HISTORY; i++) {
                double v = band.env[(band.pos + i) % HISTORY];
                re[i] = v;
                im[i] = 0.0;
            }
            double mean = 0.0;
            for (int i = 0; i < HISTORY; i++) {
                mean += re[i];
            }
            mean /= HISTORY;
            highPassEnvelope();

            double energy = 0.0;
            for (int i = 0; i < HISTORY; i++) {
                energy += re[i] * re[i];
            }
            int lag = (int) Math.round(band.periodSec * 1000.0);
            int nl = 0;
            double target = Double.NaN;
            for (int l = (int) (lag * 0.85); l <= (int) (lag * 1.15); l += 16) {
                double r = autocorr(re, l, energy);
                if (Math.abs(l - lag) <= 8) {
                    target = r;
                } else {
                    if (nl < scratch.length) {
                        scratch[nl++] = r;
                    }
                }
            }
            if (Double.isNaN(target) || nl < 8) {
                continue;
            }
            Arrays.sort(scratch, 0, nl);
            double med = median(scratch, nl);
            for (int i = 0; i < nl; i++) {
                dev[i] = Math.abs(scratch[i] - med);
            }
            Arrays.sort(dev);
            double mad = dev[dev.length / 2];
            double z = (target - med) / Math.max(1.4826 * mad, MIN_MAD);

            if (z > band.maxZ) {
                band.maxZ = z;
            }
            if (z > evalTop || Double.isNaN(evalTop)) {
                evalTop = z;
                evalTopLabel = band.name + " @" + band.periodName;
            }
            if (z >= band.zGate && (target - med) >= MIN_ABS_RISE) {
                alarms++;
                report.report("PERIODIC MARK",
                        String.format("%s repeats every %s, z=%.1f against neighbouring lags, "
                                        + "envelope mean %.5f full-scale",
                                band.name, band.periodName, z, mean),
                        z);
            }
        }
        lastZ = Double.isNaN(evalTop) ? lastZ : evalTop;
        lastZLabel = evalTopLabel;
        if (evalTop > sessionMaxZ) {
            sessionMaxZ = evalTop;
            sessionMaxLabel = evalTopLabel;
        }
    }

    /** Zero everything outside HP_HZ..LP_HZ, in place, on re[]. */
    private void highPassEnvelope() {
        Fft.transform(re, im, false);
        double binHz = 1000.0 / HISTORY;      // envelope is sampled at 1 kHz
        int kLo = (int) Math.ceil(HP_HZ / binHz);
        int kHi = (int) Math.floor(LP_HZ / binHz);
        for (int k = 0; k <= HISTORY / 2; k++) {
            boolean keep = k >= kLo && k <= kHi;
            if (!keep) {
                re[k] = 0.0;
                im[k] = 0.0;
                int mirror = HISTORY - k;
                if (mirror >= 0 && mirror < HISTORY) {
                    re[mirror] = 0.0;
                    im[mirror] = 0.0;
                }
            }
        }
        Fft.transform(re, im, true);
    }

    /** Normalised by the full-window energy, so it is comparable across lags. */
    private static double autocorr(double[] x, int lag, double energy) {
        if (energy <= 0.0) {
            return 0.0;
        }
        double num = 0.0;
        for (int i = 0; i + lag < x.length; i++) {
            num += x[i] * x[i + lag];
        }
        return num / energy;
    }

    private static double median(double[] sorted, int n) {
        return (n % 2 == 1) ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /** RBJ band-pass, constant peak gain at the centre frequency. */
    private static final class Biquad {
        private double b0, b1, b2, a1, a2;
        private double z1, z2;

        Biquad(double f0, double q) {
            double w0 = 2.0 * Math.PI * f0 / FS;
            double cw = Math.cos(w0);
            double sw = Math.sin(w0);
            double alpha = sw / (2.0 * q);
            double a0 = 1.0 + alpha;
            b0 = alpha / a0;
            b1 = 0.0;
            b2 = -alpha / a0;
            a1 = -2.0 * cw / a0;
            a2 = (1.0 - alpha) / a0;
        }

        double process(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    }
}
