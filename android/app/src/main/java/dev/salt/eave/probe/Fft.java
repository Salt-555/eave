package dev.salt.eave.probe;

/**
 * In-place radix-2 FFT. Small on purpose: no dependency, and the arithmetic has
 * to be auditable by eye because the whole app is built on it.
 *
 * Forward transform uses the -i convention, so output bin k is frequency
 * k*Fs/N. Inverse uses +i and divides by N. Anything that masks bins by
 * frequency depends on that convention being what it says.
 */
final class Fft {

    private Fft() {
    }

    static int nextPow2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }

    static void transform(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        if ((n & (n - 1)) != 0 || im.length != n) {
            throw new IllegalArgumentException("length must be a power of two");
        }
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len * (inverse ? -1.0 : 1.0);
            double wlr = Math.cos(ang);
            double wli = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curR = 1.0;
                double curI = 0.0;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    double ur = re[i + k];
                    double ui = im[i + k];
                    double vr = re[i + k + half] * curR - im[i + k + half] * curI;
                    double vi = re[i + k + half] * curI + im[i + k + half] * curR;
                    re[i + k] = ur + vr;
                    im[i + k] = ui + vi;
                    re[i + k + half] = ur - vr;
                    im[i + k + half] = ui - vi;
                    double nr = curR * wlr - curI * wli;
                    curI = curR * wli + curI * wlr;
                    curR = nr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }

    /** Hann window, the same one the Python reference uses. */
    static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
        }
        return w;
    }
}
