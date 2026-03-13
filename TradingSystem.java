import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * TradingSystem
 *
 * A self-contained single-class trading system that:
 *
 *   1. Generates a stream of random stock prices using a seeded random walk.
 *   2. Feeds each price tick through a dual moving-average crossover strategy
 *      (fast MA crosses above slow MA → BUY; crosses below → SELL).
 *   3. Executes all monetary arithmetic — cash balance, position cost,
 *      P&amp;L, commissions — exclusively through {@link FixedArithmetic}.
 *   4. Prints a live tick-by-tick trade log and a final portfolio summary.
 *
 * <h2>Strategy: Dual Simple Moving Average Crossover</h2>
 * <pre>
 *   Fast MA window : FAST_PERIOD ticks  (default  5)
 *   Slow MA window : SLOW_PERIOD ticks  (default 20)
 *
 *   Signal
 *   ──────
 *   BUY  when fastMA crosses above slowMA (golden cross)
 *   SELL when fastMA crosses below slowMA (death cross)
 *        or when a stop-loss of STOP_LOSS_PCT % below entry is hit
 * </pre>
 *
 * <h2>Position sizing</h2>
 * Each buy order uses a fixed fraction (POSITION_SIZE_PCT) of available cash,
 * rounded down to a whole number of shares.
 *
 * <h2>Compile &amp; run</h2>
 * <pre>
 *   javac FixedArithmetic.java TradingSystem.java
 *   java  TradingSystem
 * </pre>
 */
public class TradingSystem {

    // ── strategy parameters ───────────────────────────────────────────────────

    /** Fast moving-average window (ticks). */
    private static final int FAST_PERIOD = 5;

    /** Slow moving-average window (ticks). */
    private static final int SLOW_PERIOD = 20;

    /** Fraction of cash deployed per trade (e.g. "0.25" = 25 %). */
    private static final String POSITION_SIZE_PCT = "0.25";

    /** Stop-loss: sell if price falls this fraction below the entry price. */
    private static final String STOP_LOSS_PCT = "0.05";

    /** Round-trip commission per share as a fixed amount. */
    private static final String COMMISSION_PER_SHARE = "0.01";

    // ── price-stream parameters ───────────────────────────────────────────────

    private static final int    NUM_TICKS       = 200;
    private static final double INITIAL_PRICE   = 100.0;
    private static final double PRICE_VOLATILITY = 0.015;   // std-dev per tick as fraction
    private static final long   RANDOM_SEED      = 42L;

    // ── account ───────────────────────────────────────────────────────────────

    private static final String INITIAL_CASH = "10000.00";

    // ── state ─────────────────────────────────────────────────────────────────

    /** Current cash balance. */
    private FixedArithmetic cash;

    /** Shares currently held (integer, but stored as FixedArithmetic for arithmetic). */
    private FixedArithmetic sharesHeld;

    /** Price paid per share on the last BUY (for stop-loss and P&L). */
    private FixedArithmetic entryPrice;

    /** Total realised profit/loss across all closed trades. */
    private FixedArithmetic realisedPnL;

    /** Total commissions paid. */
    private FixedArithmetic totalCommissions;

    /** Number of completed round-trip trades. */
    private int tradeCount;

    /** Number of profitable trades. */
    private int winCount;

    /** Ring buffer for the fast MA. */
    private final double[] fastBuffer = new double[FAST_PERIOD];
    private int fastCount = 0;

    /** Ring buffer for the slow MA. */
    private final double[] slowBuffer = new double[SLOW_PERIOD];
    private int slowCount = 0;

    /** Previous tick's fast MA − slow MA spread (for crossover detection). */
    private double prevSpread = 0.0;

    /** Whether we are currently long. */
    private boolean inPosition = false;

    /** Tick log entries. */
    private final List<String> log = new ArrayList<>();

    // =========================================================================
    // Constructor
    // =========================================================================

    public TradingSystem() {
        cash             = FixedArithmetic.of(INITIAL_CASH);
        sharesHeld       = FixedArithmetic.of(0);
        entryPrice       = FixedArithmetic.of(0);
        realisedPnL      = FixedArithmetic.of(0);
        totalCommissions = FixedArithmetic.of(0);
        tradeCount       = 0;
        winCount         = 0;
    }

