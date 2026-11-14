package dev.salt.eave.probe;

/**
 * The one reporting path the detectors are allowed to see.
 *
 * This exists as its own type rather than a method on EaveService because the
 * first version had the detectors calling EaveService.report() directly, and
 * EaveService extends android.app.Service. Under Gradle that hides the problem,
 * since unit tests run against a mockable android.jar; run the same detector on
 * a bare JVM and the first alarm in the run dies with NoClassDefFoundError on a
 * class that has nothing to do with arithmetic. Every test that only ever saw a
 * silent signal had passed, and the one that produced an alarm had not.
 *
 * A detector now depends on this interface and on nothing Android at all.
 */
public interface Report {

    /** An interface rather than java.util.function so the confidence can travel
     *  as a double without being boxed into a varargs bag. */
    void report(String kind, String detail, double confidence);

    Report SILENT = (kind, detail, confidence) -> {
    };
}
