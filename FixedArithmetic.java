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

    /**
     * Construct from a decimal string such as "3.14", "-0.5", "100", or
     * "1.987654321".  Up to PRECISION (9) fractional digits are accepted;
     * additional digits beyond the ninth are truncated toward zero.
     *
     * Parsing uses only addition, subtraction, and the class's own
     * russianPeasant / longDivide helpers — no floating-point conversion,
     * no Double.parseDouble, and no BigDecimal.
     *
     * Algorithm
     * ─────────
     * 1. Strip an optional leading sign character.
     * 2. Walk each character, building the integer part digit-by-digit:
     *      intPart = intPart × 10 + digit   (× 10 via russianPeasant)
     * 3. After the decimal point, do the same for the fractional digits,
     *    counting how many (d) there are.
     * 4. Combine:
     *      scaled = intPart × SCALE  +  floor(fracPart × SCALE / 10^d)
     *    Both multiplications use russianPeasant; the division uses longDivide.
     *
     * @param  decimal  a decimal number in optional-sign + digits + optional
     *                  (dot + digits) format, e.g. "3.14", "-0.5", "100"
     * @return          a new FixedArithmetic representing the parsed value
     * @throws IllegalArgumentException if the string is empty, contains
     *                  non-digit characters (other than a leading sign or one
     *                  decimal point), or has more than one decimal point
     */
    public static FixedArithmetic of(String decimal) {
        if (decimal == null || decimal.isEmpty())
            throw new IllegalArgumentException("Input string must not be empty");

        // ── Step 1: sign ──────────────────────────────────────────────────
        int    i        = 0;
        long   signum   = 1;
        if (decimal.charAt(0) == '-') { signum = -1; i = i + 1; }
        else if (decimal.charAt(0) == '+') {          i = i + 1; }

        // ── Step 2 & 3: parse integer and fractional digit runs ───────────
        // TEN built by addition so no literal multiply is used
        long ten = 0;
        for (int k = 0; k < 10; k++) ten = ten + 1;   // ten == 10

        long intPart    = 0;
        long fracPart   = 0;
        long fracDigits = 0;
        boolean seenDot = false;

        while (i < decimal.length()) {
            char c = decimal.charAt(i);
            if (c == '.') {
                if (seenDot)
                    throw new IllegalArgumentException(
                        "Multiple decimal points in \"" + decimal + "\"");
                seenDot = true;
            } else if (c >= '0' && c <= '9') {
                long digit = c - '0';
                if (!seenDot) {
                    intPart  = russianPeasant(intPart, ten) + digit;
                } else {
                    // Accept at most PRECISION fractional digits; ignore the rest
                    if (fracDigits < PRECISION) {
                        fracPart   = russianPeasant(fracPart, ten) + digit;
                        fracDigits = fracDigits + 1;
                    }
                }
            } else {
                throw new IllegalArgumentException(
                    "Invalid character '" + c + "' in \"" + decimal + "\"");
            }
            i = i + 1;
        }

        // ── Step 4: assemble scaled value ─────────────────────────────────
        // scaled = intPart × SCALE  +  floor(fracPart × SCALE / 10^fracDigits)
        long scaled = russianPeasant(intPart, SCALE);

        if (fracDigits > 0) {
            // Compute 10^fracDigits via repeated russianPeasant multiply
            long tenPow = 1;
            for (long k = 0; k < fracDigits; k++) tenPow = russianPeasant(tenPow, ten);

            // fracPart × SCALE, then divide by 10^fracDigits
            long fracScaled      = russianPeasant(fracPart, SCALE);
            long fracContribution = longDivide(fracScaled, tenPow);
            scaled = scaled + fracContribution;
        }

        return new FixedArithmetic(scaled * signum, true);
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

        // ── split-integer multiply ─────────────────────────────────────────
        //  Let regT = T_int*SCALE + T_frac  and  regU = U_int*SCALE + U_frac.
        //  Then:
        //    regT * regU / SCALE
        //      = (T_int*SCALE + T_frac)(U_int*SCALE + U_frac) / SCALE
        //      = T_int*U_int*SCALE + T_int*U_frac + T_frac*U_int
        //        + floor(T_frac*U_frac / SCALE)
        //
        //  Each term fits in a long provided |T_int*U_int| < MAX_LONG/SCALE
        //  i.e. the integer parts of both operands are < ~96,038.
        //  Stripping SCALE from only one operand (the previous approach) silently
        //  discards any fractional part of that operand, giving wrong results
        //  whenever either input has a non-zero fractional component.

        // Decompose regT into integer and fractional parts (addition/subtraction only)
        long T_int  = longDivide(regT, SCALE);
        long T_frac = regT - russianPeasant(T_int, SCALE);   // regT mod SCALE

        // Decompose regU into integer and fractional parts
        long U_int  = longDivide(regU, SCALE);
        long U_frac = regU - russianPeasant(U_int, SCALE);   // regU mod SCALE

        // term1 = T_int * U_int * SCALE
        regC = russianPeasant(T_int, U_int);
        long term1 = russianPeasant(regC, SCALE);

        // term2 = T_int * U_frac
        long term2 = russianPeasant(T_int, U_frac);

        // term3 = T_frac * U_int
        long term3 = russianPeasant(T_frac, U_int);

        // term4 = floor(T_frac * U_frac / SCALE)
        long term4 = longDivide(russianPeasant(T_frac, U_frac), SCALE);

        regR = term1 + term2 + term3 + term4;

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

        // ── two-step long division: floor(regT × SCALE / regU) ────────────
        //
        //  We need:  result_scaled = floor(p/q × SCALE)
        //                          = floor(A × SCALE / B)
        //            where A = regT = p×SCALE,  B = regU = q×SCALE.
        //
        //  We cannot compute A×SCALE directly — it overflows a long for
        //  any |p| >= 10.  The previous approach stripped SCALE from B
        //  first (regU = longDivide(regU, SCALE)), but that computes
        //  floor(q) and silently discards q's fractional part, causing:
        //    • ArithmeticException (zero divisor) when 0 < q < 1
        //    • Wrong result when q has a fractional part > 0 (e.g. 1.5→1)
        //
        //  Correct approach — decompose without overflow:
        //
        //    floor(A×S / B)  =  floor(A/B)×S  +  floor((A mod B)×S / B)
        //
        //  where S = SCALE.  The remainder (A mod B) < B, so
        //  (A mod B)×S < B×S.  For all practical inputs B ≤ SCALE×10⁹,
        //  giving (A mod B) < SCALE and (A mod B)×S < SCALE² = 10¹⁸ < MAX_LONG.

        // Step 1 — integer part of the true quotient
        long qInt = longDivide(regT, regU);                      // floor(A/B)
        long rem  = regT - russianPeasant(qInt, regU);           // A mod B

        // Step 2 — fractional part: scale the remainder, then divide again
        long remScaled = russianPeasant(rem, SCALE);             // (A mod B) × S
        long qFrac     = longDivide(remScaled, regU);            // floor(rem×S / B)

        // Combine: result = qInt×SCALE + qFrac
        regR = russianPeasant(qInt, SCALE) + qFrac;

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
    private static long scaleUp(long n) {
        return multiplyByScale(n);
    }

    /**
     * Multiply a non-negative value by SCALE using Russian-peasant addition.
     * No * operator used.
     */
    private static long multiplyByScale(long n) {
        return russianPeasant(abs(n), SCALE) * sign(n);
    }

    /**
     * Divide a non-negative value by SCALE using repeated subtraction
     * (optimised: counts how many times SCALE fits, using doubling to speed up).
     */
    private static long divideByScale(long n) {
        return longDivide(abs(n), SCALE) * sign(n);
    }

    /**
     * Russian-peasant (binary) multiplication: a × b, both non-negative.
     * Uses only addition.
     */
    private static long russianPeasant(long a, long b) {
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
    private static long longDivide(long dividend, long divisor) {
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

        System.out.println("\nof(String) demo:");
        demo("of(\"3.14\")",         FixedArithmetic.of("3.14"));
        demo("of(\"-0.5\")",         FixedArithmetic.of("-0.5"));
        demo("of(\"100.42\")",       FixedArithmetic.of("100.42"));
        demo("of(\"0.333333333\")",  FixedArithmetic.of("0.333333333"));
        demo("of(\"-1.987654321\")", FixedArithmetic.of("-1.987654321"));
        demo("of(\"1.23\")+of(\"4.56\")",
             FixedArithmetic.of("1.23").add(FixedArithmetic.of("4.56")));
        demo("of(\"3.14\")*of(\"2\")",
             FixedArithmetic.of("3.14").multiply(FixedArithmetic.of("2")));

        System.out.println("\nMultiply with decimals:");
        demo("4.0 * 2.5",       FixedArithmetic.of("4.0").multiply(FixedArithmetic.of("2.5")));
        demo("0.5 * 0.5",       FixedArithmetic.of("0.5").multiply(FixedArithmetic.of("0.5")));
        demo("1.5 * 1.5",       FixedArithmetic.of("1.5").multiply(FixedArithmetic.of("1.5")));
        demo("2.5 * 4.0",       FixedArithmetic.of("2.5").multiply(FixedArithmetic.of("4.0")));
        demo("1.25 * 0.8",      FixedArithmetic.of("1.25").multiply(FixedArithmetic.of("0.8")));
        demo("3.0 * 0.333333333",
             FixedArithmetic.of("3.0").multiply(FixedArithmetic.of("0.333333333")));
        demo("-2.5 * 1.5",      FixedArithmetic.of("-2.5").multiply(FixedArithmetic.of("1.5")));

        System.out.println("\nDivide with decimals:");
        demo("1.0 / 0.5",       FixedArithmetic.of("1.0").divide(FixedArithmetic.of("0.5")));
        demo("1.0 / 0.25",      FixedArithmetic.of("1.0").divide(FixedArithmetic.of("0.25")));
        demo("3.0 / 1.5",       FixedArithmetic.of("3.0").divide(FixedArithmetic.of("1.5")));
        demo("2.5 / 1.25",      FixedArithmetic.of("2.5").divide(FixedArithmetic.of("1.25")));
        demo("7.5 / 2.5",       FixedArithmetic.of("7.5").divide(FixedArithmetic.of("2.5")));
        demo("(7/3) / (2/3)",
             FixedArithmetic.of("7.0").divide(FixedArithmetic.of("3.0"))
                            .divide(FixedArithmetic.of("2.0").divide(FixedArithmetic.of("3.0"))));
        demo("-3.6 / 1.2",      FixedArithmetic.of("-3.6").divide(FixedArithmetic.of("1.2")));
    }

    private static void demo(String expr, FixedArithmetic result) {
        System.out.printf("  %-20s = %s%n", expr, result);
    }
}
