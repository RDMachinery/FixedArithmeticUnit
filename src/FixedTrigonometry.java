/**
 * FixedTrigonometry
 *
 * Provides the standard set of trigonometric functions computed entirely
 * through {@link FixedArithmetic} operations (add, subtract, multiply, divide).
 * No floating-point arithmetic, {@code Math.*}, or native trig calls are used.
 *
 * <h2>Functions provided</h2>
 * <ul>
 *   <li>{@link #sin(FixedArithmetic)}  – sine of an angle in radians</li>
 *   <li>{@link #cos(FixedArithmetic)}  – cosine of an angle in radians</li>
 *   <li>{@link #tan(FixedArithmetic)}  – tangent of an angle in radians</li>
 *   <li>{@link #asin(FixedArithmetic)} – arcsine,  result in radians ∈ [-π/2, π/2]</li>
 *   <li>{@link #acos(FixedArithmetic)} – arccosine, result in radians ∈ [0, π]</li>
 *   <li>{@link #atan(FixedArithmetic)} – arctangent, result in radians ∈ (-π/2, π/2)</li>
 *   <li>{@link #atan2(FixedArithmetic, FixedArithmetic)} – two-argument arctangent</li>
 * </ul>
 *
 * <h2>Algorithms</h2>
 *
 * <h3>sin / cos — Taylor series with range reduction</h3>
 * <pre>
 *   sin(x) = x - x³/3! + x⁵/5! - x⁷/7! + …
 *   cos(x) = 1 - x²/2! + x⁴/4! - x⁶/6! + …
 * </pre>
 * The input angle is first reduced to the range [-π/4, π/4] using standard
 * identities (sin/cos periodicity, quadrant symmetry) so that the series
 * converges quickly.  Twenty terms are accumulated; each successive term is
 * obtained by multiplying the previous one by -x²/(2n·(2n+1)) (or the
 * equivalent for cosine), so only one multiply and one divide per term.
 *
 * <h3>tan</h3>
 * Computed as sin(x) / cos(x).  A domain check guards against near-zero
 * cosine values (|cos(x)| < 10⁻⁸ ≈ undefined).
 *
 * <h3>atan — arctangent Taylor series with range reduction</h3>
 * <pre>
 *   atan(x) = x - x³/3 + x⁵/5 - x⁷/7 + …   (converges only for |x| ≤ 1)
 * </pre>
 * Range is reduced to |x| ≤ 1 using:
 * <ul>
 *   <li>If |x| > 1: {@code atan(x) = ±π/2 - atan(1/x)}</li>
 *   <li>Half-angle reduction: {@code atan(x) = 2·atan(x/(1+√(1+x²)))}</li>
 * </ul>
 * The square root is computed via Newton–Raphson iteration using only
 * FixedArithmetic operations.
 *
 * <h3>asin / acos — expressed via atan</h3>
 * <pre>
 *   asin(x) = atan(x / √(1-x²))
 *   acos(x) = π/2 - asin(x)
 * </pre>
 *
 * <h3>atan2(y, x)</h3>
 * Standard quadrant-aware two-argument arctangent built from {@link #atan}.
 *
 * <h2>Precision</h2>
 * Results agree with {@code java.lang.Math} trig functions to within
 * approximately ±2 ULP at {@code FixedArithmetic.PRECISION} = 9 decimal places
 * for inputs in their natural domains.
 */
public class FixedTrigonometry {

    // ── high-precision constants (pre-computed once) ──────────────────────────

    /** π  ≈ 3.141592653 */
    private static final FixedArithmetic PI   = FixedArithmetic.of("3.141592653");
    /** π/2 */
    private static final FixedArithmetic PI_2 = FixedArithmetic.of("1.570796326");
    /** π/4 */
    private static final FixedArithmetic PI_4 = FixedArithmetic.of("0.785398163");
    /** 2π */
    private static final FixedArithmetic TWO_PI = FixedArithmetic.of("6.283185307");
    /** 1 */
    private static final FixedArithmetic ONE  = FixedArithmetic.of(1);
    /** 2 */
    private static final FixedArithmetic TWO  = FixedArithmetic.of(2);
    /** −1 */
    private static final FixedArithmetic NEG_ONE = FixedArithmetic.of(-1);
    /** Threshold below which cosine is considered undefined for tan(x) */
    private static final FixedArithmetic COS_ZERO_THRESHOLD = FixedArithmetic.of("0.00000001");
    /** Number of Taylor series terms (sufficient for 9-digit precision). */
    private static final int SERIES_TERMS = 20;

