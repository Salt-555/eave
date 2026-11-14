package dev.salt.eave.probe;

/** Throwaway instrumentation: what does the mark detector's z actually look like,
 *  marked versus unmarked, as a function of level? Nothing here is a test; the
 *  point is to stop guessing the gate. */
public class Instrument {

    static short[] gen(double periodSec, double seconds, double markDbfs, double progDbfs,
                       long seed0, boolean marked, double[] zout) {
        int n = (int) (48000 * seconds);
        short[] out = new short[n];
        double markAmp = Math.pow(10.0, markDbfs / 20.0);
        double progAmp = Math.pow(10.0, progDbfs / 20.0);
        long seed = seed0;
        for (int i = 0; i < n; i++) {
            seed ^= seed << 13;
            seed ^= seed >>> 7;
            seed ^= seed << 17;
            double u = (seed & 0xffffffL) / (double) 0x1000000L - 0.5;
            double t = i / 48000.0;
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

    public static void main(String[] args) {
        // Every band gets its own column. A gate that is safe for one band is
        // not automatically safe for another: the CBET filter is deliberately
        // wide, so most of what it rectifies is programme, and programme's
        // envelope has a heavier tail than the narrow bands do.
        System.out.printf("%-10s %-9s %-9s %-9s %-9s %-9s %-8s%n",
                "mark dBFS", "z max", "z p50", "NAES2 z", "NAES6 z", "CBET z", "alarms");
        for (double level : new double[]{-200, -52, -46, -40, -34, -28, -22, -16, -10}) {
            java.util.ArrayList<Double> zs = new java.util.ArrayList<>();
            double maxBand0 = -999;
            double maxBand1 = -999;
            double maxBand2 = -999;
            int alarms = 0;
            for (int clip = 0; clip < 6; clip++) {
                MarkDetector d = new MarkDetector();
                // marked = there is a mark, which is any level above -100. The
                // -200 row is the clean control and is the number the alarm gate
                // has to clear, so it matters more than any of the marked rows.
                short[] x = gen(1.870, 45.0, level, -26.0, 1000L + clip * 77,
                        level > -100, null);
                short[] b = new short[4800];
                for (int i = 0; i < x.length; i += 4800) {
                    int len = Math.min(4800, x.length - i);
                    System.arraycopy(x, i, b, 0, len);
                    d.feed(b, len);
                }
                zs.add(d.maxZ(0));
                maxBand0 = Math.max(maxBand0, d.maxZ(0));
                maxBand1 = Math.max(maxBand1, d.maxZ(1));
                maxBand2 = Math.max(maxBand2, d.maxZ(2));
                alarms += d.alarms;
            }
            java.util.Collections.sort(zs);
            System.out.printf("%-10.0f %-9.2f %-9.2f %-9.2f %-9.2f %-9.2f %-8d%n", level,
                    zs.get(zs.size() - 1), zs.get(zs.size() / 2),
                    maxBand0, maxBand1, maxBand2, alarms);
        }
    }
}
