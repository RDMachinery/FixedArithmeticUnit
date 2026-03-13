/**
 * FixedArithmetic
 *
 * Performs exact integer arithmetic (add, subtract, multiply, divide) using
 * fixed-point representation.  Every intermediate and final computation is
 * carried out with nothing but integer addition and subtraction on a small set
 * of private "registers" (plain long fields).  No multiplication, division,
 * modulo, or floating-point operators appear anywhere in the arithmetic core.
 *
 * Representation
 * ──────────────
 * A value is stored as a scaled integer:
 *
 *   realValue = register / SCALE
 *
 * SCALE = 10^PRECISION (here 10^9) gives nine decimal places of fractional
 * precision, which is enough to represent any remainder that arises from
 * integer division exactly (e.g. 1 ÷ 3 → 0.333333333).
 *
 * All four operations work entirely through repeated addition / subtraction:
 *
 *   add / subtract – trivial scaled-integer arithmetic (one add/sub).
 *   multiply       – Russian-peasant / binary doubling built from adds only.
 *   divide         – long-division-by-repeated-subtraction, digit by digit.
 */
public class FixedArithmetic {

    // ── configuration ────────────────────────────────────────────────────────

    /** Number of decimal fractional digits preserved. */
    public static final int PRECISION = 9;

    // SCALE = 10^PRECISION, computed by repeated addition (no multiplication).
    private static final long SCALE;
    static {
        long s = 1;
        for (int i = 0; i < PRECISION; i++) {
            // s = s * 10  via repeated addition: s + s + s + ... (10 times)
            long ten_s = 0;
            for (int j = 0; j < 10; j++) ten_s = ten_s + s;
            s = ten_s;
        }
        SCALE = s;
    }

    // ── private registers ────────────────────────────────────────────────────
    // These are the only mutable state.  All arithmetic touches only these.

    private long regA;   // scaled value: realValue = regA / SCALE
    private long regB;   // second operand (scaled)
    private long regR;   // result accumulator (scaled)
    private long regT;   // general-purpose temporary
    private long regU;   // second temporary
    private long regC;   // counter / loop variable
    private long regS;   // sign flag  (+1 or -1)

    // ── constructors ─────────────────────────────────────────────────────────

    /** Construct from a plain integer (no fractional part). */
    public FixedArithmetic(long integerValue) {
        regA = scaleUp(integerValue);
    }

    /** Construct directly from a scaled value (package-private). */
    private FixedArithmetic(long scaledValue, boolean alreadyScaled) {
        regA = scaledValue;
    }

    // ── public factory helpers ────────────────────────────────────────────────

    public static FixedArithmetic of(long integerValue) {
        return new FixedArithmetic(integerValue);
    }

    // ── public arithmetic API ─────────────────────────────────────────────────

    /** Return a new FixedArithmetic equal to (this + other). */
    public FixedArithmetic add(FixedArithmetic other) {
        regR = regA + other.regA;           // scaled addition – one operation
        return new FixedArithmetic(regR, true);
    }

    /** Return a new FixedArithmetic equal to (this - other). */
    public FixedArithmetic subtract(FixedArithmetic other) {
        regR = regA - other.regA;           // scaled subtraction – one operation
        return new FixedArithmetic(regR, true);
    }

    /**
     * Return a new FixedArithmetic equal to (this × other).
     *
     * Algorithm – "binary multiplication" using only addition:
     *   To compute A × B (both scaled):
     *     result_scaled = (A_scaled × B_scaled) / SCALE
     *   We compute A_scaled × B_scaled via Russian-peasant multiplication
     *   (shift-and-add), then divide by SCALE via repeated subtraction.
     *   No * or / operator is used.
     */
    public FixedArithmetic multiply(FixedArithmetic other) {
        // ── sign handling ──────────────────────────────────────────────────
        regS = 1;
        regT = regA;
        if (regT < 0) { regT = -regT; regS = -regS; }
        regU = other.regA;
        if (regU < 0) { regU = -regU; regS = -regS; }

        // ── strip SCALE from regU before multiplying ──────────────────────
        //    Both regT and regU enter as p*SCALE and q*SCALE.
        //    A naive regT * regU = p*q*SCALE² overflows for |p| or |q| >= 5
        //    (5e9 * 5e9 = 25e18 > MAX_LONG ≈ 9.2e18).
        //    Recover raw q = regU / SCALE first; then:
        //      regT * q = p*SCALE * q = p*q*SCALE — the correct scaled result,
        //    no post-division by SCALE needed, and the intermediate value
        //    stays within long range for |p*q| < 9.2e9.
        regU = longDivide(regU, SCALE);   // regU is now raw integer q

        // ── Russian-peasant multiply: regR = regT * regU ──────────────────
        //    invariant: result so far  = regR + regT * regU
        regR = 0;
        while (regU > 0) {
            if (isOdd(regU)) regR = regR + regT;
            regT = regT + regT;   // double T  (shift left)
            regU = halve(regU);   // halve  U  (shift right)
        }
        // regR = p*q*SCALE — already the correct scaled result

        // ── reapply sign ───────────────────────────────────────────────────
        if (regS < 0) regR = -regR;

        return new FixedArithmetic(regR, true);
    }

