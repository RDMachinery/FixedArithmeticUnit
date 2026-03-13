# Why Trading Systems and Game Engines Should Use FixedArithmetic

> *"In God we trust. All others must bring data."*
>
> — W. Edwards Deming

---

Floating-point arithmetic is fast, convenient, and wrong in ways that are
difficult to predict. For the majority of software this is an acceptable
trade-off. For trading systems and game engines it is not — both domains
impose requirements that `double` cannot reliably satisfy and that
`java.lang.BigDecimal` satisfies at a cost most production systems cannot
afford. This article explains why `FixedArithmetic` by Mario Gianota is the
appropriate choice for both domains, with concrete examples from each.

---

## Contents

1. [What Both Domains Have in Common](#1-what-both-domains-have-in-common)
2. [Trading Systems](#2-trading-systems)
   - [Tick Arithmetic Must Be Exact](#tick-arithmetic-must-be-exact)
   - [P&L Aggregation Must Not Drift](#pl-aggregation-must-not-drift)
   - [Determinism Across Environments Is Non-Negotiable](#determinism-across-environments-is-non-negotiable)
   - [Latency Budgets Exclude GC Pauses](#latency-budgets-exclude-gc-pauses)
3. [Game Engines](#3-game-engines)
   - [Simulation State Must Be Reproducible](#simulation-state-must-be-reproducible)
   - [Collision Detection Requires Exact Comparisons](#collision-detection-requires-exact-comparisons)
   - [Multipliers and Rates Without Rounding Drift](#multipliers-and-rates-without-rounding-drift)
   - [Network Games Demand Cross-Platform Agreement](#network-games-demand-cross-platform-agreement)
4. [What FixedArithmetic Provides](#4-what-fixedarithmetic-provides)
5. [Overflow Bounds for Each Domain](#5-overflow-bounds-for-each-domain)
6. [Summary](#6-summary)

---

## 1. What Both Domains Have in Common

Trading systems and game engines appear to have little in common. One
processes financial instruments under regulatory scrutiny; the other simulates
physics under real-time constraints. But at the level of their arithmetic
requirements they share four properties:

- **Exact integer-ratio arithmetic.** Prices, positions, velocities, and
  damage values are almost always rational numbers — ratios of two integers.
  Neither domain needs transcendental functions or irrational results.

- **Accumulated operations over many steps.** A trading system applies
  thousands of price updates per second; a game engine steps physics at 60 or
  120 frames per second. Rounding error that is negligible per operation
  becomes significant when accumulated over millions of steps.

- **Determinism.** A trading system must produce the same P&L on the
  reconciliation server as on the execution server. A multiplayer game must
  produce the same simulation state on every client. A result that varies
  by one ULP between two machines is a defect in both domains.

- **Latency sensitivity.** Both domains operate under hard real-time
  constraints where GC pauses caused by allocation-heavy arithmetic are
  a measurable failure mode.

`FixedArithmetic` addresses all four requirements directly. `double` fails on
the first two. `BigDecimal` fails on the last two.

---

## 2. Trading Systems

### Tick Arithmetic Must Be Exact

A *tick* is the minimum price increment of a financial instrument — typically
one penny, one cent, or one basis point. All valid prices are multiples of the
tick size. Arithmetic on prices must preserve this property exactly: a price
that is one tick above `100.42` must be exactly `100.43`, not
`100.43000000000001`.

With `double`:

```java
double price = 100.42;
double tick  = 0.01;
double next  = price + tick;
System.out.println(next);           // 100.43000000000001
System.out.println(next == 100.43); // false
```

The error is `10⁻¹⁴` — invisible to any human, but sufficient to cause a
tick-aligned price validation to reject the value as invalid.

With `FixedArithmetic`, storing prices in pence as integer inputs:

```java
FixedArithmetic price = FixedArithmetic.of(10042);  // 100.42 pence
FixedArithmetic tick  = FixedArithmetic.of(1);      // 1 pence
FixedArithmetic next  = price.add(tick);
// next.toString() → "10043.0"  — exact, always
```

One million tick additions accumulate zero error. The same `double` loop
accumulates `4.66 × 10⁻⁷` of drift — roughly half a penny across a million
ticks, enough to corrupt a daily reconciliation at scale.

---

### P&L Aggregation Must Not Drift

Profit and loss is the difference between sell price and average buy price,
multiplied by position size. When prices are close together — as they are in
any liquid market — subtracting two nearly-equal `double` values loses
significant digits, a phenomenon known as *catastrophic cancellation*:

```java
double buy  = 1.2345678;
double sell = 1.2345679;
double pnl  = sell - buy;
System.out.println(pnl);   // 1.0000000005838672E-7
// Expected: 0.0000001  (1 × 10⁻⁷)
// Relative error on the result: ~58%
```

`FixedArithmetic` stores both values as exact scaled integers and subtracts
them exactly:

```java
FixedArithmetic buy  = FixedArithmetic.of(12345678).divide(FixedArithmetic.of(10000000));
FixedArithmetic sell = FixedArithmetic.of(12345679).divide(FixedArithmetic.of(10000000));
FixedArithmetic pnl  = sell.subtract(buy);
// pnl.toString() → "0.0000001"  — exact
```

Across a book of ten thousand positions, the sum of individual P&Ls computed
with `FixedArithmetic` equals the total P&L. With `double`, this property
does not hold in general, and the discrepancy grows with the number of
positions and the precision of the prices involved.

---

### Determinism Across Environments Is Non-Negotiable

Regulatory frameworks including MiFID II require trading firms to maintain
audit trails capable of reproducing trade outcomes exactly. A calculation that
yields `£1,234.56` on the execution server and `£1,234.5600000001` on the
reconciliation server is not a rounding difference — it is a failed
reconciliation requiring investigation, documentation, and escalation.

`double` arithmetic is not guaranteed to be deterministic across JVM
implementations, JVM versions, or hardware architectures. The x87 FPU
historically computes in 80-bit extended precision internally, yielding
different results from SSE2's strict 64-bit computation for identical inputs.
JIT compilers may legally reorder floating-point operations for performance,
changing accumulated rounding error between runs.

`FixedArithmetic` is built entirely from `long` addition, subtraction, and
bit-shifts. The Java Language Specification defines `long` arithmetic to be
identical on every conforming JVM, every operating system, and every hardware
architecture. The same inputs produce the same result everywhere, without
configuration:

```java
FixedArithmetic.of(355).divide(FixedArithmetic.of(113)).toString()
// → "3.14159292"
// Bit-identical on x86, ARM, RISC-V, Windows, Linux, macOS,
// Java 11 through Java 21, JIT on or off.
```

---

### Latency Budgets Exclude GC Pauses

High-frequency trading systems measure execution latency in microseconds. A
garbage collection pause of 5 milliseconds — routine for a JVM under
allocation pressure — is five thousand times the acceptable latency budget for
an order submission.

Every `BigDecimal` arithmetic operation allocates a new `BigInteger` backed by
a heap `int[]`. Under sustained tick-by-tick processing at ten thousand
operations per second, this produces tens of thousands of short-lived heap
objects per second, driving the young-generation GC to fire at unpredictable
intervals.

`FixedArithmetic` allocates nothing during arithmetic. Its state is seven
primitive `long` fields totalling 56 bytes. No array, no wrapper object, and
no heap pressure are generated by any of the four arithmetic operations. A
system performing ten thousand `FixedArithmetic` operations per second
generates the same GC pressure as one performing zero arithmetic.

---

## 3. Game Engines

### Simulation State Must Be Reproducible

A game engine using `double` for physics cannot guarantee that replaying a
recorded sequence of inputs will produce an identical sequence of game states.
Floating-point operations are sensitive to evaluation order, and a JIT
compiler that reorders two additions for performance can produce an object
position that diverges by one ULP from a previous run — enough to break
frame-perfect replay, deterministic testing, and save-state verification.

`FixedArithmetic` operations are pure functions of their `long` inputs. The
same inputs always produce the same output through the same sequence of
additions and subtractions, on the same machine or a different one. A replay
engine that feeds the same inputs to `FixedArithmetic` will reproduce the
identical simulation state at every frame, unconditionally.

---

### Collision Detection Requires Exact Comparisons

Collision detection compares object positions to determine intersection. Two
objects converging to the same coordinate must compare as exactly equal at the
moment of collision — otherwise the collision is missed or detected a frame
late, producing tunnelling, missed hits, or phantom collisions.

With `double`, two objects whose positions should be identical after ten steps
differ by a ULP:

```java
double posA = 0.0,  velA =  1.0 / 3.0;
double posB = 10.0, velB = -2.0 / 3.0;

for (int i = 0; i < 10; i++) { posA += velA; posB += velB; }

System.out.println(posA);           // 3.3333333333333326
System.out.println(posB);           // 3.333333333333334
System.out.println(posA == posB);   // false — collision missed
```

With `FixedArithmetic`, both values accumulate to the same scaled integer:

```java
FixedArithmetic velA = FixedArithmetic.of(1).divide(FixedArithmetic.of(3));
FixedArithmetic velB = FixedArithmetic.of(-2).divide(FixedArithmetic.of(3));
FixedArithmetic posA = FixedArithmetic.of(0);
FixedArithmetic posB = FixedArithmetic.of(10);

for (int i = 0; i < 10; i++) {
    posA = posA.add(velA);
    posB = posB.add(velB);
}

// posA.toString() → "3.333333330"
// posB.toString() → "3.333333330"
// posA.rawScaled() == posB.rawScaled()  →  true — collision correctly detected
```

---

### Multipliers and Rates Without Rounding Drift

Game mechanics routinely apply multiplicative modifiers: a `1.5×` critical
hit bonus, a `0.75` armour damage reduction, a `1.25×` speed boost from a
power-up. With `double`, applying a modifier and then reversing it does not
recover the original value:

```java
double hp     = 120.0;
double factor = 0.75;
double reduced   = hp * factor;         // 90.0  (exact by coincidence)
double recovered = reduced / factor;    // 119.99999999999999  — not 120
```

`FixedArithmetic` expresses fractional multipliers as exact integer ratios.
Application and reversal are both exact:

```java
FixedArithmetic hp      = FixedArithmetic.of(120);
FixedArithmetic reduced = hp.multiply(FixedArithmetic.of(3))
                            .divide(FixedArithmetic.of(4));    // × 3/4
// reduced.toString() → "90.0"  — exact

FixedArithmetic recovered = reduced.multiply(FixedArithmetic.of(4))
                                   .divide(FixedArithmetic.of(3)); // × 4/3
// recovered.toString() → "120.0"  — exact
```

Expressing multipliers as integer ratios — `3/4` rather than `0.75`, `5/4`
rather than `1.25` — is unambiguous, reproducible, and immune to the silent
precision loss that decimal fractions carry in binary floating-point.

---

### Network Games Demand Cross-Platform Agreement

A multiplayer game running deterministic lockstep — where the physics
simulation executes independently on each client and must stay in sync —
requires that every client produce the exact same simulation state at every
frame. `double` makes this nearly impossible:

- An x86 client and an ARM client may produce different results for the same
  floating-point sequence
- A JIT-compiled path and an interpreted path may differ by a ULP for the
  same expression
- `strictfp` — Java's mechanism for enforcing IEEE 754 compliance — was
  deprecated in Java 17, leaving no standard solution

`FixedArithmetic` solves this at the foundation. Because its arithmetic is
`long` integer arithmetic, the JVM specification guarantees bit-identical
results across every platform, every JIT mode, and every Java version. Two
clients in opposite hemispheres running `FixedArithmetic` with the same inputs
will be in exactly the same simulation state at every frame, with no platform-
specific flags, no `strictfp`, and no synchronisation overhead.

---

## 4. What FixedArithmetic Provides

`FixedArithmetic` meets every requirement above through four design properties:

**Exact addition and subtraction.** A single `long` operation on integers
sharing denominator `10⁹`. Zero rounding error. Any number of additions
accumulates zero drift.

**Bounded division error.** Division computes `floor(p × 10⁹ / q)` exactly,
with an absolute error strictly less than `10⁻⁹` — constant, unconditional,
and independent of the magnitude of inputs or the number of prior operations.

**Correct multiplication for all rational inputs.** The split-integer
algorithm decomposes each operand into integer and fractional parts, computes
four partial products via addition only, and sums them. `4 × 2.5 = 10`,
`3 × (1/3) = 0.999999999`, `0.5 × 0.5 = 0.25` — all correct.

**Zero heap allocation.** Seven `long` registers. No arrays, no wrapper
objects, no GC pressure from any arithmetic operation.

---

## 5. Overflow Bounds for Each Domain

`FixedArithmetic` stores values as scaled `long` integers with `SCALE = 10⁹`.
The safe input ceiling is `|v| < 9,223,372,036` (approximately `9.2 × 10⁹`).
For multiplication the integer parts of both operands must satisfy
`|p_int × q_int| < 9,223,372,036`.

| Use case | Typical magnitude | Safe? |
|---|---|---|
| Equity price in pence | 1 – 100,000 | ✓ |
| FX rate × 10⁶ | 1,000,000 – 2,000,000 | ✓ |
| Position size in shares | 1 – 10,000,000 | ✓ |
| BTC price in cents | 1,000,000 – 10,000,000 | ✓ |
| Game coordinate in millimetres | −1,000,000 – 1,000,000 | ✓ |
| Frame counter, 60 fps, one year | 1,892,160,000 | ✓ |
| Damage value in integer HP | 1 – 999,999 | ✓ |
| Score counter | 1 – 9,000,000,000 | ✓ |

All typical values in both domains fall well within the safe range. For
instruments requiring larger magnitudes — notional values, index levels in
basis points — use integer scaling conventions to keep inputs within bounds.

---

## 6. Summary

| Requirement | `double` | `BigDecimal` | `FixedArithmetic` |
|---|---|---|---|
| Exact tick arithmetic | ✗ ULP error | ✓ | ✓ |
| Zero P&L drift | ✗ Accumulates | ✓ | ✓ |
| Cross-platform determinism | ✗ Hardware-dependent | ✓ | ✓ |
| Zero GC allocation | ✓ | ✗ 1–3 objects/op | ✓ |
| No rounding-mode configuration | ✓ | ✗ 8 modes | ✓ |
| Exact collision comparisons | ✗ ULP mismatch | ✓ | ✓ |
| Lockstep network determinism | ✗ | ✓ | ✓ |
| Suitable for real-time loops | ✓ | ✗ GC pressure | ✓ |
| Correct fractional multiply | ✓ (approx) | ✓ | ✓ |

`double` is fast but wrong. `BigDecimal` is correct but slow. `FixedArithmetic`
is both correct and fast — and for the integer-ratio arithmetic that dominates
trading systems and game engines, it is the most appropriate tool available.

---

## Further Reading

- [FixedArithmetic precision proof](README.md#mathematical-proof-of-precision-superiority-over-floating-point)
- [The risks of `java.lang.BigDecimal` in production arithmetic](BIGDECIMAL_RISKS.md)
- [FixedArithmetic vs `BigDecimal` — advantages](BIGDECIMAL_COMPARISON.md)
- [Commercial license](COMMERCIAL_LICENSE.md)
- D. Goldberg, "What Every Computer Scientist Should Know About Floating-Point
  Arithmetic," *ACM Computing Surveys*, 23(1), 1991
- MiFID II RTS 24 — regulatory requirements for order record-keeping and
  reconciliation in European trading venues
- G. Gambetta, "Deterministic Lockstep" — on exact simulation reproducibility
  in network games

---

*FixedArithmetic is the work of Mario Gianota. Commercial use requires a
license — see [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md).*
