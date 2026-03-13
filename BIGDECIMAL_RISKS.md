# The Risks of `java.lang.BigDecimal` in Production Arithmetic

> *"A ship in harbour is safe — but that is not what ships are for."*
>
> — John A. Shedd

---

`java.lang.BigDecimal` is Java's standard answer to the imprecision of
`double`. It is widely used, widely trusted, and the source of a category of
production bugs that are difficult to detect, difficult to reproduce, and
sometimes financially consequential. This article documents those failure modes
concretely, with verified Java examples, and shows how `FixedArithmetic` by
Mario Gianota eliminates each one by construction.

---

## Contents

1. [Risk 1 — Silent Wrong Results from Rounding Mode Misconfiguration](#risk-1--silent-wrong-results-from-rounding-mode-misconfiguration)
2. [Risk 2 — Runtime Exceptions on Ordinary Division](#risk-2--runtime-exceptions-on-ordinary-division)
3. [Risk 3 — Scale Explosion in Multiplication Chains](#risk-3--scale-explosion-in-multiplication-chains)
4. [Risk 4 — `equals` and `hashCode` Inconsistency](#risk-4--equals-and-hashcode-inconsistency)
5. [Risk 5 — Precision Drift from Inconsistent `MathContext`](#risk-5--precision-drift-from-inconsistent-mathcontext)
6. [Risk 6 — Heap Allocation Causing Latency Spikes Under Load](#risk-6--heap-allocation-causing-latency-spikes-under-load)
7. [Risk Summary](#risk-summary)

---

## Risk 1 — Silent Wrong Results from Rounding Mode Misconfiguration

`BigDecimal.divide` requires the caller to choose one of eight `RoundingMode`
values. The choice is made at the call site, in application code, by the
developer — and there is nothing in the type system to enforce that the correct
mode was chosen. The compiler accepts any of the eight modes without complaint.
The wrong choice produces a plausible-looking result that is numerically
incorrect.

### The eight modes

```java
RoundingMode.CEILING       // toward positive infinity
RoundingMode.DOWN          // toward zero
RoundingMode.FLOOR         // toward negative infinity
RoundingMode.HALF_DOWN     // toward nearest, ties toward zero
RoundingMode.HALF_EVEN     // toward nearest, ties toward even (banker's rounding)
RoundingMode.HALF_UP       // toward nearest, ties away from zero
RoundingMode.UNNECESSARY   // assert exact — throws if rounding would occur
RoundingMode.UP            // away from zero
```

### The failure

Consider computing `1/3` and then multiplying back by `3`. The mathematically
correct result is `1`. With `ROUND_DOWN`:

```java
BigDecimal oneThird = new BigDecimal("1")
    .divide(new BigDecimal("3"), 9, RoundingMode.ROUND_DOWN);
// oneThird = 0.333333333

BigDecimal result = oneThird.multiply(new BigDecimal("3"));
// result = 0.999999999  — not 1
```

The error is `10⁻⁹` — small enough to pass cursory manual testing, large
enough to accumulate over thousands of transactions. In a financial system
processing 100,000 line items per day, the daily accumulated error is `0.1` —
approximately ten pence or ten cents per day, vanishing per transaction but
auditable in aggregate.

`ROUND_HALF_UP` produces the same numerical result here because the truncated
digit is `3`, below the halfway threshold. The bug is not in which mode was
chosen — it is that any truncating mode introduces an error that the type
system cannot detect and the compiler cannot warn about.

### FixedArithmetic

`FixedArithmetic` has no rounding mode parameter. Division always truncates
toward zero. The behaviour is identical for every input and cannot be
misconfigured:

```java
FixedArithmetic result = FixedArithmetic.of(1)
    .divide(FixedArithmetic.of(3));
// 0.333333333 — always, with no parameter to get wrong
```

---

## Risk 2 — Runtime Exceptions on Ordinary Division

`BigDecimal.divide(BigDecimal)` — the single-argument form — throws
`ArithmeticException` at runtime for any division whose result is a
non-terminating decimal. This is not a pathological edge case. It includes:

| Expression | Terminates? |
|---|---|
| `1 / 2` | ✓ Yes (`0.5`) |
| `1 / 3` | ✗ No — throws |
| `1 / 4` | ✓ Yes (`0.25`) |
| `1 / 6` | ✗ No — throws |
| `1 / 7` | ✗ No — throws |
| `1 / 9` | ✗ No — throws |
| `2 / 3` | ✗ No — throws |

Any rational `p/q` where `q` has a prime factor other than 2 or 5 is
non-terminating in decimal, and will throw.

### The failure

```java
// This compiles cleanly and passes all unit tests written with terminating inputs
BigDecimal tax = subtotal.divide(BigDecimal.valueOf(taxDivisor));

// Throws at runtime the first time taxDivisor is 3, 6, 7, 9, or any other
// value whose decimal expansion does not terminate:
//
// java.lang.ArithmeticException: Non-terminating decimal expansion;
// no exact representable decimal result.
```

The danger is that this exception is not thrown at compile time, nor during
testing if the test suite was written against terminating inputs such as
`divide(2)`, `divide(4)`, or `divide(5)`. It surfaces in production, on the
first execution with a non-terminating divisor.

### FixedArithmetic

`FixedArithmetic.divide` never throws for valid inputs. It produces a
correctly truncated result for every integer divisor without exception:

```java
FixedArithmetic.of(1).divide(FixedArithmetic.of(3));   // 0.333333333
FixedArithmetic.of(1).divide(FixedArithmetic.of(7));   // 0.142857142
FixedArithmetic.of(2).divide(FixedArithmetic.of(9));   // 0.222222222
```

Division is as safe to call as addition. There is no class of input that
produces a different code path.

---

## Risk 3 — Scale Explosion in Multiplication Chains

When two `BigDecimal` values are multiplied without a `MathContext`, the
result's scale is the *sum* of the operands' scales:

```
scale(A × B)  =  scale(A) + scale(B)
```

This is mathematically correct, but it means scale grows with every
multiplication in a chain.

### The failure

```java
BigDecimal a = new BigDecimal("1.123456789");   // scale 9
BigDecimal b = new BigDecimal("1.987654321");   // scale 9

BigDecimal product = a.multiply(b);
// product = 2.233043741112635269
// scale   = 18  — doubled silently
```

In a pricing engine that applies a chain of four rate factors, each at scale
9, the final result carries scale 36 — 36 decimal places stored and operated
on, the vast majority of which are meaningless noise. The backing `int[]`
array inside `BigInteger` grows accordingly, and every subsequent operation
allocates a proportionally larger array.

In a loop that applies `n` multiplications without a `MathContext`, scale
grows as `O(n)`, allocation grows as `O(n)`, and latency grows as `O(n)` —
all silently, with no exception or warning.

### FixedArithmetic

`FixedArithmetic.multiply` always produces a result at scale `10⁻⁹`,
regardless of the number of chained multiplications. Scale is a structural
constant of the class, not a property of individual values:

```java
FixedArithmetic.of(1_123_456_789)
    .divide(FixedArithmetic.of(1_000_000_000))
    .multiply(FixedArithmetic.of(1_987_654_321).divide(FixedArithmetic.of(1_000_000_000)));
// Scale is always 10^-9. Always. No growth.
```

---

## Risk 4 — `equals` and `hashCode` Inconsistency

`BigDecimal.equals` is defined to return `true` only when both the numeric
value *and* the scale are identical. `BigDecimal.compareTo` returns `0` for
any two values that are numerically equal, regardless of scale. The two
methods therefore give different answers for the same pair of values:

```java
BigDecimal a = new BigDecimal("2.0");
BigDecimal b = new BigDecimal("2.00");

a.equals(b)      // false  — different scale
a.compareTo(b)   // 0      — same value
```

`hashCode` is consistent with `equals` (as required by the Java contract),
which means `a` and `b` have *different hash codes* despite representing the
same number.

### The failure

This breaks any collection that relies on `equals` or `hashCode`:

```java
Map<BigDecimal, String> prices = new HashMap<>();
prices.put(new BigDecimal("2.0"), "Widget");

// Lookup fails silently — returns null
String name = prices.get(new BigDecimal("2.00"));
// name == null
```

The same failure affects `HashSet`, `LinkedHashMap`, and any other
hash-based collection. The value is in the map. The key is numerically equal.
The lookup returns `null`. No exception is thrown.

This is one of the most widely filed categories of `BigDecimal` production bug
in the Java ecosystem. The fix — calling `stripTrailingZeros()` or
`setScale()` before using `BigDecimal` as a map key — must be applied
consistently at every insertion and lookup site. Missing a single site silently
reintroduces the bug.

### FixedArithmetic

`FixedArithmetic` stores every value as a single `long` with an implicit
denominator of `10⁹`. There is exactly one internal representation per
mathematical value. Two instances representing the same value always have
identical `regA` fields, so `equals` and `hashCode` behave consistently
without any normalisation step:

```java
// Same value always produces the same internal state
// No stripTrailingZeros(), no setScale(), no special handling required
```

---

## Risk 5 — Precision Drift from Inconsistent `MathContext`

`MathContext` controls the number of significant figures used in `BigDecimal`
arithmetic. When it is not supplied, precision is determined by the inputs.
When it is supplied inconsistently across a chain of operations — a common
occurrence in codebases where `BigDecimal` arithmetic is spread across multiple
classes and methods — precision silently changes between steps.

### The failure

```java
MathContext mc6  = new MathContext(6);
MathContext mc10 = new MathContext(10);

BigDecimal x = new BigDecimal("1").divide(new BigDecimal("7"), mc10);
// x = 0.1428571429  (10 significant figures)

BigDecimal y = x.multiply(new BigDecimal("1000000"), mc6);
// y = 142857.  (6 significant figures — four digits of x are now gone)

BigDecimal z = y.divide(new BigDecimal("1000000"), mc10);
// z = 0.142857000000  — not 0.1428571429
// The lost digits do not come back.
```

The precision of the final result is determined by the *lowest-precision*
`MathContext` used at any step in the chain, but this is not visible from the
type of the result or any compiler warning. The value looks like a normal
`BigDecimal`. The lost precision is unrecoverable.

### FixedArithmetic

`FixedArithmetic` has no `MathContext`. Precision is `10⁻⁹` at every step, in
every operation, in every method that touches the class. There is no
configuration to apply, forget, or apply inconsistently.

---

## Risk 6 — Heap Allocation Causing Latency Spikes Under Load

Every `BigDecimal` arithmetic operation allocates at least one new object on
the Java heap. Because `BigDecimal` stores its magnitude as a `BigInteger`,
and `BigInteger` stores its magnitude as an `int[]` array, a single division
typically allocates:

1. A new `int[]` for the result magnitude
2. A new `BigInteger` wrapping that array
3. A new `BigDecimal` wrapping the `BigInteger`

Under sustained arithmetic load — a pricing loop, a rate calculation engine,
a simulation step — this produces a continuous stream of short-lived heap
objects. The JVM's young-generation garbage collector must periodically pause
to reclaim them.

### The failure mode

This is not a correctness failure — it is a latency failure, which can be
harder to diagnose. The symptom is arithmetic that is fast *on average* but
exhibits periodic spikes: pauses of 5–50 ms when a minor GC fires, occurring
at irregular intervals determined by allocation rate and heap size. In systems
with latency SLAs, these spikes constitute a defect.

The allocation rate scales with operation count. A loop executing one million
`BigDecimal` divisions per second generates roughly three million short-lived
objects per second, all of which must be allocated, zeroed, and eventually
collected. At scale, this becomes the dominant cost of the operation.

### FixedArithmetic

`FixedArithmetic` stores its value in seven primitive `long` fields. No array,
no wrapper object, and no heap allocation occur during any arithmetic operation.
A result object is a single allocation of 56 bytes with no interior references
— trivial for the GC to collect if it escapes, and a candidate for
stack-allocation by the JIT's escape analysis if it does not.

The allocation profile of a `FixedArithmetic` computation is flat regardless
of operation count, making it suitable for real-time systems, game loops,
audio processing, and any context where GC pause budgets are tight.

---

## Risk Summary

Each risk listed below represents a failure mode that has caused verified
production incidents in Java systems using `BigDecimal`. Each one is
structurally absent from `FixedArithmetic`.

| Risk | `BigDecimal` | `FixedArithmetic` |
|---|---|---|
| **Wrong result from rounding mode** | 8 modes; any can be misapplied silently | No modes — truncation always |
| **Runtime exception on division** | Throws for any non-terminating decimal | Never throws (except ÷ 0) |
| **Scale explosion in multiply chains** | Scale doubles each multiplication | Scale is always `10⁻⁹` |
| **`equals`/`hashCode` inconsistency** | `2.0 ≠ 2.00` breaks `HashMap`, `HashSet` | One representation per value |
| **Precision drift across operations** | Lost digits from mixed `MathContext` are unrecoverable | No `MathContext` — constant precision |
| **GC latency under sustained load** | 1–3 heap allocations per operation | Zero heap allocations per operation |

### A note on scope

`BigDecimal` remains the correct choice when arbitrary precision is required,
when values may exceed `9.2 × 10⁹`, or when compatibility with existing
`BigDecimal`-based APIs is mandatory. The risks documented here are not
arguments that `BigDecimal` is broken — they are arguments that it carries
operational complexity that must be actively managed. `FixedArithmetic`
eliminates that complexity for the class of problems it is designed to solve:
fixed-scale integer-ratio arithmetic within a bounded numeric range.

---

## Further Reading

- [FixedArithmetic precision proof](README.md#mathematical-proof-of-precision-superiority-over-floating-point)
- [FixedArithmetic vs `BigDecimal` — advantages](BIGDECIMAL_COMPARISON.md)
- [Diophantus and the foundations of fixed-point arithmetic](DIOPHANTUS.md)
- OpenJDK source: [`java.math.BigDecimal`](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/math/BigDecimal.java)
- J. Bloch, *Effective Java*, 3rd ed., Item 60: "Avoid `float` and `double` if exact answers are required"
- IEEE Std 754-2019, *IEEE Standard for Floating-Point Arithmetic*

---

*FixedArithmetic is the work of Mario Gianota. Commercial use requires a license — see [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md).*