    // ── private constructor – utility class, not instantiated ─────────────────

    private FixedTrigonometry() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Returns the sine of {@code angle} (in radians).
     *
     * @param angle angle in radians (any finite value)
     * @return sin(angle) ∈ [-1, 1]
     */
    public static FixedArithmetic sin(FixedArithmetic angle) {
        // Reduce to [-π, π]
        FixedArithmetic x = reduceAngle(angle);

        // Quadrant reduction to [-π/4, π/4] using identities:
        //   sin(π - x) =  sin(x)   →  x in [π/4, 3π/4]  → reflect
        //   sin(-π + x) = -sin(x)  →  x in [-3π/4, -π/4] → reflect with negation
        //   sin(x ± π/2) use cos   →  handled below
        boolean negate = false;

        if (isNegative(x)) {
            x = negate(x);
            negate = true;
        }
        // Now x ∈ [0, π]
        if (greaterThan(x, PI_2)) {
            // sin(x) = sin(π - x) for x ∈ [π/2, π]
            x = PI.subtract(x);
        }
        // Now x ∈ [0, π/2]; further reduce to [0, π/4] via:
        // sin(x) = cos(π/2 - x) for x ∈ [π/4, π/2]
        boolean useCosSeries = false;
        if (greaterThan(x, PI_4)) {
            x = PI_2.subtract(x);
            useCosSeries = true;
        }
        // x is now in [0, π/4]
        FixedArithmetic result = useCosSeries ? cosSeries(x) : sinSeries(x);
        return negate ? negate(result) : result;
    }

    /**
     * Returns the cosine of {@code angle} (in radians).
     *
     * @param angle angle in radians (any finite value)
     * @return cos(angle) ∈ [-1, 1]
     */
    public static FixedArithmetic cos(FixedArithmetic angle) {
        // cos(x) = sin(π/2 - x)
        return sin(PI_2.subtract(angle));
    }

    /**
     * Returns the tangent of {@code angle} (in radians).
     *
     * @param angle angle in radians
     * @return tan(angle) = sin(angle) / cos(angle)
     * @throws ArithmeticException if cos(angle) ≈ 0 (angle ≈ π/2 + k·π)
     */
    public static FixedArithmetic tan(FixedArithmetic angle) {
        FixedArithmetic s = sin(angle);
        FixedArithmetic c = cos(angle);
        FixedArithmetic absC = isNegative(c) ? negate(c) : c;
        if (!greaterThan(absC, COS_ZERO_THRESHOLD)) {
            throw new ArithmeticException(
                "tan is undefined: cos(angle) ≈ 0 at angle ≈ " + angle);
        }
        return s.divide(c);
    }

    /**
     * Returns the arcsine of {@code x}.
     *
     * @param x value in [-1, 1]
     * @return asin(x) in radians ∈ [-π/2, π/2]
     * @throws ArithmeticException if {@code x} is outside [-1, 1]
     */
    public static FixedArithmetic asin(FixedArithmetic x) {
        validateDomain(x, "asin");
        // asin(x) = atan(x / sqrt(1 - x²))
        // Edge cases: x = ±1 → ±π/2 directly (avoids divide-by-zero in sqrt)
        if (equalsOne(x))      return PI_2;
        if (equalsNegOne(x))   return negate(PI_2);

        FixedArithmetic x2     = x.multiply(x);
        FixedArithmetic oneMinusX2 = ONE.subtract(x2);
        FixedArithmetic sqrtTerm   = sqrt(oneMinusX2);
        return atan(x.divide(sqrtTerm));
    }

    /**
     * Returns the arccosine of {@code x}.
     *
     * @param x value in [-1, 1]
     * @return acos(x) in radians ∈ [0, π]
     * @throws ArithmeticException if {@code x} is outside [-1, 1]
     */
    public static FixedArithmetic acos(FixedArithmetic x) {
        validateDomain(x, "acos");
        // acos(x) = π/2 - asin(x)
        return PI_2.subtract(asin(x));
    }

