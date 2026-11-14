# Measured results

Output of `python3 phy/eave_phy.py 1234`, kept in the repo so claims in the README
can be checked rather than trusted.

**Nothing in this directory is a capability claim yet.** The physical-layer tests run
against a synthetic broadcast-like surrogate, not against a loudspeaker, a microphone,
or a room. Until phase P0 runs on real hardware these numbers describe the detectors,
not the product.

One sweep is currently under investigation: detection rate at a very low simulated
high-frequency floor came out non-monotonic in tone level, which is not physically
defensible. The diagnostic added for it will say whether the content-change gate is
still mis-firing. A result we cannot explain does not get published as a result.