    // =========================================================================
    // Price stream
    // =========================================================================

    /**
     * Generates {@code NUM_TICKS} prices using a multiplicative random walk:
     * <pre>
     *   price_{t+1} = price_t × (1 + ε)   where ε ~ N(0, PRICE_VOLATILITY)
     * </pre>
     * The Gaussian sample is approximated by summing 12 uniform [−0.5, 0.5]
     * random variables (Box–Muller is avoided to keep the class dependency-free).
     */
    private static double[] generatePrices() {
        Random rng = new Random(RANDOM_SEED);
        double[] prices = new double[NUM_TICKS];
        prices[0] = INITIAL_PRICE;
        for (int i = 1; i < NUM_TICKS; i++) {
            // Approximate Gaussian via sum of 12 uniforms (central limit theorem)
            double g = 0.0;
            for (int k = 0; k < 12; k++) g += rng.nextDouble() - 0.5;
            // g is approximately N(0, 1); scale by volatility
            double change = g * PRICE_VOLATILITY;
            prices[i] = prices[i - 1] * (1.0 + change);
            if (prices[i] < 0.01) prices[i] = 0.01;  // floor at 1 cent
        }
        return prices;
    }

    // =========================================================================
    // Moving average helpers
    // =========================================================================

    /** Pushes a new price into a ring buffer and returns the current mean.
     *  Returns Double.NaN if the buffer is not yet full. */
    private static double pushAndAverage(double[] buf, int[] countRef, double price) {
        int count = countRef[0];
        buf[count % buf.length] = price;
        count++;
        countRef[0] = count;
        if (count < buf.length) return Double.NaN;   // not enough data yet
        double sum = 0.0;
        for (double v : buf) sum += v;
        return sum / buf.length;
    }

    // =========================================================================
    // Core tick processor
    // =========================================================================

    /**
     * Processes one price tick: updates MAs, evaluates signals, and executes
     * any resulting BUY or SELL order.  All monetary arithmetic uses
     * {@link FixedArithmetic}.
     *
     * @param tick  tick index (0-based)
     * @param price tick price as a double (converted to FixedArithmetic for trading)
     */
    private void onTick(int tick, double price) {
        // ── update moving averages (plain double for MA computation) ──────────
        int[] fc = {fastCount};
        int[] sc = {slowCount};
        double fastMA = pushAndAverage(fastBuffer, fc, price);
        double slowMA = pushAndAverage(slowBuffer, sc, price);
        fastCount = fc[0];
        slowCount = sc[0];

        boolean maReady = !Double.isNaN(fastMA) && !Double.isNaN(slowMA);
        double  spread  = maReady ? fastMA - slowMA : 0.0;

        // Convert price to FixedArithmetic for all order arithmetic
        FixedArithmetic fPrice = priceToFixed(price);

        // ── stop-loss check (evaluated before new signal) ─────────────────────
        if (inPosition) {
            // stopLevel = entryPrice × (1 − STOP_LOSS_PCT)
            FixedArithmetic stopLevel = entryPrice.multiply(
                FixedArithmetic.of("1").subtract(FixedArithmetic.of(STOP_LOSS_PCT)));
            if (isLessThan(fPrice, stopLevel)) {
                executeSell(tick, fPrice, "STOP-LOSS");
            }
        }

        // ── MA crossover signal ───────────────────────────────────────────────
        if (maReady) {
            boolean goldenCross = prevSpread <= 0 && spread > 0;
            boolean deathCross  = prevSpread >= 0 && spread < 0;

            if (goldenCross && !inPosition) {
                executeBuy(tick, fPrice);
            } else if (deathCross && inPosition) {
                executeSell(tick, fPrice, "MA-CROSS");
            }
            prevSpread = spread;
        }

        // ── tick log line ─────────────────────────────────────────────────────
        String maStr = maReady
            ? String.format("fast=%7.3f slow=%7.3f", fastMA, slowMA)
            : String.format("%-29s", "(warming up)");
        log.add(String.format("tick %3d  price=%8s  %s  cash=%11s  shares=%3d%s",
            tick + 1,
            fmt(fPrice),
            maStr,
            fmt(cash),
            sharesHeld.integerPart(),
            inPosition ? "  [LONG]" : ""));
    }