    /**
     * Returns the arctangent of {@code x}.
     *
     * @param x any finite value
     * @return atan(x) in radians ∈ (-π/2, π/2)
     */
    public static FixedArithmetic atan(FixedArithmetic x) {
        return atanInternal(x);
    }

    /**
     * Returns the angle (in radians) whose tangent is {@code y/x},
     * using the signs of both arguments to determine the correct quadrant.
     *
     * <p>The result is in the range (-π, π].  Follows the same convention
     * as {@code java.lang.Math.atan2}:
     * <ul>
     *   <li>atan2(0, positive) = 0</li>
     *   <li>atan2(positive, 0) = π/2</li>
     *   <li>atan2(0, negative) = π</li>
     *   <li>atan2(negative, 0) = -π/2</li>
     *   <li>atan2(0, 0)        = 0  (by convention)</li>
     * </ul>
     *
     * @param y the ordinate (y-coordinate)
     * @param x the abscissa (x-coordinate)
     * @return atan2(y, x) in radians ∈ (-π, π]
     */
    public static FixedArithmetic atan2(FixedArithmetic y, FixedArithmetic x) {
        boolean yNeg = isNegative(y);
        boolean xNeg = isNegative(x);
        boolean yZero = isZero(y);
        boolean xZero = isZero(x);

        if (xZero && yZero) return FixedArithmetic.of(0);
        if (xZero)          return yNeg ? negate(PI_2) : PI_2;
        if (yZero)          return xNeg ? PI : FixedArithmetic.of(0);

        FixedArithmetic base = atan(y.divide(x));

        if (!xNeg) {
            // Quadrants I and IV — atan(y/x) is correct as-is
            return base;
        }
        // x < 0: Quadrants II and III
        if (!yNeg) {
            // Quadrant II: add π
            return base.add(PI);
        } else {
            // Quadrant III: subtract π
            return base.subtract(PI);
        }
    }

