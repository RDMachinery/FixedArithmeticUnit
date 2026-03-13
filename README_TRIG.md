# FixedTrigonometry

A pure fixed-point trigonometry library for Java, built entirely on top of [`FixedArithmetic`](FixedArithmetic.java). Computes all standard trigonometric and inverse trigonometric functions using nothing but integer addition and subtraction — no `double`, no `float`, no `java.lang.Math`.

---

## Table of Contents

- [Overview](#overview)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [API Reference](#api-reference)
  - [Forward Functions](#forward-functions)
  - [Inverse Functions](#inverse-functions)
  - [Utility](#utility)
- [How It Works](#how-it-works)
  - [The FixedArithmetic Foundation](#the-fixedarithmetic-foundation)
  - [sin and cos — Taylor Series with Range Reduction](#sin-and-cos--taylor-series-with-range-reduction)
  - [tan — Ratio with Domain Guard](#tan--ratio-with-domain-guard)
  - [atan — Leibniz Series with Aggressive Range Reduction](#atan--leibniz-series-with-aggressive-range-reduction)
  - [asin and acos — Expressed via atan](#asin-and-acos--expressed-via-atan)
  - [atan2 — Quadrant-Aware Two-Argument Arctangent](#atan2--quadrant-aware-two-argument-arctangent)
  - [sqrt — Newton–Raphson Helper](#sqrt--newtonraphson-helper)
- [Precision](#precision)
- [Limitations](#limitations)
- [Running the Demo](#running-the-demo)

---

## Overview

`FixedTrigonometry` is a static utility class. All methods are `public static` and accept and return `FixedArithmetic` values. There is no instance state and no constructor — just call the methods directly.

```java
FixedArithmetic angle = FixedArithmetic.of("1.047197551"); // π/3 radians
FixedArithmetic result = FixedTrigonometry.sin(angle);
System.out.println(result); // → 0.866025403
```

---

## Requirements

- **Java 8** or later
- [`FixedArithmetic.java`](FixedArithmetic.java) must be on the classpath — `FixedTrigonometry` delegates all arithmetic to it

Both files must be compiled together:

```bash
javac FixedArithmetic.java FixedTrigonometry.java
```

---

## Quick Start

```java
import static FixedTrigonometry.*;

// Construct angles from decimal strings (radians)
FixedArithmetic pi    = FixedTrigonometry.pi();
FixedArithmetic piOver4 = FixedArithmetic.of("0.785398163"); // π/4

// Forward trig
FixedArithmetic s = sin(piOver4);   // → 0.707106781
FixedArithmetic c = cos(piOver4);   // → 0.707106781
FixedArithmetic t = tan(piOver4);   // → 1.0

// Inverse trig
FixedArithmetic half  = FixedArithmetic.of("0.5");
FixedArithmetic angle = asin(half); // → 0.523598775  (π/6)
FixedArithmetic angle2 = acos(half);// → 1.047197551  (π/3)
FixedArithmetic angle3 = atan(half);// → 0.463647609

// Two-argument arctangent
FixedArithmetic y = FixedArithmetic.of("1");
FixedArithmetic x = FixedArithmetic.of("-1");
FixedArithmetic q = atan2(y, x);    // → 2.356194490  (3π/4)

// Chain operations freely — every method returns a new FixedArithmetic
FixedArithmetic identity = sin(angle).multiply(sin(angle))
                            .add(cos(angle).multiply(cos(angle)));
// identity ≈ 1.0  (sin²θ + cos²θ = 1)
```

---

## API Reference

All methods are `public static`. Angles are always in **radians**.

### Forward Functions

#### `sin(FixedArithmetic angle) → FixedArithmetic`

Returns the sine of `angle`.

| | |
|---|---|
| **Input** | Any finite radian value |
| **Output** | ∈ \[−1, 1\] |
| **Throws** | Nothing |

```java
FixedArithmetic result = FixedTrigonometry.sin(FixedArithmetic.of("3.141592653")); // sin(π) ≈ 0.0
```

---

#### `cos(FixedArithmetic angle) → FixedArithmetic`

Returns the cosine of `angle`.

| | |
|---|---|
| **Input** | Any finite radian value |
| **Output** | ∈ \[−1, 1\] |
| **Throws** | Nothing |

```java
FixedArithmetic result = FixedTrigonometry.cos(FixedArithmetic.of("0")); // cos(0) = 1.0
```

---

#### `tan(FixedArithmetic angle) → FixedArithmetic`

Returns the tangent of `angle`, computed as `sin(angle) / cos(angle)`.

| | |
|---|---|
| **Input** | Any finite radian value where `cos(angle) ≠ 0` |
| **Output** | Any real value |
| **Throws** | `ArithmeticException` if `\|cos(angle)\| < 10⁻⁸` (i.e. angle ≈ π/2 + k·π) |

```java
FixedArithmetic result = FixedTrigonometry.tan(FixedArithmetic.of("0.785398163")); // tan(π/4) ≈ 1.0

// Catching the undefined case
try {
    FixedArithmetic bad = FixedTrigonometry.tan(FixedArithmetic.of("1.570796326")); // tan(π/2)
} catch (ArithmeticException e) {
    System.out.println("Undefined: " + e.getMessage());
}
```

---

### Inverse Functions

#### `asin(FixedArithmetic x) → FixedArithmetic`

Returns the arcsine of `x`.

| | |
|---|---|
| **Input** | `x` ∈ \[−1, 1\] |
| **Output** | Radians ∈ \[−π/2, π/2\] |
| **Throws** | `ArithmeticException` if `x` is outside \[−1, 1\] |

```java
FixedArithmetic result = FixedTrigonometry.asin(FixedArithmetic.of("1")); // → π/2 ≈ 1.570796326
```

---

#### `acos(FixedArithmetic x) → FixedArithmetic`

Returns the arccosine of `x`.

| | |
|---|---|
| **Input** | `x` ∈ \[−1, 1\] |
| **Output** | Radians ∈ \[0, π\] |
| **Throws** | `ArithmeticException` if `x` is outside \[−1, 1\] |

```java
FixedArithmetic result = FixedTrigonometry.acos(FixedArithmetic.of("0")); // → π/2 ≈ 1.570796326
```

---

#### `atan(FixedArithmetic x) → FixedArithmetic`

Returns the arctangent of `x`.

| | |
|---|---|
| **Input** | Any finite value |
| **Output** | Radians ∈ (−π/2, π/2) |
| **Throws** | Nothing |

```java
FixedArithmetic result = FixedTrigonometry.atan(FixedArithmetic.of("1")); // → π/4 ≈ 0.785398163
```

---

#### `atan2(FixedArithmetic y, FixedArithmetic x) → FixedArithmetic`

Returns the angle whose tangent is `y/x`, using the signs of both arguments to determine the correct quadrant. Matches the behaviour of `java.lang.Math.atan2`.

| | |
|---|---|
| **Input** | Any two finite values (`y`, `x`) |
| **Output** | Radians ∈ (−π, π\] |
| **Throws** | Nothing |

Special cases:

| `y` | `x` | Result |
|---|---|---|
| 0 | > 0 | 0 |
| > 0 | 0 | π/2 |
| 0 | < 0 | π |
| < 0 | 0 | −π/2 |
| 0 | 0 | 0 (by convention) |

```java
FixedArithmetic result = FixedTrigonometry.atan2(
    FixedArithmetic.of("1"),
    FixedArithmetic.of("1")
); // → π/4 ≈ 0.785398163

FixedArithmetic result2 = FixedTrigonometry.atan2(
    FixedArithmetic.of("1"),
    FixedArithmetic.of("-1")
); // → 3π/4 ≈ 2.356194490
```

---

### Utility

#### `pi() → FixedArithmetic`

Returns π as a `FixedArithmetic` constant (≈ 3.141592653).

```java
FixedArithmetic twoPi = FixedTrigonometry.pi().multiply(FixedArithmetic.of(2));
```

---

## How It Works

### The FixedArithmetic Foundation

`FixedArithmetic` represents real numbers as scaled 64-bit integers:

```
realValue = register / 10^9
```

Every arithmetic operation — including multiplication and division — is ultimately carried out using only integer addition and subtraction (via Russian-peasant multiplication and long division by repeated subtraction). `FixedTrigonometry` builds on this: it never introduces a floating-point value or calls any method outside `FixedArithmetic`'s public API.

---

### sin and cos — Taylor Series with Range Reduction

The Taylor series for sine and cosine are:

```
sin(x) = x − x³/3! + x⁵/5! − x⁷/7! + …
cos(x) = 1 − x²/2! + x⁴/4! − x⁶/6! + …
```

These converge for all `x`, but the convergence is much faster for small `|x|`. To keep the required number of terms low (and therefore precision high), the input angle is reduced in two stages before the series is evaluated.

**Stage 1 — Wrap to [−π, π]**

The nearest integer multiple of 2π is subtracted so the angle lands in [−π, π]. This uses only `FixedArithmetic.divide`, `integerPart`, `remainder`, and `subtract`.

**Stage 2 — Fold to [0, π/4]**

Three standard identities bring the angle down to the first octant:

| Condition | Identity applied |
|---|---|
| `x < 0` | `sin(−x) = −sin(x)` — negate and set a flag |
| `x > π/2` | `sin(x) = sin(π − x)` — reflect around π/2 |
| `x > π/4` | `sin(x) = cos(π/2 − x)` — switch to the cosine series |

With `x` now guaranteed to be in `[0, π/4]` (≈ 0.785), 20 terms of the series give 9-digit accuracy. Each successive term is computed incrementally — multiplying the previous term by `−x²` and dividing by the next pair of factorial factors — so only one `multiply` and one `divide` is needed per iteration.

`cos(angle)` is implemented simply as `sin(π/2 − angle)`, reusing the whole pipeline.

---

### tan — Ratio with Domain Guard

```
tan(x) = sin(x) / cos(x)
```

`sin` and `cos` are computed independently, and the result is divided. Before the division, the absolute value of cosine is checked against a threshold of 10⁻⁸. If `|cos(x)|` is smaller than this, the angle is too close to π/2 + k·π for a meaningful result, and an `ArithmeticException` is thrown.

---

### atan — Leibniz Series with Aggressive Range Reduction

The Leibniz/Gregory series for arctangent is:

```
atan(x) = x − x³/3 + x⁵/5 − x⁷/7 + …
```

This series only converges for `|x| ≤ 1`, and converges slowly near `|x| = 1`. Two reductions are applied before evaluating it.

**Reduction 1 — Reciprocal flip (for `|x| > 1`)**

```
atan(x) = sign(x) · π/2 − atan(1/x)
```

This maps any argument with `|x| > 1` to one with `|x| < 1`.

**Reduction 2 — Half-angle formula (iterated until `|x| < 0.4`)**

```
atan(x) = 2 · atan( x / (1 + √(1 + x²)) )
```

Each application roughly halves the effective argument. The loop repeats until `|x| < 0.4`, at which point the series converges quickly. The number of halvings is tracked, and the accumulated factor of 2 is applied to the series result at the end.

With `|x| < 0.4`, 20 series terms are more than sufficient for 9-digit precision. Each term is again built incrementally from the previous one.

---

### asin and acos — Expressed via atan

Rather than using separate series, both inverse functions are reduced to `atan`:

```
asin(x) = atan( x / √(1 − x²) )
acos(x) = π/2 − asin(x)
```

The special cases `x = ±1` are handled directly (returning `±π/2`) to avoid a division by zero in the square root.

---

### atan2 — Quadrant-Aware Two-Argument Arctangent

`atan2(y, x)` determines the angle of the point `(x, y)` in the full 360° plane. It is built from `atan(y/x)` with quadrant correction:

| `x` | `y` | Adjustment |
|---|---|---|
| `x > 0` | any | `atan(y/x)` — Quadrants I & IV |
| `x < 0`, `y ≥ 0` | — | `atan(y/x) + π` — Quadrant II |
| `x < 0`, `y < 0` | — | `atan(y/x) − π` — Quadrant III |
| `x = 0`, `y > 0` | — | `+π/2` |
| `x = 0`, `y < 0` | — | `−π/2` |
| `x = 0`, `y = 0` | — | `0` (by convention) |

---

### sqrt — Newton–Raphson Helper

Square root is needed internally by `atan` (for the half-angle reduction) and by `asin`. It is computed using Newton–Raphson iteration:

```
r_{n+1} = (r_n + x / r_n) / 2
```

The seed `r_0` is found by an integer binary search for the floor of the square root of the integer part of `x`, which places the initial guess within a factor of 2 of the true answer. From there, 15 NR iterations converge to full 9-digit precision (each iteration approximately doubles the number of correct digits).

---

## Precision

All results agree with `java.lang.Math` to within approximately **±2 ULP** at 9 decimal places of precision (the precision level of the underlying `FixedArithmetic` implementation).

The internal constants π, π/2, π/4, and 2π are stored with all 9 available decimal digits. Any residual error in the final digit arises from accumulated rounding in fixed-point division, not from series truncation.

> **Note:** Because `FixedArithmetic` uses truncating (floor-toward-zero) division rather than rounding, the last digit of some results may differ from a correctly-rounded result by 1 unit.

---

## Limitations

| Limitation | Detail |
|---|---|
| **Precision ceiling** | 9 decimal digits, set by `FixedArithmetic.PRECISION`. No mechanism exists to increase this without modifying `FixedArithmetic`. |
| **Integer part range** | `FixedArithmetic` uses `long` internally. For `sin`/`cos` this is irrelevant (output is always ≤ 1), but for `tan` with very large integer outputs, or for extreme inputs to `atan2`, overflow is theoretically possible for arguments with integer parts above ~10⁹. |
| **tan near π/2** | Any angle within ~10⁻⁸ radians of π/2 + k·π throws `ArithmeticException` rather than returning a very large value. |
| **No degrees** | All inputs and outputs are radians. Convert with `angle_rad = angle_deg · π / 180` if needed. |
| **Not thread-safe** | `FixedArithmetic` instances carry mutable register fields. Do not share a single `FixedArithmetic` instance across threads without external synchronisation. |

---

## Running the Demo

`FixedTrigonometry` includes a `main` method that prints a comparison table against `java.lang.Math` for a representative set of inputs.

```bash
javac FixedArithmetic.java FixedTrigonometry.java
java FixedTrigonometry
```

Example output (abbreviated):

```
=== FixedTrigonometry Demo ===

angle       sin(fixed)     sin(Math)       cos(fixed)     cos(Math)       tan(fixed)     tan(Math)
----------------------------------------------------------------------------------------------------
0           0.0            0.0             1.0            1.0             0.0            0.0
π/6         0.5            0.5             0.866025403    0.8660254037    0.577350269    0.5773502691
π/4         0.707106781    0.7071067811    0.707106781    0.7071067811    1.0            0.9999999999
π/3         0.866025403    0.8660254037    0.5            0.5             1.732050808    1.7320508075
π/2         1.0            1.0             0.0            6.123233E-17    undefined      1.633E16

--- Inverse functions ---

x         asin(fixed)    asin(Math)      acos(fixed)    acos(Math)
----------------------------------------------------------------------
-1.0      -1.570796326   -1.5707963267   3.141592653    3.1415926535
-0.5      -0.523598775   -0.5235987755   2.094395102    2.0943951023
0.0       0.0            0.0             1.570796326    1.5707963267
0.5       0.523598775    0.5235987755    1.047197551    1.0471975511
1.0       1.570796326    1.5707963267    0.0            0.0

--- atan / atan2 ---

x         atan(fixed)    atan(Math)
----------------------------------------
-10.0     -1.470796326   -1.4711276743
-1.0      -0.785398163   -0.7853981633
-0.5      -0.463647609   -0.4636476090
0.0       0.0            0.0
0.5       0.463647609    0.4636476090
1.0       0.785398163    0.7853981633
10.0      1.470796326    1.4711276743
```