    // =========================================================================
    // Order execution  (all monetary arithmetic via FixedArithmetic)
    // =========================================================================

    /**
     * Buys as many whole shares as POSITION_SIZE_PCT of cash will purchase.
     *
     * <pre>
     *   budget    = cash × POSITION_SIZE_PCT
     *   shares    = floor(budget / price)
     *   cost      = shares × price
     *   commission = shares × COMMISSION_PER_SHARE
     *   cash      -= (cost + commission)
     * </pre>
     */
    private void executeBuy(int tick, FixedArithmetic price) {
        FixedArithmetic budget  = cash.multiply(FixedArithmetic.of(POSITION_SIZE_PCT));
        long            shares  = budget.divide(price).integerPart();
        if (shares <= 0) {
            log.add(String.format("  >>> tick %3d  BUY skipped — insufficient funds", tick + 1));
            return;
        }

        FixedArithmetic fShares    = FixedArithmetic.of(shares);
        FixedArithmetic cost       = price.multiply(fShares);
        FixedArithmetic commission = FixedArithmetic.of(COMMISSION_PER_SHARE).multiply(fShares);
        FixedArithmetic total      = cost.add(commission);

        cash             = cash.subtract(total);
        sharesHeld       = fShares;
        entryPrice       = price;
        totalCommissions = totalCommissions.add(commission);
        inPosition       = true;

        log.add(String.format(
            "  >>> BUY  %3d shares @ %s  cost=%s  comm=%s  cash after=%s",
            shares, fmt(price), fmt(cost), fmt(commission), fmt(cash)));
    }

    /**
     * Sells all held shares.
     *
     * <pre>
     *   proceeds   = shares × price
     *   commission = shares × COMMISSION_PER_SHARE
     *   net        = proceeds − commission
     *   cash       += net
     *   tradePnL   = net − entryPrice × shares
     * </pre>
     */
    private void executeSell(int tick, FixedArithmetic price, String reason) {
        if (!inPosition) return;

        FixedArithmetic fShares    = sharesHeld;
        FixedArithmetic proceeds   = price.multiply(fShares);
        FixedArithmetic commission = FixedArithmetic.of(COMMISSION_PER_SHARE).multiply(fShares);
        FixedArithmetic net        = proceeds.subtract(commission);
        FixedArithmetic entryCost  = entryPrice.multiply(fShares);
        FixedArithmetic tradePnL   = net.subtract(entryCost);

        cash             = cash.add(net);
        realisedPnL      = realisedPnL.add(tradePnL);
        totalCommissions = totalCommissions.add(commission);
        tradeCount++;
        if (tradePnL.rawScaled() > 0) winCount++;

        String pnlTag = tradePnL.rawScaled() >= 0 ? "PROFIT" : "LOSS";
        log.add(String.format(
            "  <<< SELL %3d shares @ %s  proceeds=%s  comm=%s  P&L=%s  [%s/%s]",
            fShares.integerPart(), fmt(price), fmt(proceeds),
            fmt(commission), fmt(tradePnL), reason, pnlTag));

        sharesHeld  = FixedArithmetic.of(0);
        entryPrice  = FixedArithmetic.of(0);
        inPosition  = false;
    }

    // =========================================================================
    // Run
    // =========================================================================