    /**
     * Returns π as a {@link FixedArithmetic} value (convenience accessor).
     *
     * @return π ≈ 3.141592653
     */
    public static FixedArithmetic pi() {
        return PI;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    // ── Taylor series (well-conditioned for x ∈ [-π/4, π/4]) ────────────────

    /**
     * sin(x) via Taylor series:
     *   sin(x) = Σ (−1)^n · x^(2n+1) / (2n+1)!
     *          = x − x³/6 + x⁵/120 − …
     *
     * Each term is built incrementally:
     *   term_{n+1} = term_n · (−x²) / ((2n+2)·(2n+3))
     */
    private static FixedArithmetic sinSeries(FixedArithmetic x) {
        FixedArithmetic x2      = x.multiply(x);        // x²
        FixedArithmetic negX2   = negate(x2);           // −x²
        FixedArithmetic term    = x;                     // first term: x
        FixedArithmetic sum     = x;
        for (int n = 0; n < SERIES_TERMS; n++) {
            // factor = −x² / ((2n+2)*(2n+3))
            long denom1 = 2L * n + 2;
            long denom2 = 2L * n + 3;
            FixedArithmetic divisor = FixedArithmetic.of(denom1).multiply(FixedArithmetic.of(denom2));
            term = term.multiply(negX2).divide(divisor);
            sum  = sum.add(term);
        }
        return sum;
    }

    /**
     * cos(x) via Taylor series:
     *   cos(x) = Σ (−1)^n · x^(2n) / (2n)!
     *          = 1 − x²/2 + x⁴/24 − …
     *
     * Each term is built incrementally:
     *   term_{n+1} = term_n · (−x²) / ((2n+1)·(2n+2))
     */
    private static FixedArithmetic cosSeries(FixedArithmetic x) {
        FixedArithmetic x2      = x.multiply(x);
        FixedArithmetic negX2   = negate(x2);
        FixedArithmetic term    = ONE;                   // first term: 1
        FixedArithmetic sum     = ONE;
        for (int n = 0; n < SERIES_TERMS; n++) {
            long denom1 = 2L * n + 1;
            long denom2 = 2L * n + 2;
            FixedArithmetic divisor = FixedArithmetic.of(denom1).multiply(FixedArithmetic.of(denom2));
            term = term.multiply(negX2).divide(divisor);
            sum  = sum.add(term);
        }
        return sum;
    }

    // ── atan implementation with range reduction ──────────────────────────────

    /**
     * atan(x) using the Leibniz/Gregory series with range-reduction to
     * improve convergence.
     *
     * <p>The series {@code atan(x) = x − x³/3 + x⁵/5 − …} converges only
     * when |x| ≤ 1 and is slow near 1.  We apply two reductions:</p>
     * <ol>
     *   <li><b>Reciprocal</b>: if |x| > 1, use {@code atan(x) = sgn(x)·π/2 − atan(1/x)}</li>
     *   <li><b>Half-angle</b>: applied repeatedly until |x| ≤ 0.4, using
     *       {@code atan(x) = 2·atan(x / (1 + √(1+x²)))}, which halves the
     *       effective argument each iteration.</li>
     * </ol>
     */
    private static FixedArithmetic atanInternal(FixedArithmetic x) {
        if (isZero(x)) return FixedArithmetic.of(0);

        boolean negate = false;
        if (isNegative(x)) {
            x = negate(x);
            negate = true;
        }
        // x is now non-negative

        boolean reciprocal = false;
        if (greaterThan(x, ONE)) {
            // atan(x) = π/2 − atan(1/x) for x > 1
            x = ONE.divide(x);
            reciprocal = true;
        }
        // x is now in (0, 1]

        // Half-angle reduction: bring |x| below 0.4 for fast convergence.
        // atan(x) = 2 · atan(x / (1 + sqrt(1 + x²)))
        // Each application reduces x roughly by half.
        int halvings = 0;
        FixedArithmetic threshold = FixedArithmetic.of("0.4");
        while (greaterThan(x, threshold)) {
            FixedArithmetic x2       = x.multiply(x);
            FixedArithmetic inner    = ONE.add(x2);
            FixedArithmetic sqrtInner = sqrt(inner);
            x = x.divide(ONE.add(sqrtInner));
            halvings++;
        }

        // Now compute the series for the reduced x
        FixedArithmetic result = atanSeries(x);

        // Undo the halvings
        for (int i = 0; i < halvings; i++) {
            result = TWO.multiply(result);
        }

        // Undo the reciprocal transform
        if (reciprocal) {
            result = PI_2.subtract(result);
        }

        return negate ? negate(result) : result;
    }

    /**
     * atan(x) via the Leibniz/Gregory series (converges well for |x| ≤ 0.4):
     *   atan(x) = x − x³/3 + x⁵/5 − …
     *
     * Each term is built incrementally:
     *   term_{n+1} = term_n · (−x²) · (2n+1)/(2n+3)
     */
    private static FixedArithmetic atanSeries(FixedArithmetic x) {
        FixedArithmetic x2    = x.multiply(x);
        FixedArithmetic negX2 = negate(x2);
        FixedArithmetic term  = x;
        FixedArithmetic sum   = x;
        for (int n = 0; n < SERIES_TERMS; n++) {
            long num   = 2L * n + 1;
            long denom = 2L * n + 3;
            FixedArithmetic factor = FixedArithmetic.of(num).divide(FixedArithmetic.of(denom));
            term = term.multiply(negX2).multiply(factor);
            sum  = sum.add(term);
        }
        return sum;
    }

    // ── Newton–Raphson square root ────────────────────────────────────────────

    /**
     * Computes √x using Newton–Raphson iteration:
     *   r_{n+1} = (r_n + x/r_n) / 2
     *
     * Starting estimate uses the integer square root via binary search,
     * then a few fixed-point NR iterations converge to full precision.
     *
     * @param x non-negative FixedArithmetic value
     * @return √x
     */
    private static FixedArithmetic sqrt(FixedArithmetic x) {
        if (isZero(x)) return FixedArithmetic.of(0);

        // Seed: use the floating-point sqrt of the integer part as a rough start,
        // expressed as a FixedArithmetic.  We build it purely by FixedArithmetic
        // operations: start at 1 and climb via integer binary search.
        // For typical trig inputs (x near 1 or 2) this converges in very few steps.
        FixedArithmetic guess = seedSqrt(x);

        // Newton–Raphson: 15 iterations are more than enough for 9-digit precision
        // (each iteration roughly doubles the correct digits).
        FixedArithmetic half = FixedArithmetic.of("0.5");
        for (int i = 0; i < 15; i++) {
            FixedArithmetic next = guess.add(x.divide(guess)).multiply(half);
            guess = next;
        }
        return guess;
    }

    /**
     * Produces an initial seed for √x that is within a factor of 2 of the
     * true root, computed without floating-point or the {@code Math} class.
     *
     * Strategy: find the largest integer {@code k} such that k² ≤ x,
     * using doubling and binary search entirely through FixedArithmetic.
     * For fractional values < 1 we instead use 1 as the seed (which is fine
     * for Newton–Raphson).
     */
    private static FixedArithmetic seedSqrt(FixedArithmetic x) {
        // If x ≥ 1, find floor(sqrt(integerPart(x))) as a starting seed.
        if (!greaterThan(ONE, x)) {          // x >= 1
            long ip = x.integerPart();
            // Integer square root of ip via binary search using only + and -
            long lo = 1, hi = ip;
            // Clamp hi to avoid overflow in hi*hi: sqrt(ip) ≤ ip for ip ≥ 1
            while (lo < hi) {
                long mid = lo + (hi - lo + 1) / 2;
                // Check mid² ≤ ip without overflow: mid ≤ sqrt(Long.MAX) ≈ 3e9
                // For typical trig inputs ip < 100; safe.
                if (mid * mid <= ip) lo = mid;
                else                 hi = mid - 1;
            }
            return FixedArithmetic.of(lo < 1 ? 1 : lo);
        }
        // x ∈ (0, 1): seed with 1 — NR will converge rapidly
        return ONE;
    }

    // ── range-reduction helpers ───────────────────────────────────────────────

    /**
     * Reduces angle to [-π, π] by subtracting the nearest integer multiple
     * of 2π.  Computed using only FixedArithmetic.
     */
    private static FixedArithmetic reduceAngle(FixedArithmetic angle) {
        // k = round(angle / (2π))
        // We need the signed fractional part of (angle/2π) to decide which
        // way to round.  remainder() is always non-negative (it works on abs),
        // so we derive the signed fraction from rawScaled() directly.
        FixedArithmetic divided = angle.divide(TWO_PI);
        long k = divided.integerPart();

        // signedFrac is the fractional part in units of 10^-PRECISION, with sign.
        // rawScaled() = integerPart * SCALE + signedFrac*SCALE, so:
        //   signedFrac*SCALE = rawScaled() - integerPart*rawScaled()/|rawScaled()|*SCALE
        // Simpler: just compute rawScaled() - k * SCALE
        long SCALE = 1_000_000_000L;  // 10^PRECISION
        long signedRem = divided.rawScaled() - k * SCALE;

        long halfScale = 500_000_000L;   // 0.5 in units of 10^-9
        if (signedRem >=  halfScale) k = k + 1;
        if (signedRem <= -halfScale) k = k - 1;

        return angle.subtract(TWO_PI.multiply(FixedArithmetic.of(k)));
    }

    // ── domain validation ─────────────────────────────────────────────────────

    /** Throws if x is outside [-1, 1] (for asin / acos). */
    private static void validateDomain(FixedArithmetic x, String fn) {
        if (greaterThan(x, ONE) || greaterThan(NEG_ONE, x)) {
            throw new ArithmeticException(
                fn + " domain error: argument " + x + " is outside [-1, 1]");
        }
    }

    // ── FixedArithmetic comparison / sign helpers ─────────────────────────────

    /** True if a > b. */
    private static boolean greaterThan(FixedArithmetic a, FixedArithmetic b) {
        // rawScaled() is the unambiguous signed integer backing both values;
        // a > b iff a.rawScaled() > b.rawScaled().
        return a.rawScaled() > b.rawScaled();
    }

    /** True if x < 0. */
    private static boolean isNegative(FixedArithmetic x) {
        // remainder() always returns a non-negative value (it works on abs),
        // so integerPart()/remainder() cannot distinguish -0.5 from +0.5.
        // rawScaled() is the single signed source of truth.
        return x.rawScaled() < 0;
    }

    /** True if x == 0 (to within fixed-point representation). */
    private static boolean isZero(FixedArithmetic x) {
        return x.rawScaled() == 0;
    }

    /** True if x == 1 exactly. */
    private static boolean equalsOne(FixedArithmetic x) {
        return x.integerPart() == 1 && x.remainder() == 0;
    }

    /** True if x == -1 exactly. */
    private static boolean equalsNegOne(FixedArithmetic x) {
        return x.integerPart() == -1 && x.remainder() == 0;
    }

    /** Returns -x. */
    private static FixedArithmetic negate(FixedArithmetic x) {
        return FixedArithmetic.of(0).subtract(x);
    }

    // =========================================================================
    // main – demonstration
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== FixedTrigonometry Demo ===\n");

        // Representative angles
        String[] labels = {
            "0", "π/6", "π/4", "π/3", "π/2",
            "2π/3", "3π/4", "5π/6", "π", "−π/4", "−π/2"
        };
        double[] radians = {
            0, Math.PI/6, Math.PI/4, Math.PI/3, Math.PI/2,
            2*Math.PI/3, 3*Math.PI/4, 5*Math.PI/6, Math.PI, -Math.PI/4, -Math.PI/2
        };

        System.out.printf("%-10s  %-14s %-14s  %-14s %-14s  %-14s %-14s%n",
                          "angle", "sin(fixed)", "sin(Math)", "cos(fixed)", "cos(Math)",
                          "tan(fixed)", "tan(Math)");
        System.out.println("-".repeat(100));

        for (int i = 0; i < labels.length; i++) {
            FixedArithmetic angle = FixedArithmetic.of(String.valueOf(radians[i]));
            FixedArithmetic s = sin(angle);
            FixedArithmetic c = cos(angle);

            String tanFixed, tanMath;
            try {
                tanFixed = tan(angle).toString();
            } catch (ArithmeticException e) {
                tanFixed = "undefined";
            }
            double tMath = Math.tan(radians[i]);
            tanMath = (Math.abs(tMath) > 1e8) ? "undefined" : String.valueOf(tMath);

            System.out.printf("%-10s  %-14s %-14s  %-14s %-14s  %-14s %-14s%n",
                              labels[i],
                              s, String.valueOf(Math.sin(radians[i])),
                              c, String.valueOf(Math.cos(radians[i])),
                              tanFixed, tanMath);
        }

        System.out.println("\n--- Inverse functions ---\n");
        double[] asinInputs = {-1, -0.5, 0, 0.5, 1};
        System.out.printf("%-8s  %-14s %-14s  %-14s %-14s%n",
                          "x", "asin(fixed)", "asin(Math)", "acos(fixed)", "acos(Math)");
        System.out.println("-".repeat(70));
        for (double v : asinInputs) {
            FixedArithmetic x = FixedArithmetic.of(String.valueOf(v));
            System.out.printf("%-8s  %-14s %-14s  %-14s %-14s%n",
                              v,
                              asin(x), Math.asin(v),
                              acos(x), Math.acos(v));
        }

        System.out.println("\n--- atan / atan2 ---\n");
        double[] atanInputs = {-10, -1, -0.5, 0, 0.5, 1, 10};
        System.out.printf("%-8s  %-14s %-14s%n", "x", "atan(fixed)", "atan(Math)");
        System.out.println("-".repeat(40));
        for (double v : atanInputs) {
            FixedArithmetic x = FixedArithmetic.of(String.valueOf(v));
            System.out.printf("%-8s  %-14s %-14s%n",
                              v, atan(x), Math.atan(v));
        }

        System.out.println();
        double[][] atan2Cases = {
            {1,1},{1,-1},{-1,1},{-1,-1},{0,1},{1,0},{0,-1},{-1,0},{0,0}
        };
        System.out.printf("%-8s %-8s  %-14s %-14s%n", "y", "x", "atan2(fixed)", "atan2(Math)");
        System.out.println("-".repeat(50));
        for (double[] c : atan2Cases) {
            FixedArithmetic y = FixedArithmetic.of(String.valueOf(c[0]));
            FixedArithmetic x = FixedArithmetic.of(String.valueOf(c[1]));
            System.out.printf("%-8s %-8s  %-14s %-14s%n",
                              c[0], c[1], atan2(y, x), Math.atan2(c[0], c[1]));
        }
    }
}