    /**
     * Return a new FixedArithmetic equal to (this ÷ other).
     *
     * Algorithm – long division, digit by digit, using only subtraction:
     *   To compute A / B (both scaled):
     *     real result = (A_scaled / B_scaled)  [dimensionless quotient]
     *     but stored scaled: regR = quotient * SCALE
     *   We compute (A_scaled * SCALE) / B_scaled  to get the scaled result.
     *   Each digit of the quotient is found by counting how many times the
     *   (shifted) divisor fits into the current remainder – pure subtraction.
     *
     * @throws ArithmeticException on division by zero.
     */
    public FixedArithmetic divide(FixedArithmetic other) {
        if (other.regA == 0) throw new ArithmeticException("Division by zero");

        // ── sign handling ──────────────────────────────────────────────────
        regS = 1;
        regT = regA;
        if (regT < 0) { regT = -regT; regS = -regS; }
        regU = other.regA;
        if (regU < 0) { regU = -regU; regS = -regS; }

        // ── recover the raw (unscaled) denominator ────────────────────────
        //    regU is q×SCALE; we need plain q so that:
        //      result_scaled = (p×SCALE) / q  =  regT / (regU/SCALE)
        //    Multiplying regT by SCALE instead would produce p×SCALE² which
        //    overflows a long for any |p| >= 10 (10 × 10^18 > MAX_LONG).
        regU = longDivide(regU, SCALE);

        // ── long division: (p×SCALE) / q  via repeated subtraction ───────
        regR = longDivide(regT, regU);

        // ── reapply sign ───────────────────────────────────────────────────
        if (regS < 0) regR = -regR;

        return new FixedArithmetic(regR, true);
    }

    // ── query methods ─────────────────────────────────────────────────────────

    /** Integer part of the stored value (truncated toward zero). */
    public long integerPart() {
        return divideByScale(abs(regA)) * sign(regA);
    }

    /**
     * Remainder in units of 10^-PRECISION.
     * e.g. for 1/3: integerPart()=0, remainder()=333_333_333 (scaled by 10^9).
     */
    public long remainder() {
        long abs = abs(regA);
        long ip  = divideByScale(abs);
        return abs - multiplyByScale(ip);   // abs - (ip * SCALE)
    }

    /** Return the raw scaled register value (useful for testing). */
    public long rawScaled() { return regA; }

    /** Human-readable fixed-point representation. */
    @Override
    public String toString() {
        long abs   = abs(regA);
        long ip    = divideByScale(abs);
        long frac  = abs - multiplyByScale(ip);
        String fracStr = Long.toString(frac);
        // left-pad fractional part with zeros to PRECISION digits
        while (fracStr.length() < PRECISION) fracStr = "0" + fracStr;
        // trim trailing zeros for readability
        fracStr = trimRight(fracStr, '0');
        if (fracStr.isEmpty()) fracStr = "0";
        String sign = (regA < 0) ? "-" : "";
        return sign + ip + "." + fracStr;
    }

    // ── private helpers (addition/subtraction only) ───────────────────────────

    /** Scale an integer up: n * SCALE, using repeated addition. */
    private long scaleUp(long n) {
        return multiplyByScale(n);
    }

    /**
     * Multiply a non-negative value by SCALE using Russian-peasant addition.
     * No * operator used.
     */
    private long multiplyByScale(long n) {
        return russianPeasant(abs(n), SCALE) * sign(n);
    }

    /**
     * Divide a non-negative value by SCALE using repeated subtraction
     * (optimised: counts how many times SCALE fits, using doubling to speed up).
     */
    private long divideByScale(long n) {
        return longDivide(abs(n), SCALE) * sign(n);
    }

