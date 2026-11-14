package dev.salt.eave.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

/**
 * These run on the desktop JVM, not on a phone, and they are the reason the
 * first field run is not the first time this arithmetic has ever executed.
 *
 * The inputs are synthesised here, in Java, with no reference to the Python
 * generator that the published tables came from. If the two implementations
 * agree on a tone's level, that is cross-validation; if this test had reused
 * the Python output it would only prove the file format works.
 *
 * Each test also asserts the negative case, because a detector that always
 * alarms passes any "it detected it" test.
 */
public class DetectorTest {

    private static final double FS = 48000.0;

    /** The detectors report through an injected interface, so a test can collect
     *  what it was told without any static state and without Android loading. */
    private final java.util.List<String> seen = new java.util.ArrayList<>();

    private Report capture() {
        return (kind, detail, confidence) -> seen.add(kind + ": " + detail);
    }

    @Before
    public void reset() {
        seen.clear();
    }

    private static short[] tone(double freqHz, double dbfs, double seconds, double noiseDbfs) {
        int n = (int) (FS * seconds);
        short[] out = new short[n];
        double amp = Math.pow(10.0, dbfs / 20.0);
        double noiseAmp = Math.pow(10.0, noiseDbfs / 20.0);
        long seed = 12345L;
        for (int i = 0; i < n; i++) {
            // xorshift, so the "random" noise is the same on every machine
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            double u = (seed & 0xffffffL) / (double) 0x1000000L - 0.5;
            double v = amp * Math.sin(2.0 * Math.PI * freqHz * i / FS) + noiseAmp * 1.7 * u;
            out[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v * 32767.0)));
        }
        return out;
    }

    private static void pump(ToneDetector d, short[] x) {
        int block = 4800;
        short[] b = new short[block];
        for (int i = 0; i < x.length; i += block) {
            int len = Math.min(block, x.length - i);
            System.arraycopy(x, i, b, 0, len);
            d.feed(b, len);
        }
    }

    private static void pump(MarkDetector d, short[] x) {
        int block = 4800;
        short[] b = new short[block];
        for (int i = 0; i < x.length; i += block) {
            int len = Math.min(block, x.length - i);
            System.arraycopy(x, i, b, 0, len);
            d.feed(b, len);
        }
    }

    private java.util.List<String> seen() {
        return seen;
    }

    @Test
    public void fftMeasuresABinCentredTonesLevelToATenthOfADecibel() {
        // The whole app's calibration rests on this divisor: a full-scale sine
        // through a Hann window has peak magnitude FRAME/4, where FRAME is the
        // window length and not the zero-padded transform size.
        double binHz = FS / ToneDetector.FFT_N;
        double centred = 25947 * binHz;              // exactly bin 25947
        ToneDetector d = new ToneDetector();
        pump(d, tone(centred, -40.0, 3.0, -120.0));
        assertEquals(centred, d.bestFreqHz, 0.5);
        assertEquals(-40.0, d.bestDbfs, 0.1);
    }

    @Test
    public void aToneBetweenBinsReadsLowByAtMostTheHannScalloppingLoss() {
        // 19000 Hz sits 0.33 bins off. Reading it slightly quiet is a property of
        // the window, not a bug, and the app should not pretend to better than
        // 1.4 dB of level accuracy for an arbitrary emitter frequency.
        ToneDetector d = new ToneDetector();
        pump(d, tone(19000, -40.0, 3.0, -120.0));
        assertEquals(19000.0, d.bestFreqHz, 1.5);
        assertEquals(-40.0, d.bestDbfs, 1.42);
    }

    @Test
    public void aHeldToneInTheBandAlarms() {
        ToneDetector d = new ToneDetector();
        d.report = capture();
        pump(d, tone(19000, -40.0, 6.0, -80.0));
        assertTrue("expected a near-ultrasonic alarm, ambient said " + d.bestContrastDb,
                d.alarms >= 1);
        assertTrue("reported: " + seen(), seen().get(0).contains("NEAR-ULTRASONIC"));
    }

    @Test
    public void stationaryNoiseAloneDoesNotAlarm() {
        ToneDetector d = new ToneDetector();
        pump(d, tone(0, -200.0, 6.0, -70.0));
        assertEquals("broadband noise must not alarm", 0, d.alarms);
    }

    @Test
    public void aLoudContrastBelowTheAbsoluteFloorDoesNotAlarm() {
        // This is the 64 kbps AAC case: the contrast can be enormous while the
        // level is under the microphone's own noise, and in that situation the
        // contrast is measuring the encoder, not a beacon.
        ToneDetector d = new ToneDetector();
        pump(d, tone(19000, -98.0, 6.0, -80.0));
        assertTrue("contrast alone should clear the gate, saw " + d.bestContrastDb,
                d.bestContrastDb > 20.0);
        assertTrue("level should sit under the floor, saw " + d.bestDbfs,
                d.bestDbfs < ToneDetector.FLOOR_DBFS);
        assertEquals("below the calibrated floor there is no alarm", 0, d.alarms);
    }

    @Test
    public void aBeaconThatHopsEverySymbolDoesNotSustainAStreak() {
        // 40 ms dwells, random order: the shape of a real M-FSK beacon. Each
        // one-second window contains several tones, so no single bin can hold
        // still for four windows and the persistence rule is what stops it,
        // not the contrast rule.
        ToneDetector d = new ToneDetector();
        long seed = 4242L;
        for (int sym = 0; sym < 150; sym++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            double f = 17600.0 + 400.0 * (Math.abs(seed) % 8);
            pump(d, tone(f, -40.0, 0.040, -90.0));
        }
        assertEquals("a symbol-rate hopper is not a held tone", 0, d.alarms);
    }

    @Test
    public void aToneThatHopsSlowlyDoesAlarmAndThatIsByDesign() {
        // Half-second dwells, 400 Hz apart. Every one-second window therefore
        // still contains half a second of each tone, so a held narrow tone
        // genuinely is present in window after window. This app is a held-tone
        // detector, so it alarms, and the assertion records that rather than
        // pretending the rule is smarter than it is. An emitter that rests on
        // one frequency for half a second at a time is inside this detector's
        // definition of a beacon; decoding which beacon it is needs the M-FSK
        // receiver, which is not built yet.
        ToneDetector d = new ToneDetector();
        short[] a = tone(18000, -40.0, 0.5, -90.0);
        short[] b = tone(18400, -40.0, 0.5, -90.0);
        pump(d, a);
        for (int round = 0; round < 12; round++) {
            pump(d, (round % 2 == 0) ? a : b);
        }
        assertTrue("slow alternation is a held tone and must alarm", d.alarms >= 1);
    }

    /** Bursts of band-limited noise on a 1.87 s schedule, the shape of a NAES2
     *  mark, over programme-like background. */
    private static short[] periodicMarks(double periodSec, double seconds, double markDbfs,
                                         double programDbfs, long seed0) {
        return periodicMarks(periodSec, seconds, markDbfs, programDbfs, seed0, true);
    }

    private static short[] periodicMarks(double periodSec, double seconds, double markDbfs,
                                         double programDbfs, long seed0, boolean marked) {
        int n = (int) (FS * seconds);
        short[] out = new short[n];
        double markAmp = Math.pow(10.0, markDbfs / 20.0);
        double progAmp = Math.pow(10.0, programDbfs / 20.0);
        long seed = seed0;
        for (int i = 0; i < n; i++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            double u = (seed & 0xffffffL) / (double) 0x1000000L - 0.5;
            double t = i / FS;
            double pos = t % periodSec;
            double v = progAmp * 1.4 * u;
            if (marked && pos < 0.030) {
                // 5.2, 6.1 and 5.7 kHz: inside the NAES2 band. An earlier
                // version of this generator multiplied an accumulated 5.3 kHz
                // phase by those numbers, which makes 27.6, 32.3 and 30.2 kHz
                // tones. Above Nyquist, aliased up into the near-ultrasonic
                // band, and exactly invisible to a 4.5-6.3 kHz detector. The
                // instrument then reported, correctly, that marked and unmarked
                // were indistinguishable, about a signal with no mark anywhere
                // near where it was being measured.
                v += markAmp * 0.33 * (Math.sin(2.0 * Math.PI * 5200.0 * t)
                        + Math.sin(2.0 * Math.PI * 6100.0 * t)
                        + Math.sin(2.0 * Math.PI * 5700.0 * t));
            }
            out[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v * 32767.0)));
        }
        return out;
    }

    @Test
    public void aRepeatingInBandMarkIsDetected() {
        // -18 dBFS is well past saturation for this statistic; the interesting
        // end of the range is in the two tests below, at -40 and -46.
        MarkDetector d = new MarkDetector();
        d.report = capture();
        pump(d, periodicMarks(1.870, 45.0, -18.0, -26.0, 999L));
        assertTrue("expected a periodic-mark alarm, best z was " + d.lastZ, d.alarms >= 1);
        assertTrue("the alarm must name the band it fired on, saw: " + seen(),
                seen().get(0).contains("NAES2"));
    }

    /**
     * The sensitivity floor, re-pinned at the calibrated gates.
     *
     * Under the old 4.0/0.05 gate this test watched the six clips straddle
     * the threshold. Under the corpus-calibrated 8.0/0.2 gate (see the gate
     * comment in MarkDetector) the whole straddle is below the gate: measured
     * best-z per clip over these exact six seeds is 2.63, 3.48, 5.43, 5.59,
     * 6.62, 7.79, and none of them alarms. That is the calibration buying
     * zero false alarms on 2.59 h of real programme, and it is a real loss,
     * recorded here rather than hidden: a mark 14 dB under a synthetic
     * surrogate sits inside the real-programme z tail, so this detector
     * honestly cannot promise to see it. What it can see is a mark that
     * dominates its band (bedA corpus runs: rise ~0.87, saturated).
     */
    @Test
    public void aMarkFourteenDbBelowTheProgrammeSitsBelowTheCalibratedGate() {
        double[] z = new double[6];
        for (int clip = 0; clip < 6; clip++) {
            MarkDetector d = new MarkDetector();
            d.report = capture();
            pump(d, periodicMarks(1.870, 45.0, -40.0, -26.0, 1000L + clip * 77));
            assertEquals("no clip in the measured straddle may alarm at 8.0/0.2, clip "
                    + clip + " reached z " + d.maxZ(0), 0, d.alarms);
            z[clip] = d.maxZ(0);
        }
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : z) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        assertTrue("measured straddle must stay where it was measured (saw " + lo
                + " to " + hi + ")", lo > 2.0 && hi < 8.0);
    }

    @Test
    public void aMarkTwentyDbBelowTheProgrammeIsNotDetected() {
        MarkDetector d = new MarkDetector();
        d.report = capture();
        pump(d, periodicMarks(1.870, 45.0, -46.0, -26.0, 31L));
        assertEquals("this is the measured floor; do not claim past it", 0, d.alarms);
    }

    @Test
    public void theSameLevelOfBackgroundWithoutARepeatIsNotDetected() {
        MarkDetector d = new MarkDetector();
        // identical generator and seed, mark switched off: the only difference
        // between this and the test above is the repetition
        pump(d, periodicMarks(1.870, 45.0, -18.0, -26.0, 999L, false));
        assertEquals("no repeat, no mark", 0, d.alarms);
    }
}
