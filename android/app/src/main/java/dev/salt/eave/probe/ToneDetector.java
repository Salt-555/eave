package dev.salt.eave.probe;

import java.util.Arrays;

/**
 * Near-ultrasonic tone detector, the live form of the D1b experiment.
 *
 * A beacon in this band is a tone that stays where it is. Programme audio and
 * loudspeaker intermodulation do not, and neither does the handset's own spur
 * map, which is the part that synthetic testing could not show: a real capture
 * chain has narrow persistent peaks of its own, measured at -90 dBFS on this
 * handset (docs/DEVICE-P0.md).
 *
 * So the rule needs three things at once, and each one covers a hole the others
 * leave:
 *   - contrast against the band's own median, which is scale-free and survives
 *     an unknown room level and an unknown microphone gain;
 *   - an absolute calibrated floor, because contrast alone reports a beacon from
 *     a loudspeaker that is not actually reproducing one. At 64 kbps AAC the
 *     measured contrast of a 19 kHz tone is still 54.7 dB while the band sits at
 *     -107 dBFS, which is below the microphone's own noise;
 *   - persistence of the same frequency across consecutive windows, which is
 *     what programme transients fail.
 *
 * Tolerance is expressed in hertz, not bins. At 19 kHz, 2 Hz is 105 ppm, which
 * covers two independent +/-20 ppm oscillators with room for a mobile handset's
 * temperature drift. The bin width here is 0.73 Hz because the frame is
 * zero-padded, so a rule written in bins would silently depend on the FFT size.
 */
final class ToneDetector {

    /** Where findings go. Silent unless someone installs a listener, which is
     *  why this arithmetic can be exercised on a JVM with no Android in sight. */
    Report report = Report.SILENT;

    static final double FS = 48000.0;
    static final int FRAME = 48000;        // one second analysed at a time
    static final int FFT_N = 65536;        // zero-padded, 0.7324 Hz per bin
    static final int HOP = 12000;          // every 250 ms
    static final double F_MIN = 17000.0;
    static final double F_MAX = 21000.0;
    static final double GATE_DB = 20.0;
    /**
     * Measured 2026-11-14 on this project's own handset (docs/DEVICE-P0.md):
     * thirty minutes of quiet-room capture across all six AudioSources plus a
     * 68-minute real-programme corpus, scored with this class's own window
     * rule. At -100 dBFS the count of gate-passing windows (20 dB contrast
     * AND floor) in quiet-room UNPROCESSED/MIC/DEFAULT capture is zero, and
     * the shipped rule raises 0 alarms over all 41 corpus and capture files
     * (~68 min of programme audio plus the device captures). The -105 probe
     * caught two transients in 477 quiet-room windows, which is the margin
     * this floor keeps: five dB above the first room sound that could ever
     * pair with the contrast term.
     *
     * It replaces the -95 placeholder, which was never measured. Note the
     * placeholder was accidentally doing one job right: unt.mp4's genuine
     * 1.94 s programme tone cleared 20 dB contrast 13 times at -95 and 147
     * times at -100 (window counts, not alarms - the persistence term is
     * what actually refuses it). A persistent in-band programme tone is the
     * one content class that threatens both floors, and level is only
     * partly the defense; docs/REAL-AUDIO.md carries the detail.
     */
    static final double FLOOR_DBFS = -100.0;
    static final double TOL_HZ = 2.0;
    static final int NEED_WINDOWS = 4;     // one second held still

    private final double[] window = Fft.hann(FRAME);
    private final double[] re = new double[FFT_N];
    private final double[] im = new double[FFT_N];
    private final short[] frame = new short[FRAME];
    private final short[] staging = new short[HOP];
    private int staged = 0;
    private int shifted = 0;

    private final int k0;
    private final int k1;
    private final double[] levels;
    private final double[] scratch;

    private int streakBin = -1;
    private int streak = 0;

    // Read by the service for the ambient line, so a quiet room is visible and
    // not merely the absence of an alarm.
    volatile double medianDbfs = -999;
    volatile double bestContrastDb = -999;
    volatile double bestFreqHz = 0;
    volatile double bestDbfs = -999;
    volatile int alarms = 0;

    ToneDetector() {
        k0 = (int) Math.ceil(F_MIN * FFT_N / FS);
        k1 = (int) Math.floor(F_MAX * FFT_N / FS);
        levels = new double[k1 - k0 + 1];
        scratch = new double[levels.length];
    }

    void feed(short[] pcm, int len) {
        int off = 0;
        while (off < len) {
            int take = Math.min(HOP - staged, len - off);
            System.arraycopy(pcm, off, staging, staged, take);
            staged += take;
            off += take;
            if (staged == HOP) {
                staged = 0;
                rollAndAnalyse();
            }
        }
    }

    private void rollAndAnalyse() {
        if (shifted == 0) {
            // first window: use what we have, keep the tail short rather than
            // pretending the buffer is longer than it is
            System.arraycopy(staging, 0, frame, FRAME - HOP, HOP);
        } else {
            System.arraycopy(frame, HOP, frame, 0, FRAME - HOP);
            System.arraycopy(staging, 0, frame, FRAME - HOP, HOP);
        }
        shifted++;

        for (int i = 0; i < FRAME; i++) {
            re[i] = (frame[i] / 32768.0) * window[i];
            im[i] = 0.0;
        }
        for (int i = FRAME; i < FFT_N; i++) {
            re[i] = 0.0;
            im[i] = 0.0;
        }
        Fft.transform(re, im, false);

        // A full-scale sine through a Hann window has peak magnitude FRAME/4.
        // The divisor is the window length, not the transform size: zero
        // padding interpolates, it does not add gain.
        final double scale = 4.0 / FRAME;
        double med;
        {
            int n = 0;
            for (int k = k0; k <= k1; k++) {
                double amp = Math.hypot(re[k], im[k]) * scale;
                double db = 20.0 * Math.log10(Math.max(amp, 1e-12));
                levels[k - k0] = db;
                scratch[n++] = db;
            }
            Arrays.sort(scratch);
            med = (n % 2 == 1) ? scratch[n / 2] : 0.5 * (scratch[n / 2 - 1] + scratch[n / 2]);
        }
        medianDbfs = med;

        int bestIdx = -1;
        double best = -1e9;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] > best) {
                best = levels[i];
                bestIdx = i;
            }
        }
        double contrast = best - med;
        bestContrastDb = contrast;
        bestDbfs = best;
        bestFreqHz = (bestIdx + k0) * FS / FFT_N;

        boolean above = contrast >= GATE_DB && best >= FLOOR_DBFS;
        if (above) {
            int bin = bestIdx + k0;
            if (streakBin >= 0 && Math.abs(bin - streakBin) * FS / FFT_N <= TOL_HZ) {
                streak++;
            } else {
                streak = 1;
                streakBin = bin;
            }
        } else {
            streak = 0;
            streakBin = -1;
        }

        if (streak == NEED_WINDOWS) {
            alarms++;
            report.report("NEAR-ULTRASONIC",
                    String.format("tone holding at %.0f Hz, %.1f dBFS, %.1f dB above its band, "
                                    + "held %.1f s",
                            bestFreqHz, best, contrast, NEED_WINDOWS * (HOP / FS)),
                    contrast);
        }
    }
}
