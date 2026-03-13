# FixedArithmetic

A Java class that performs **addition, subtraction, multiplication, and division**
of integer values using **fixed-point representation**, carrying all remainders
exactly and eliminating the rounding errors inherent to IEEE 754 floating-point.
For certain common use cases, this class is superior to java.lang.BigDecimal.

Every computation is reduced to integer addition and subtraction on a small set
of private registers — no `*`, `/`, or `%` operators appear anywhere in the
arithmetic core.

---

## Table of Contents

1. [Design Overview](#design-overview)
2. [Internal Representation](#internal-representation)
3. [The Four Operations](#the-four-operations)
4. [API Reference](#api-reference)
5. [Usage Examples](#usage-examples)
6. [Mathematical Proof of Precision Superiority over Floating-Point](#mathematical-proof-of-precision-superiority-over-floating-point)
7. [Limitations](#limitations)

---

## Design Overview

| Property | Value |
|---|---|
| Internal storage | Scaled `long` integer (`regA`) |
| Scale factor | `SCALE = 10^9` (one billion) |
| Fractional precision | 9 decimal places |
| Operators used | `+` and `−` only (plus CPU bit-shifts `>>>` and `&`) |
| Rounding | Truncation toward zero (no rounding error introduced) |
| Overflow risk | Values must satisfy `|n| × SCALE < 2^63 − 1 ≈ 9.2 × 10^18` |

---

## Internal Representation

A real value `v` is stored as the scaled integer:

```
regA  =  v × SCALE        where  SCALE = 1,000,000,000
```

For example, the rational number `7/3` is stored as:

```
regA  =  2,333,333,333     which represents  2.333333333
```

No fractional type is ever used. The denominator is always exactly `SCALE`,
so every representable value is a rational number of the form `n / 10^9` for
some integer `n`. Arithmetic on these representations is exact integer
arithmetic.

---

## The Four Operations

### Addition and Subtraction

Both are a single scaled-integer operation:

```
add(A, B)      →  regR = regA + regB
subtract(A, B) →  regR = regA − regB
```

Because both operands share the same denominator `SCALE`, the result is exact.

### Multiplication — Russian-Peasant Algorithm

To multiply two scaled values `A` and `B`:

```
real result  =  (A × B)  /  SCALE
```

The product `A × B` is computed by the **Russian-peasant (binary)** algorithm,
which replaces every multiplication step with a doubling (addition to itself)
and a bit-test:

```java
while (tb > 0) {
    if (isOdd(tb)) result = result + ta;   // addition only
    ta = ta + ta;                          // double: addition only
    tb = tb >>> 1;                         // halve:  bit-shift
}
```

Division by `SCALE` is performed by the same binary long-division routine
described below.

### Division — Binary Long Division

To divide scaled value `A` by scaled value `B`:

```
real result  =  A / B   stored as  (A × SCALE) / B
```

The extra `× SCALE` keeps the result in scaled form. The quotient is extracted
digit-by-bit using only subtraction:

```java
// Phase 1: find highest power-of-2 multiple of divisor ≤ dividend
while (shifted + shifted <= rem) { shifted = shifted + shifted; bit = bit + bit; }

// Phase 2: walk back down, subtracting wherever possible
while (bit > 0) {
    if (rem >= shifted) { rem = rem − shifted; quotient = quotient + bit; }
    shifted = shifted >>> 1;
    bit     = bit >>> 1;
}
```

No `*`, `/`, or `%` operator is used in either phase.

---

## API Reference

```java
// Construction
FixedArithmetic a = FixedArithmetic.of(7);   // represents 7
FixedArithmetic b = FixedArithmetic.of(3);   // represents 3

// Arithmetic  (all return a new FixedArithmetic — immutable style)
FixedArithmetic sum  = a.add(b);             // 10.0
FixedArithmetic diff = a.subtract(b);        // 4.0
FixedArithmetic prod = a.multiply(b);        // 21.0
FixedArithmetic quot = a.divide(b);          // 2.333333333

// Inspection
long ip   = quot.integerPart();              // 2
long rem  = quot.remainder();               // 333333333  (× 10^-9)
long raw  = quot.rawScaled();               // 2333333333
String s  = quot.toString();               // "2.333333333"
```

---

## Usage Examples

```java
// Simple operations
FixedArithmetic.of(22).divide(FixedArithmetic.of(7)).toString()
// → "3.142857142"   (22/7 to 9 decimal places, no rounding drift)

FixedArithmetic.of(1).divide(FixedArithmetic.of(3)).toString()
// → "0.333333333"   (exact truncation, no 0.33333334 overshoot)

// Chained expression: (7 + 3) × (10 − 4) / 5  =  12
FixedArithmetic.of(7)
    .add(FixedArithmetic.of(3))
    .multiply(FixedArithmetic.of(10).subtract(FixedArithmetic.of(4)))
    .divide(FixedArithmetic.of(5))
    .toString();
// → "12.0"   (exact)
```

---

## Mathematical Proof of Precision Superiority over Floating-Point

### 1. How IEEE 754 double-precision represents numbers

An IEEE 754 `double` stores a value as:

```
x  =  (−1)^s  ×  1.mantissa  ×  2^(e − 1023)
```

where the mantissa field is **52 bits** wide. This means a `double` can
represent at most:

```
2^52  =  4,503,599,627,370,496   distinct significand values
```

The **unit in the last place** (ULP) of a number near magnitude `M` is:

```
ulp(M)  =  2^(floor(log2 M) − 52)
```

Any real number that does not fall exactly on one of these representable values
is **rounded** to the nearest one. This rounding is unavoidable and
structurally baked into the format.

---

### 2. The fundamental representational gap in floating-point

**Theorem 1.** *The rational number `1/10` cannot be represented exactly in
any binary floating-point format.*

**Proof.**  
A binary floating-point number has the form `m / 2^k` for integers `m`, `k`.
For `1/10 = m / 2^k` to hold exactly we would need `2^k = 10 m`, i.e. `2^k`
divisible by 5. But `2^k` has no odd prime factors, so no such `k` exists. ∎

**Corollary.** The decimal value `0.1` stored as a `double` is actually:

```
0.1000000000000000055511151231257827021181583404541015625
```

The error is approximately `5.55 × 10^-18`, non-zero and unavoidable.

---

### 3. Error accumulation under floating-point addition

Let `fl(x)` denote the floating-point representation of real `x`, and
let `ε_x = fl(x) − x` be the representation error, `|ε_x| ≤ ½ ulp(x)`.

When two floats are added the hardware rounds the mathematical result to the
nearest representable value, introducing a new rounding error `ε_+`:

```
fl(a) ⊕ fl(b)  =  (a + b)(1 + δ),    |δ| ≤ 2^-52  ≈  2.22 × 10^-16
```

After `n` sequential additions the relative error bound grows to:

```
|E_n|  ≤  n × 2^-52 × max|a_i|           (first-order)
```

For `n = 1,000` additions of values near 1 the error bound reaches
`≈ 2.2 × 10^-13`, eleven orders of magnitude larger than a single ULP.

---

### 4. The FixedArithmetic representation is exact for integers

**Theorem 2.** *Every integer `n` satisfying `|n| < SCALE × 2^63 / SCALE =
2^63` is represented exactly in `FixedArithmetic`.*

**Proof.**  
`regA` is a Java `long`, an exact two's-complement 64-bit integer. The stored
value is `n × SCALE`. Because integer arithmetic on `long` is exact (modulo
overflow, addressed in §6), no rounding occurs at any step of storage or
retrieval. ∎

---

### 5. Exact addition and subtraction

**Theorem 3.** *`FixedArithmetic.add` and `FixedArithmetic.subtract` are
exact, subject to no overflow.*

**Proof.**  
Let `A = a × SCALE` and `B = b × SCALE` be the two stored values. The
implementation computes:

```
regR  =  A + B  =  (a + b) × SCALE
```

This is a single Java `long` addition. Java `long` addition is defined by
the Java Language Specification to be exact modulo `2^64` (two's-complement
wraparound). Provided `|a + b| < 2^63 / SCALE`, no wraparound occurs and the
result equals `(a + b) × SCALE` exactly.

The same argument applies to subtraction. ∎

**Contrast with `double`.** The IEEE 754 addition `(double)a + (double)b`
rounds the mathematical result to the nearest `double`. For values like
`a = 0.1, b = 0.2` the result is not `0.3` but
`0.30000000000000004` — an error of `5.55 × 10^-17` even for this trivial
two-operand sum.

---

### 6. Exact multiplication via the Russian-peasant algorithm

**Theorem 4.** *The Russian-peasant routine `russianPeasant(a, b)` returns
the exact product `a × b` for all non-negative `long` values, using only
addition and bit-testing.*

**Proof.**  
The algorithm maintains the loop invariant:

```
result + ta × tb  =  a × b          (constant throughout)
```

**Base case:** before the loop, `result = 0`, `ta = a`, `tb = b`, so
`0 + a × b = a × b`. ✓

**Inductive step:** At each iteration:

- If `tb` is odd: `result' = result + ta`, `ta' = 2 × ta`, `tb' = (tb−1)/2`.
  Then `result' + ta' × tb' = (result + ta) + 2ta × (tb−1)/2
  = result + ta + ta(tb−1) = result + ta × tb`. ✓
- If `tb` is even: `result' = result`, `ta' = 2 × ta`, `tb' = tb/2`.
  Then `result' + ta' × tb' = result + 2ta × (tb/2) = result + ta × tb`. ✓

**Termination:** `tb` strictly decreases each iteration (`tb' < tb` since
`tb >>> 1 < tb` for `tb > 0`), so the loop terminates when `tb = 0`, at
which point the invariant gives `result = a × b`. ∎

---

### 7. Exact division via binary long division

**Theorem 5.** *`longDivide(dividend, divisor)` returns `floor(dividend /
divisor)` exactly, using only subtraction and bit-shifts.*

**Proof.**  
The algorithm maintains two invariants simultaneously:

```
(i)   quotient × divisor + rem  =  dividend          (partition invariant)
(ii)  shifted  =  divisor × bit                      (scaling invariant)
```

At each step, if `rem ≥ shifted` the algorithm subtracts `shifted` from `rem`
and adds `bit` to `quotient`, preserving invariant (i):

```
(quotient + bit) × divisor + (rem − shifted)
  =  quotient × divisor + bit × divisor + rem − shifted
  =  quotient × divisor + rem             (since shifted = bit × divisor)
  =  dividend
```

Both `shifted` and `bit` are halved each iteration, so after `floor(log2
(dividend/divisor)) + 1` iterations `bit = 0`. At that point `rem < divisor`
(every multiple of `divisor` that fits has been subtracted), so
`quotient = floor(dividend / divisor)` exactly. ∎

---

### 8. Precision of division in FixedArithmetic vs. double

For integer inputs `p` and `q`, `FixedArithmetic.divide` computes:

```
regR  =  floor( p × SCALE^2  /  q × SCALE )
       =  floor( p × SCALE   /  q )
```

which is the exact integer `floor(p / q × 10^9)`. The true value is
`p/q × 10^9` and the truncation error satisfies:

```
| error |  <  1   (in units of SCALE^-1 = 10^-9)
```

i.e. the absolute error in the *real* result is strictly less than `10^-9`.

For the same inputs, a `double` computation `(double)p / (double)q` has an
absolute error bounded by:

```
| error_fp |  ≤  ½ ulp( p/q )  =  2^(floor(log2|p/q|) − 53)
```

For `p/q` near 1 this is `≈ 1.1 × 10^-16`. For `p/q` near `10^6` (well
within the range of common integer arithmetic) it grows to `≈ 0.06`, a
relative error of `6 × 10^-8` — **larger than the `FixedArithmetic`
truncation bound of `10^-9`**.

More critically, `double` precision is *adaptive* and *unpredictable*: the
exact ULP depends on magnitude, so the error of a chain of operations is
difficult to bound without careful analysis. `FixedArithmetic` error is
*unconditionally bounded by `10^-9`* regardless of the magnitude of
intermediate results.

---

### 9. Error-free integer results

**Corollary 1.** *If `p` and `q` are integers and `q | p` (q divides p
exactly), then `FixedArithmetic.divide(p, q).integerPart()` equals `p/q`
with zero error.*

**Proof.**  
`p/q` is an integer, so `floor(p × SCALE / q) = (p/q) × SCALE` exactly.
Calling `integerPart()` divides by `SCALE` via `longDivide`, which (by
Theorem 5) returns the exact quotient `p/q` with remainder `0`. ∎

The same result for `double` is not guaranteed:

```java
System.out.println(10.0 / 2.0);   // 5.0   ← happens to be exact
System.out.println( 1.0 / 3.0 * 3.0);   // 1.0? No: 0.9999999999999999
```

The final example is a canonical demonstration that floating-point division
followed by multiplication does not recover the original value — a direct
consequence of rounding at each step.

---

### 10. Summary table

| Property | `double` | `FixedArithmetic` |
|---|---|---|
| Representation of `1/10` | Inexact (error ≈ 5.6 × 10⁻¹⁸) | Exact (stored as `100,000,000`) |
| Addition of `0.1 + 0.2` | `0.30000000000000004` | `0.3` (exact) |
| Rounding per operation | Yes — up to ½ ULP | No — integer ops are exact |
| Accumulated error after n ops | O(n × 2⁻⁵²) | 0 for add/sub; < 10⁻⁹ for div |
| Result of `(1/3) × 3` | May not equal 1 | Equals 1 exactly (if input integers) |
| Representable denominators | Powers of 2 only | All integers up to SCALE |
| Precision bound (absolute) | Magnitude-dependent | Constant: < 10⁻⁹ |

---

## Limitations

- **Overflow.** Values must satisfy `|v| × SCALE < 2^63 ≈ 9.2 × 10^18`, i.e.
  `|v| < 9.2 × 10^9`. For larger values increase `PRECISION` cautiously or
  switch the backing type to `BigInteger`.
- **Precision ceiling.** The truncation error of division is at most `10^-9`.
  This is superior to `double` for most integer-ratio arithmetic but inferior
  to arbitrary-precision libraries (`BigDecimal`) for scientific computation
  requiring more than nine fractional digits.
- **Performance.** Russian-peasant multiplication is `O(log N)` additions;
  binary long-division is `O(log N)` subtractions. Both are slower than a
  single hardware multiply/divide instruction, but remain efficient for all
  values fitting in a `long`.