    /** Runs the full simulation over the generated price stream. */
    public void run() {
        double[] prices = generatePrices();

        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║               F I X E D - P O I N T   T R A D I N G                 ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.printf("%n  Strategy : Dual SMA crossover  (fast=%d / slow=%d ticks)%n", FAST_PERIOD, SLOW_PERIOD);
        System.out.printf("  Position : %.0f%% of cash per trade%n", Double.parseDouble(POSITION_SIZE_PCT) * 100);
        System.out.printf("  Stop-loss: %.0f%% below entry%n", Double.parseDouble(STOP_LOSS_PCT) * 100);
        System.out.printf("  Capital  : %s%n", INITIAL_CASH);
        System.out.printf("  Ticks    : %d%n%n", NUM_TICKS);
        System.out.println("─".repeat(72));

        for (int t = 0; t < prices.length; t++) {
            onTick(t, prices[t]);
        }

        // Close any open position at the last price
        if (inPosition) {
            FixedArithmetic lastPrice = priceToFixed(prices[prices.length - 1]);
            executeSell(prices.length - 1, lastPrice, "END-OF-STREAM");
        }

        // Print log
        for (String line : log) System.out.println(line);

        // ── Summary ───────────────────────────────────────────────────────────
        FixedArithmetic finalEquity = cash;   // all positions closed
        FixedArithmetic totalReturn = finalEquity.subtract(FixedArithmetic.of(INITIAL_CASH));
        FixedArithmetic returnPct   = totalReturn
            .divide(FixedArithmetic.of(INITIAL_CASH))
            .multiply(FixedArithmetic.of(100));

        System.out.println("\n" + "─".repeat(72));
        System.out.println("  FINAL SUMMARY");
        System.out.println("─".repeat(72));
        System.out.printf("  Starting capital   : %s%n", INITIAL_CASH);
        System.out.printf("  Final equity       : %s%n", fmt(finalEquity));
        System.out.printf("  Total return       : %s  (%s%%)%n", fmt(totalReturn), fmt(returnPct));
        System.out.printf("  Realised P&L       : %s%n", fmt(realisedPnL));
        System.out.printf("  Total commissions  : %s%n", fmt(totalCommissions));
        System.out.printf("  Completed trades   : %d%n", tradeCount);
        System.out.printf("  Winning trades     : %d / %d%n", winCount, tradeCount);
        if (tradeCount > 0) {
            FixedArithmetic winRate = FixedArithmetic.of(winCount)
                .divide(FixedArithmetic.of(tradeCount))
                .multiply(FixedArithmetic.of(100));
            System.out.printf("  Win rate           : %s%%%n", fmt(winRate));
        }
        System.out.println("─".repeat(72));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Converts a double price to {@link FixedArithmetic} by rounding to 4
     * decimal places and constructing from a decimal string — the only
     * double→fixed bridge in the entire system.
     */
    private static FixedArithmetic priceToFixed(double price) {
        // Format to 4 decimal places then parse with FixedArithmetic.of(String).
        // This is the single controlled entry point where double meets fixed-point.
        long cents10k = Math.round(price * 10_000.0);  // round to 4dp
        long intPart  = cents10k / 10_000;
        long fracPart = cents10k % 10_000;
        // Build decimal string without String.format to minimise float involvement
        String s = intPart + "." + pad4(fracPart);
        return FixedArithmetic.of(s);
    }

    /** Zero-pads a number to 4 digits. */
    private static String pad4(long n) {
        String s = Long.toString(n);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    /**
     * Formats a {@link FixedArithmetic} value as a currency string with 2dp,
     * using only {@code integerPart()}, {@code remainder()}, and
     * {@code rawScaled()} — no floating-point conversion.
     */
    private static String fmt(FixedArithmetic v) {
        boolean neg  = v.rawScaled() < 0;
        long    ip   = Math.abs(v.integerPart());
        long    rem  = v.remainder();                  // always non-negative
        // Extract 2dp from the 9dp remainder: divide by 10^7
        long    dp2  = rem / 10_000_000L;
        // Round the 2dp figure based on the 3rd decimal place
        long    dp3  = (rem / 1_000_000L) % 10;
        if (dp3 >= 5) dp2++;
        if (dp2 >= 100) { ip++; dp2 = 0; }
        String cents = dp2 < 10 ? "0" + dp2 : Long.toString(dp2);
        return (neg ? "-" : "") + ip + "." + cents;
    }

    /** True if a < b using rawScaled(). */
    private static boolean isLessThan(FixedArithmetic a, FixedArithmetic b) {
        return a.rawScaled() < b.rawScaled();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        new TradingSystem().run();
    }
}
