# FixedArithmetic vs `java.lang.BigDecimal`

> *"Perfection is achieved not when there is nothing more to add, but when
> there is nothing left to take away."*
>
> — Antoine de Saint-Exupéry

---

`java.lang.BigDecimal` is the standard Java answer to floating-point
imprecision. It is powerful, flexible, and general. It is also significantly
heavier than the problem often requires. For programs that work exclusively
with integer-ratio arithmetic — financial calculations, fixed-rate conversions,
game engines, embedded controllers, and protocol codecs — `FixedArithmetic`
offers a set of concrete, measurable advantages. This article documents them.

---

## Contents

1. [No Heap Allocation](#1-no-heap-allocation)
2. [No Rounding-Mode Footguns](#2-no-rounding-mode-footguns)
3. [Constant, Predictable Precision](#3-constant-predictable-precision)
4. [Algorithmic Transparency and Auditability](#4-algorithmic-transparency-and-auditability)
5. [Garbage Collector Pressure](#5-garbage-collector-pressure)
6. [Deterministic Representation](#6-deterministic-representation)
7. [No Unchecked Exceptions on Division](#7-no-unchecked-exceptions-on-division)
8. [Summary](#8-summary)

---

## 1. No Heap Allocation

`BigDecimal` stores its value as a `BigInteger` mantissa paired with an `int`
scale. `BigInteger` itself wraps an `int[]` array on the heap whose length
grows with the magnitude and precision of the value. Every arithmetic
operation — addition, multiplication, division — allocates at least one new
`BigInteger` and therefore at least one new `int[]`:

```java
// Each of these lines allocates one or more objects on the heap
BigDecimal a = new BigDecimal("7");
BigDecimal b = new BigDecimal("3");
BigDecimal c = a.divide(b, 9, RoundingMode.DOWN);   // new BigDecimal + new BigInteger + new int[]
```

`FixedArithmetic` stores its entire state in a fixed set of primitive `long`
fields — `regA` through `regS`. No array, no wrapper object, and no heap
allocation occur during any arithmetic operation. The result object itself is a
single allocation of seven `long` fields, totalling 56 bytes, regardless of
the magnitude or complexity of the value it holds:

```java
// One object, seven longs, no heap arrays, ever
FixedArithmetic a = FixedArithmetic.of(7);
FixedArithmetic c = a.divide(FixedArithmetic.of(3));
```

In a tight loop performing millions of fixed-ratio calculations — the kind
common in financial engines, physics simulations, and network protocol
processors — the difference between zero heap allocation per operation and
one-to-three heap allocations per operation is the difference between
predictable sub-microsecond throughput and GC-induced latency spikes.

---

## 2. No Rounding-Mode Footguns

`BigDecimal` division requires the caller to supply a `RoundingMode` for any
quotient that cannot be represented exactly in the requested scale. Java
provides eight such modes:

```
CEILING  DOWN  FLOOR  HALF_DOWN  HALF_EVEN  HALF_UP  UNNECESSARY  UP
```

Choosing the wrong mode silently produces incorrect results. The most common
mistake is omitting the mode entirely:

```java
// Throws ArithmeticException at runtime for non-terminating decimals
BigDecimal result = new BigDecimal("1").divide(new BigDecimal("3"));
```

This exception is not thrown at compile time. It surfaces only when a
non-terminating decimal is encountered in production — exactly the kind of
latent bug that evades unit tests written against terminating inputs.

`FixedArithmetic` has no rounding modes. Division always truncates toward zero
to nine decimal places, unconditionally:

```java
// Never throws. Always returns a value. Behaviour is identical for every input.
FixedArithmetic result = FixedArithmetic.of(1).divide(FixedArithmetic.of(3));
// → 0.333333333
```

There is one behaviour, one code path, and one outcome. The API cannot be
misconfigured because it has no configuration.

---

## 3. Constant, Predictable Precision

`BigDecimal` arithmetic precision is controlled by a `MathContext` parameter
that specifies a number of significant figures and a rounding mode. When
`MathContext` is not supplied, precision is determined by the inputs and can
vary operation by operation:

```java
BigDecimal x = new BigDecimal("1.000000000");
BigDecimal y = new BigDecimal("3");
BigDecimal z = x.multiply(y);   // scale is now determined by operand scales — may surprise
```

The effective precision of a `BigDecimal` computation therefore depends on the
history of operations that produced each operand, the scales of intermediate
results, and whether `MathContext` was consistently applied throughout. This is
a hidden source of inconsistency in long chains of computation.

`FixedArithmetic` has exactly one precision: nine decimal places. It never
changes. It does not depend on inputs, operation order, or configuration. A
value computed after one hundred chained operations has the same precision
guarantee as a value computed in a single step:

```
| error |  <  10⁻⁹     for all inputs, all operations, always
```

In systems where uniform, auditable precision is a requirement — financial
reporting, legal calculations, protocol specifications — a constant bound is
easier to reason about, document, and certify than a configurable one.

---

## 4. Algorithmic Transparency and Auditability

`BigDecimal` is implemented across approximately 6,000 lines of internal Java
source, calling into `BigInteger` which adds several thousand more. The
algorithms for multiplication (`multiplyToBigInteger`), division
(`divideAndRemainder`), and rounding are not visible from the public API and
require reading OpenJDK internals to audit.

`FixedArithmetic` exposes its complete arithmetic in under 120 lines of
logic spread across four methods. Each operation reduces to a single
well-known algorithm with a formal correctness proof:

| Operation | Algorithm | Proof technique |
|---|---|---|
| Add / Subtract | Single `long` addition or subtraction | Trivially exact for integers |
| Multiply | Russian-peasant binary multiplication | Loop invariant: `result + ta × tb = a × b` |
| Divide | Binary long division by repeated subtraction | Partition invariant: `quotient × divisor + rem = dividend` |

Any engineer can read the source, trace a specific input through every register
assignment, and verify by hand that the output is correct. This level of
transparency is not available in `BigDecimal`, and it matters in regulated
industries where arithmetic code must be independently verified.

---

## 5. Garbage Collector Pressure

In addition to the per-operation allocations described in §1, `BigDecimal`
operations generate intermediate `BigInteger` objects that are immediately
discarded — allocated, used once, and left for the garbage collector. In a
system processing high-frequency data, this produces a pattern of short-lived
object creation that stresses the young-generation heap and triggers frequent
minor GC pauses.

Because `FixedArithmetic` allocates nothing during arithmetic (only one result
object per operation, containing only primitive fields), it imposes near-zero
GC pressure. The result objects themselves are small, field-only instances with
no interior references, making them trivial for the GC to collect if they
become unreachable — and trivial for the JIT to stack-allocate via escape
analysis if they do not escape the calling method.

This makes `FixedArithmetic` suitable for use in:

- **Real-time systems** where GC pause budgets are tight (games, audio, control loops)
- **High-frequency trading engines** where per-tick latency is measured in nanoseconds
- **Embedded JVM deployments** where heap size is constrained
- **Android applications** where GC pressure directly degrades frame rate

---

## 6. Deterministic Representation

A `BigDecimal` value carries its scale as metadata. Two `BigDecimal` instances
representing the same mathematical value can have different scales and will
compare as unequal under `equals`:

```java
new BigDecimal("2.0").equals(new BigDecimal("2.00"))   // false
new BigDecimal("2.0").compareTo(new BigDecimal("2.00")) // 0 — but equals is false
```

This asymmetry between `equals` and `compareTo` is a well-known source of bugs
when `BigDecimal` values are used as keys in `HashMap` or `HashSet`, or when
equality is tested directly in conditionals.

`FixedArithmetic` has no scale metadata. Every value is stored as a single
`long` with denominator `10⁹` always implied. Two instances representing the
same mathematical value always have the same `regA`, so equality is a single
integer comparison with no edge cases:

```java
// Same mathematical value always produces identical internal state
FixedArithmetic.of(2).equals(FixedArithmetic.of(2))   // unambiguously true
```

There is one canonical representation per value. Equality means what it
says.

---

## 7. No Unchecked Exceptions on Division

As noted in §2, `BigDecimal.divide(BigDecimal)` (the single-argument form)
throws `ArithmeticException` for any non-terminating decimal result. This
includes `1/3`, `1/7`, `1/9`, `2/3`, and every other rational with a
denominator containing a prime factor other than 2 or 5 — a large fraction of
all integer divisions that occur in practice.

The two-argument form requires a scale and rounding mode:

```java
result = a.divide(b, 9, RoundingMode.HALF_UP);
```

This is safe, but it means the caller must make three decisions — scale,
rounding mode, and whether to use the safe form at all — every time a division
is written. Omitting any of them produces either a runtime exception or a
silently incorrect result.

`FixedArithmetic.divide` never throws for valid inputs (only for division by
zero, which is mathematically undefined and throws in every arithmetic system).
The precision and truncation behaviour are fixed by the class, not the caller.
Division is as safe to call as addition:

```java
FixedArithmetic.of(1).divide(FixedArithmetic.of(3))   // always works, always 0.333333333
FixedArithmetic.of(1).divide(FixedArithmetic.of(7))   // always works, always 0.142857142
FixedArithmetic.of(355).divide(FixedArithmetic.of(113)) // always works, always 3.14159292
```

---

## 8. Summary

`BigDecimal` is the right tool when arbitrary precision is required, when
magnitudes exceed `10⁹`, or when multiple rounding modes must be supported.
For the large class of problems that do not need those features, it carries
costs — heap allocation, GC pressure, rounding-mode complexity, and scale
metadata — that `FixedArithmetic` avoids entirely.

| Property | `BigDecimal` | `FixedArithmetic` |
|---|---|---|
| Heap allocation per operation | Yes — `BigInteger` + `int[]` | No — primitive `long` fields only |
| Rounding modes | 8 — must be chosen correctly | None — truncation always |
| Precision consistency | Varies with scale and `MathContext` | Constant: `< 10⁻⁹` always |
| Division safety | Throws for non-terminating decimals | Never throws (except ÷ 0) |
| `equals` vs `compareTo` asymmetry | Yes (`2.0 ≠ 2.00`) | No — one canonical form per value |
| Source lines of arithmetic logic | ~6,000 (incl. `BigInteger`) | ~120 |
| GC pressure | High under sustained use | Near zero |
| Suitable for real-time / embedded | No | Yes |

For integer-ratio arithmetic within the nine-decimal-place precision bound,
`FixedArithmetic` is not a compromise — it is the more appropriate tool.

---

## Further Reading

- OpenJDK source: [`java.math.BigDecimal`](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/math/BigDecimal.java)
- OpenJDK source: [`java.math.BigInteger`](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/math/BigInteger.java)
- [FixedArithmetic precision proof](README.md#mathematical-proof-of-precision-superiority-over-floating-point)
- [Diophantus and the foundations of fixed-point arithmetic](DIOPHANTUS.md)
- JEP 193 — Variable Handles (background on low-level Java numeric performance)
- D. Goldberg, "What Every Computer Scientist Should Know About Floating-Point
  Arithmetic," *ACM Computing Surveys*, 23(1), 1991
