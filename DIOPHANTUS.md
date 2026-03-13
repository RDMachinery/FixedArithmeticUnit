# Diophantus and the Foundations of Fixed-Point Arithmetic

> *"If we arrive at an equation containing on each side the same term but with
> different coefficients, we must take equals from equals until we get one term
> equal to another term."*
>
> — Diophantus of Alexandria, *Arithmetica* (~250 CE)

---

Diophantus of Alexandria, who flourished around 250 CE, is often called the
father of algebra — though the title undersells how specifically relevant his
thinking is to the [precision proof in this repository](README.md#mathematical-proof-of-precision-superiority-over-floating-point).
His life's work was not arithmetic in the casual sense of tallying quantities;
it was a rigorous investigation into what kinds of numbers can be the *exact*
solutions to equations, and how to find them. That investigation anticipates,
by nearly eighteen centuries, the central argument for why `FixedArithmetic`
is provably more precise than IEEE 754 floating-point.

---

## Contents

1. [The Rational Number as the Fundamental Object](#1-the-rational-number-as-the-fundamental-object)
2. ["Take Equals from Equals"](#2-take-equals-from-equals)
3. [Diophantine Equations and the Demand for Exact Integer Solutions](#3-diophantine-equations-and-the-demand-for-exact-integer-solutions)
4. [The Term *Parisotēs* — Approximate Equality](#4-the-term-parisotēs--approximate-equality)
5. [A Continuous Thread](#5-a-continuous-thread)

---

## 1. The Rational Number as the Fundamental Object

Before Diophantus, Greek mathematics was dominated by geometry, and numbers
were understood as measurements — lengths, areas, and volumes. Fractions were
therefore treated with suspicion, as *ratios between lengths* rather than as
numbers in their own right. Diophantus broke from this tradition by recognising
fractions as full-standing numbers, allowing positive rational coefficients and
rational solutions throughout his equations. This was a philosophical rupture:
a rational number `p/q` was no longer a relationship between two integers — it
*was* an integer, of a new kind, one that could be manipulated according to its
own algebraic rules.

This is precisely the conceptual move that `FixedArithmetic` makes. Every value
is stored as the integer:

```
n  =  v × SCALE      where  SCALE = 10⁹
```

The rational number `1/3` is not approximated or geometrically described — it
is stored as the exact integer `333,333,333`, a member of the scaled integer
ring `ℤ / 10⁹`. Diophantus would have recognised this immediately as a
legitimate number.

IEEE 754, by contrast, can only represent values of the form `m × 2ᵉ`. The
proof's **Theorem 1** establishes that `1/10` has no such representation,
because that would require `2ᵏ` to be divisible by `5` — which is impossible
for any `k`. A system restricted to powers-of-two denominators cannot be a
faithful host for the rationals; the `FixedArithmetic` scaled-integer ring can.

---

## 2. "Take Equals from Equals"

The quotation at the top of this article is Diophantus's core operating
instruction: reduce everything to addition and subtraction of like terms, and
*eliminate* rather than *approximate* until the desired integer remains.

This is the operational philosophy of the entire `FixedArithmetic` class.
Consider the algorithms at its core:

| Algorithm | Diophantine parallel |
|---|---|
| Russian-peasant multiplication | Decompose into a sum of doublings — only `+` |
| Binary long division | Subtract the largest fitting multiple; record the remainder — only `−` |
| Scale-up / scale-down | Reduce `v × SCALE` back to `v` by subtracting `SCALE` repeatedly |

Every one of these routines proceeds by taking equals from equals, adding and
subtracting until the exact integer result remains. No rounding, no
approximation, no residual error.

The loop-invariant proofs in **Theorems 4 and 5** of the precision proof are
formal restatements of Diophantus's instruction in modern mathematical
language. Theorem 4, for example, demonstrates that the Russian-peasant
algorithm preserves the invariant:

```
result + ta × tb  =  a × b     (constant throughout every iteration)
```

This is "take equals from equals" rendered as a formal invariant: every
subtraction and addition is exact, and the invariant holds precisely because
nothing is ever rounded away.

---

## 3. Diophantine Equations and the Demand for Exact Integer Solutions

A **Diophantine equation** is a polynomial equation with integer coefficients
for which only integer (or rational) solutions are sought. The entire
discipline bears Diophantus's name because he refused to accept irrational or
approximate answers. He considered negative or irrational solutions
*meaningless* — values that, in his framework, simply did not count as
solutions at all.

This position maps directly onto the error analysis in **§8** of the proof.
A floating-point result is not a *wrong* answer, but it is an irrational
approximation to a rational one:

```
fl(1/3)  =  0.3333333333333333148...     ← irrational in binary
FA(1/3)  =  333333333 / 10⁹  =  1/3     ← exact rational
```

For Diophantus, the floating-point result would not count as a solution.
`FixedArithmetic` enforces the same standard: division of two integers always
produces a result expressible as `n / 10⁹` for an exact integer `n`. The
absolute error is provably bounded by `10⁻⁹`, and for inputs where the
quotient is itself an integer the error is exactly `0` (**Corollary 1**).

The following table, drawn directly from the proof, makes the contrast stark:

| Expression | `double` result | `FixedArithmetic` result |
|---|---|---|
| `0.1 + 0.2` | `0.30000000000000004` | `0.3` (exact) |
| `(1/3) × 3` | `0.9999999999999999` | `1.0` (exact) |
| `22 / 7` | `3.142857142857143` (rounded) | `3.142857142` (truncated, bounded error) |

The `double` column is not the result of programmer error — it is the
unavoidable consequence of a format whose denominators are restricted to powers
of 2. Diophantus's demand for exact rational solutions is, in this light, a
precise mathematical requirement that IEEE 754 structurally cannot satisfy.

---

## 4. The Term *Parisotēs* — Approximate Equality

There is one further detail worth noting. Diophantus coined the Greek term
***parisotēs*** (παρισότης) to refer to approximate equality — a
near-equality that is not exact equality. He introduced this term not to
*endorse* approximation but to *name and quarantine* it: to distinguish it
sharply from the true equality that his methods sought to achieve.

The fact that he needed a dedicated word for approximate equality implies he
was acutely aware of the distinction between an exact rational solution and a
near-miss. That awareness is the philosophical root of the entire floating-point
precision argument laid out in the proof. In modern terms:

- A `double` result lives in the world of *parisotēs*. Every non-dyadic
  rational — every number whose denominator contains a prime factor other than
  `2` — is replaced by the nearest representable value, silently and
  irrecoverably. The accumulated error after `n` operations is bounded by
  `O(n × 2⁻⁵²)`, a quantity that grows without bound.

- A `FixedArithmetic` result, for any computation within the integer input
  domain, does not. The error bound is constant at `< 10⁻⁹`, independent of
  the number of operations, because no rounding step is ever introduced.

Diophantus drew a line between *parisotēs* and exact equality. The precision
proof in this repository draws the same line, in the language of ULP analysis
and loop invariants, and `FixedArithmetic` sits firmly on the exact side.

---

## 5. A Continuous Thread

It would be an exaggeration to say that Diophantus anticipated binary
arithmetic or fixed-point representation. What he did was more fundamental: he
established that integers and their ratios form a **closed, exact system**
worthy of rigorous analysis, and he insisted that the goal of arithmetic is
exact solutions rather than useful approximations.

Every theorem in the precision proof is an expression of that same insistence,
restated in the language of modern computer arithmetic:

| Theorem | Diophantine echo |
|---|---|
| **Theorem 1** — `1/10` has no binary representation | A rational with an odd prime in its denominator is not a member of the dyadic rationals — it is "absurd" as a floating-point number |
| **Theorem 3** — add/subtract are exact | "Take equals from equals" leaves no remainder |
| **Theorem 4** — Russian-peasant multiplication is exact | The loop invariant is an algebraic identity, not an approximation |
| **Theorem 5** — binary long division is exact | Repeated subtraction produces the exact quotient and remainder, as in the *Arithmetica* itself |
| **Corollary 1** — integer inputs yield zero-error results | Diophantine solutions are integers; integer inputs to integer operations produce integer outputs |

The `FixedArithmetic` class is, in a modest but genuine sense, a direct
descendant of the *Arithmetica*. Diophantus insisted on exact rational
arithmetic at a time when the dominant mathematical culture saw fractions as
geometric ratios rather than numbers. `FixedArithmetic` insists on exact
rational arithmetic at a time when the dominant computational culture treats
floating-point rounding as an acceptable cost of doing business. The
insistence is the same. So, it turns out, is the solution: represent rationals
as scaled integers, and do all your work with addition and subtraction.

---

## Further Reading

- Diophantus of Alexandria, *Arithmetica* (c. 250 CE) — the original thirteen books, six of which survive in Greek
- T. L. Heath, *Diophantus of Alexandria: A Study in the History of Greek Algebra* (1910, Cambridge University Press)
- IEEE Std 754-2019, *IEEE Standard for Floating-Point Arithmetic*
- D. Goldberg, "What Every Computer Scientist Should Know About Floating-Point Arithmetic," *ACM Computing Surveys*, 23(1), 1991