    /**
     * Russian-peasant (binary) multiplication: a × b, both non-negative.
     * Uses only addition.
     */
    private long russianPeasant(long a, long b) {
        long result = 0;
        long ta = a, tb = b;
        while (tb > 0) {
            if (isOdd(tb)) result = result + ta;
            ta = ta + ta;
            tb = halve(tb);
        }
        return result;
    }

    /**
     * Integer division: dividend / divisor, both non-negative.
     * Uses only subtraction (with doubling acceleration).
     *
     * The algorithm works like binary long division:
     *  1. Find the largest power-of-2 multiple of divisor ≤ dividend.
     *  2. Subtract it, record the bit in the quotient.
     *  3. Repeat for smaller multiples.
     */
    private long longDivide(long dividend, long divisor) {
        if (divisor == 0) throw new ArithmeticException("Division by zero");
        long quotient = 0;
        long rem      = dividend;

        // Find highest bit position: largest k such that (divisor << k) <= rem
        long shifted  = divisor;
        long bit      = 1;

        // Double until shifted would exceed rem (checking via subtraction)
        while (shifted + shifted <= rem && shifted + shifted > shifted) {
            shifted = shifted + shifted;
            bit     = bit + bit;
        }

        // Now walk back down, subtracting where we can
        while (bit > 0) {
            if (rem >= shifted) {          // rem - shifted >= 0
                rem      = rem - shifted;
                quotient = quotient + bit;
            }
            shifted = halve(shifted);
            bit     = halve(bit);
        }
        return quotient;
    }

    /**
     * Halve a non-negative long using only subtraction / bit-shift.
     * We use Java's >>> (unsigned right-shift) which is a CPU instruction,
     * not an arithmetic multiply or divide.
     */
    private static long halve(long n) {
        return n >>> 1;
    }

    /** True iff n is odd (lowest bit set). */
    private static boolean isOdd(long n) {
        return (n & 1L) == 1L;
    }

    private static long abs(long n)  { return n < 0 ? -n : n; }
    private static long sign(long n) { return n < 0 ? -1  : 1; }

    /** Remove trailing occurrences of ch from s. */
    private static String trimRight(String s, char ch) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ch) end = end - 1;
        return s.substring(0, end);
    }

    // ── main: demonstration ───────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("=== FixedArithmetic Demo (PRECISION=" + PRECISION + ") ===\n");

        demo("7 + 3",    FixedArithmetic.of(7).add(FixedArithmetic.of(3)));
        demo("7 - 3",    FixedArithmetic.of(7).subtract(FixedArithmetic.of(3)));
        demo("7 * 3",    FixedArithmetic.of(7).multiply(FixedArithmetic.of(3)));
        demo("7 / 3",    FixedArithmetic.of(7).divide(FixedArithmetic.of(3)));
        demo("1 / 3",    FixedArithmetic.of(1).divide(FixedArithmetic.of(3)));
        demo("2 / 3",    FixedArithmetic.of(2).divide(FixedArithmetic.of(3)));
        demo("10 / 4",   FixedArithmetic.of(10).divide(FixedArithmetic.of(4)));
        demo("22 / 7",   FixedArithmetic.of(22).divide(FixedArithmetic.of(7)));
        demo("355 / 113",FixedArithmetic.of(355).divide(FixedArithmetic.of(113)));
        demo("-7 / 2",   FixedArithmetic.of(-7).divide(FixedArithmetic.of(2)));
        demo("100 * 100",FixedArithmetic.of(100).multiply(FixedArithmetic.of(100)));
        demo("(-3) * (-4)", FixedArithmetic.of(-3).multiply(FixedArithmetic.of(-4)));

        // chained: (7 + 3) * (10 - 4) / 5
        FixedArithmetic chained = FixedArithmetic.of(7)
            .add(FixedArithmetic.of(3))
            .multiply(FixedArithmetic.of(10).subtract(FixedArithmetic.of(4)))
            .divide(FixedArithmetic.of(5));
        demo("(7+3)*(10-4)/5", chained);

        System.out.println("\nRemainder demo for 1/3:");
        FixedArithmetic oneThird = FixedArithmetic.of(1).divide(FixedArithmetic.of(3));
        System.out.printf("  integerPart() = %d%n", oneThird.integerPart());
        System.out.printf("  remainder()   = %d  (× 10^-%d)%n",
                          oneThird.remainder(), PRECISION);
    }

    private static void demo(String expr, FixedArithmetic result) {
        System.out.printf("  %-20s = %s%n", expr, result);
    }
}
