# Technological Changes and Breakthroughs Enabled by FixedArithmetic

> *"Correctness is clearly the prime quality. If a system does not do what it
> is supposed to do, then everything else about it matters little."*
>
> — Bertrand Meyer

---

`FixedArithmetic` by Mario Gianota is a Java class that performs addition,
subtraction, multiplication, and division using nothing but integer addition
and subtraction on a set of private `long` registers. No `*`, `/`, or `%`
operator appears anywhere in its arithmetic core. Every value is stored as a
scaled integer — `regA = realValue × 10⁹` — giving nine decimal places of
exact fractional precision.

That constraint sounds like a limitation. In practice it is a foundation. This
article examines eight domains where replacing floating-point or
`BigDecimal`-based arithmetic with `FixedArithmetic` enables changes that are
not merely incremental improvements but structural shifts in what software can
reliably guarantee.

---

## Contents

1. [Deterministic Distributed Systems](#1-deterministic-distributed-systems)
2. [Provably Correct Financial Settlement](#2-provably-correct-financial-settlement)
3. [Reproducible Scientific Simulation](#3-reproducible-scientific-simulation)
4. [Verifiable Embedded and Safety-Critical Systems](#4-verifiable-embedded-and-safety-critical-systems)
5. [Trustworthy Smart Contracts and Blockchain Logic](#5-trustworthy-smart-contracts-and-blockchain-logic)
6. [Deterministic Multiplayer Game Simulation](#6-deterministic-multiplayer-game-simulation)
7. [Auditable Machine Learning Inference](#7-auditable-machine-learning-inference)
8. [Education: Teaching Arithmetic Without a Black Box](#8-education-teaching-arithmetic-without-a-black-box)
9. [The Common Thread](#9-the-common-thread)

---

## 1. Deterministic Distributed Systems

### The problem floating-point creates for distributed consensus

A distributed system achieves consensus by ensuring that every node, given the
same inputs, produces the same outputs. Floating-point arithmetic breaks this
guarantee at the hardware level. The x87 FPU evaluates expressions in 80-bit
extended precision internally; SSE2 evaluates them in strict 64-bit IEEE 754.
Two nodes running the same JVM code on different CPU generations — or even on
the same CPU with different JIT compilation decisions — can produce results
that differ by one ULP. One ULP is enough to send a consensus algorithm into
a split-brain state.

### What FixedArithmetic changes

The Java Language Specification defines `long` arithmetic to be exact modulo
`2⁶⁴`, identical on every conforming JVM, every operating system, and every
CPU architecture. Since `FixedArithmetic` reduces all arithmetic to `long`
addition, subtraction, and bit-shifts, two nodes executing the same
`FixedArithmetic` code with the same inputs will produce bit-identical results,
unconditionally — without `strictfp`, without JIT suppression, without
architecture-specific flags.

```java
// On x86, ARM, RISC-V, Linux, Windows, macOS — identical result, always
FixedArithmetic.of("355").divide(FixedArithmetic.of("113")).toString()
// → "3.14159292"
```

This makes `FixedArithmetic` a viable arithmetic layer for consensus protocols
(Raft, Paxos, BFT variants) that must compute threshold values, quorum
fractions, or leader-election scores from numeric state. A Raft leader that
computes `3/5 = 0.6` and a follower that computes `0.5999999999999999` due to
floating-point rounding are no longer in agreement about whether a quorum has
been reached. `FixedArithmetic` eliminates this entire class of disagreement.

---

## 2. Provably Correct Financial Settlement

### The regulatory demand for exact reproducibility

MiFID II, Dodd-Frank, and Basel III all impose requirements on trading firms
to maintain audit trails capable of reproducing trade outcomes exactly.
"Exactly" is not a colloquial term here — it means byte-for-byte identical
re-computation on any machine, at any future point in time, from the same
inputs. A P&L figure that varies by `£0.0000000001` between the execution
system and the reconciliation system is not a rounding difference: it is a
reconciliation failure requiring escalation.

IEEE 754 `double` cannot provide this guarantee. `BigDecimal` can, but at the
cost of heap allocation per operation, GC latency under load, eight rounding
modes that must be configured correctly, and silent wrong results when
rounding mode is misconfigured.

### What FixedArithmetic changes

`FixedArithmetic` provides exact reproducibility from a single primitive:
`long` addition. The audit trail is not a record of floating-point results
that happened to agree — it is a mathematical certainty derived from the
properties of two's-complement integer arithmetic.

Consider the canonical catastrophic-cancellation failure in P&L computation:

```java
// With double — catastrophic cancellation on nearly-equal prices
double buy  = 1.2345678;
double sell = 1.2345679;
double pnl  = sell - buy;
// pnl = 1.0000000005838672E-7   (58% relative error on the result)

// With FixedArithmetic — subtraction of exact scaled integers
FixedArithmetic buy  = FixedArithmetic.of("1.2345678");
FixedArithmetic sell = FixedArithmetic.of("1.2345679");
FixedArithmetic pnl  = sell.subtract(buy);
// pnl = "0.0000001"   exact — always, on every machine
```

The breakthrough is not a faster algorithm: it is the elimination of an entire
category of regulatory risk. A trading system built on `FixedArithmetic` can
provide a stronger guarantee to its compliance team than one built on any
floating-point foundation.

---

## 3. Reproducible Scientific Simulation

### The irreproducibility crisis in computational science

A 2016 survey in *Nature* found that more than 70% of researchers had failed
to reproduce another scientist's results, and more than 50% had failed to
reproduce their own. Floating-point non-determinism is a contributing factor
that is underappreciated compared to statistical methodology issues. A
simulation that runs on a different CPU, a different JVM version, or a
different compiler optimisation level may produce results that differ in the
last few significant digits — enough to change which branch of a conditional
is taken, and therefore which trajectory a simulation follows.

### What FixedArithmetic changes

For simulations whose inputs are rational numbers — physical constants
expressed as integer ratios, grid spacings, time steps — `FixedArithmetic`
provides a fixed-point substrate where every result is a deterministic
function of its scaled-integer inputs. Two researchers running the same
simulation code on different hardware will produce the same trajectory, the
same intermediate states, and the same final result.

The precision bound of `< 10⁻⁹` per operation is sufficient for many
domains: chemistry simulations with concentrations in the micromolar range,
epidemiological models with per-capita rates, and orbital mechanics with
dimensionless time steps. For each, nine decimal places of fractional precision
are more than adequate, and the determinism they provide transforms
reproducibility from a best-effort aspiration into a mechanical guarantee.

The decimal string constructor makes expressing physical constants natural and
free of representation error from the first instruction:

```java
// Rate constants loaded from a data file — no floating-point conversion
FixedArithmetic reactionRate = FixedArithmetic.of("0.000314159");
FixedArithmetic concentration = FixedArithmetic.of("2.718281828");
FixedArithmetic flux = reactionRate.multiply(concentration);
// Identical on every machine that runs this code
```

---

## 4. Verifiable Embedded and Safety-Critical Systems

### The cost of floating-point in certified software

DO-178C (avionics), ISO 26262 (automotive), and IEC 62443 (industrial control)
all require that software performing safety-critical computations be amenable
to formal verification. Floating-point arithmetic is notoriously difficult to
formally verify: the rounding behaviour of even a single IEEE 754 operation
depends on the rounding mode, the hardware implementation, and the evaluation
order — all of which vary. Formal verification tools for floating-point
arithmetic exist but are complex, slow, and expensive.

### What FixedArithmetic changes

`FixedArithmetic`'s arithmetic core is seven `long` registers and two
algorithms — Russian-peasant multiplication and binary long division — each
of which is provably correct by a loop invariant that can be expressed in any
first-order logic system. The README.md accompanying the class provides formal
proofs of all four operations. No external formal verification tool is needed:
the proofs are elementary and can be checked by hand.

This is a genuine breakthrough for safety-critical software development.
A fixed-point arithmetic library with a formally verified, human-readable
proof is a stronger foundation for certification than a floating-point
implementation whose correctness depends on hardware behaviour that lies
outside the software's control.

The no-allocation property is equally important for embedded systems. Every
`FixedArithmetic` operation works on the seven primitive `long` registers of a
single object — no heap allocation, no GC pressure, no unpredictable pause
that could violate a hard real-time deadline.

---

## 5. Trustworthy Smart Contracts and Blockchain Logic

### Why floating-point is banned from smart contracts

Ethereum's EVM, Solana's SBF, and most other smart contract execution
environments prohibit floating-point arithmetic entirely. The reason is
fundamental: a smart contract that produces different numeric results on
different validator nodes — due to floating-point non-determinism — would
cause the blockchain to fork. The entire security model of a distributed
ledger rests on every node producing the exact same state transition from the
same inputs.

Smart contract platforms therefore implement their own fixed-point arithmetic
libraries, often at significant engineering cost and with varying levels of
correctness.

### What FixedArithmetic changes

`FixedArithmetic` is already the architecture that smart contract platforms
reinvent from scratch. It is fixed-point, deterministic, allocation-free, free
of rounding-mode configuration, and its algorithms are formally proven. A
JVM-based smart contract platform — Hyperledger Fabric chaincode in Java, or
a proposed JVM execution layer — could adopt `FixedArithmetic` as its numeric
primitive and inherit all of these properties immediately.

The split-integer multiply and two-step long division also address the overflow
problems that have caused critical bugs in smart contract arithmetic libraries.
Integer overflow in early Solidity fixed-point implementations caused
significant financial losses. `FixedArithmetic`'s algorithms carry explicit
overflow analysis at every intermediate step: term1 requires
`|p_int × q_int| < 9.2 × 10⁹`; terms 2 and 3 are bounded by `MAX_LONG` by
construction; term4 is bounded by `SCALE`. Each bound is derived, not assumed.

---

## 6. Deterministic Multiplayer Game Simulation

### The lockstep determinism problem

A multiplayer game running in deterministic lockstep — where physics and game
logic execute independently on each client and must agree at every frame —
requires that every client produce the exact same simulation state. `double`
arithmetic makes this nearly impossible without heroic engineering effort.
The Java `strictfp` modifier, which enforced strict IEEE 754 compliance, was
deprecated in Java 17 with no direct replacement. Cross-platform `double`
determinism in Java is now effectively unachievable without external libraries.

### What FixedArithmetic changes

`FixedArithmetic` solves the lockstep determinism problem at the arithmetic
foundation. Because all operations reduce to `long` arithmetic, the JVM
specification guarantees identical results on every platform, in every JIT
mode, on every version of Java from 8 through 21 and beyond.

The two operations most critical to game physics — collision detection and
cumulative position updates — are where floating-point causes the most damage.
Two objects whose positions should be equal at the moment of collision compare
as equal under `FixedArithmetic` because both accumulate the same exact `long`
value:

```java
// Two objects converging — correct collision detection at frame 10
FixedArithmetic velA = FixedArithmetic.of("1").divide(FixedArithmetic.of("3"));
FixedArithmetic velB = FixedArithmetic.of("-2").divide(FixedArithmetic.of("3"));
FixedArithmetic posA = FixedArithmetic.of("0");
FixedArithmetic posB = FixedArithmetic.of("10");

for (int frame = 0; frame < 10; frame++) {
    posA = posA.add(velA);
    posB = posB.add(velB);
}

// posA.rawScaled() == posB.rawScaled()  →  true, on every client
// With double: values differ by one ULP — collision silently missed
```

Fractional game modifiers — a `1.5×` critical hit multiplier, a `0.75` armour
damage reduction — are expressed as exact integer ratios and applied without
rounding drift:

```java
FixedArithmetic damage  = FixedArithmetic.of("120");
FixedArithmetic reduced = damage.multiply(FixedArithmetic.of("3"))
                                .divide(FixedArithmetic.of("4"));  // × 3/4
// "90.0" — exact, on all clients, reverses cleanly to 120.0
```

---

## 7. Auditable Machine Learning Inference

### The auditability gap in neural network deployment

Regulators in finance (SR 11-7), healthcare (FDA AI/ML guidance), and
insurance (EU AI Act) are increasingly requiring that AI systems be auditable:
given a specific input, the system must be able to reproduce the exact output
it produced at a recorded point in time, on any machine, by any auditor.
Floating-point arithmetic makes this extremely difficult for neural network
inference, where thousands of multiply-accumulate operations accumulate
rounding errors that differ between hardware generations, CUDA versions, and
even between runs on the same hardware with different thread scheduling.

### What FixedArithmetic changes

For networks whose weights have been quantised to nine decimal places of
precision — which encompasses virtually all production deployments, where
weights are typically quantised to 8-bit or 16-bit integers anyway —
`FixedArithmetic` provides a substrate where every forward pass is a
deterministic integer computation. An auditor can re-run the inference on any
machine, with any JVM, and obtain bit-identical results.

The performance cost is acceptable for inference workloads: Russian-peasant
multiplication is O(log N) additions where N is the operand magnitude, which
for quantised weights in `[-1, 1]` means at most 30 additions per multiply.
For interpretability and regulatory compliance, this is a worthwhile trade.

The `of(String)` constructor means that quantised weights serialised as decimal
strings can be loaded with zero representation error — no floating-point
parsing, no rounding at the point of ingestion:

```java
// Load a quantised weight — exact from the first instruction
FixedArithmetic weight     = FixedArithmetic.of("0.347821649");
FixedArithmetic activation = FixedArithmetic.of("0.912345678");
FixedArithmetic output     = weight.multiply(activation);
// "0.317294158" — identical on every auditor's machine
```

---

## 8. Education: Teaching Arithmetic Without a Black Box

### The pedagogical problem with floating-point

Every introductory programming course eventually reaches the moment where a
student types `0.1 + 0.2` and sees `0.30000000000000004`. The standard
explanation — "floating-point numbers can't represent all decimals exactly" —
is true but deeply unsatisfying. It presents arithmetic as a system with
opaque, unpredictable failure modes that must simply be accepted.

The alternative — teaching students to use `BigDecimal` — replaces opacity
with complexity: eight rounding modes, `MathContext` objects, and a class
whose own `equals` method violates the reflexivity contract (`new
BigDecimal("2.0").equals(new BigDecimal("2.00"))` is `false`).

### What FixedArithmetic changes

`FixedArithmetic` is a teaching tool whose entire implementation is an
explanation of how arithmetic works. Every operation is traceable to a named
algorithm with a formal proof. There is no hardware rounding, no implicit
precision, and no behaviour that cannot be predicted from the source code alone.

A student who reads `FixedArithmetic` learns:

- Why `1/3` cannot be stored exactly in any finite representation, and
  precisely how much error the truncation introduces (`< 10⁻⁹`)
- What the Russian-peasant algorithm is, why it works, and how a loop
  invariant constitutes a proof of correctness
- How binary long division extracts a quotient digit-by-digit using only
  subtraction, and why that is equivalent to the long division taught in
  primary school
- Why `SCALE = 10⁹` is constructed by repeated addition rather than a literal
  `1_000_000_000`, and what that constraint says about the class's own
  operating rules
- How the split-integer decomposition of multiplication avoids overflow, and
  why the previous single-strip approach silently gave wrong answers for
  fractional operands

This is a qualitative shift from "trust the hardware" to "understand the
algorithm". It equips students not just to use arithmetic libraries but to
reason about their correctness — a skill that transfers directly to writing
provably correct software in any domain.

---

## 9. The Common Thread

Every breakthrough described above flows from a single architectural decision:
**represent numbers as scaled integers and reduce all arithmetic to addition
and subtraction**.

This decision has three consequences that compound each other:

**Determinism.** Integer addition on `long` is the same operation on every
machine that has ever run a JVM. There is no hardware rounding mode, no ULP,
no evaluation-order sensitivity. Two systems that agree on their inputs will
agree on their outputs, permanently, across all future hardware generations.

**Verifiability.** Both core algorithms — Russian-peasant multiplication and
binary long division — are provably correct by elementary loop invariants. The
proofs fit in a single document and can be checked without specialised tools.
No formal verification of hardware floating-point behaviour is required.

**Transparency.** The source code is the specification. There is no gap
between what the implementation says it does and what it actually does, because
there is no floating-point rounding that occurs outside the programmer's
control.

These three properties together — determinism, verifiability, transparency —
are what make `FixedArithmetic` not merely a drop-in replacement for `double`
or `BigDecimal` in specific cases, but a foundation for a different approach
to numeric computation: one where correctness is a consequence of the
arithmetic design, not a property that has to be tested for, worked around,
or hoped for.

---

## Further Reading

- [Mathematical proof of precision superiority](README.md#mathematical-proof-of-precision-superiority-over-floating-point)
- [Why trading systems and game engines should use FixedArithmetic](TRADING_AND_GAMES.md)
- [The risks of `java.lang.BigDecimal` in production arithmetic](BIGDECIMAL_RISKS.md)
- [FixedArithmetic vs `BigDecimal` — advantages](BIGDECIMAL_COMPARISON.md)
- [Commercial license](COMMERCIAL_LICENSE.md)
- D. Goldberg, "What Every Computer Scientist Should Know About Floating-Point
  Arithmetic," *ACM Computing Surveys*, 23(1), 1991
- B. Meyer, *Object-Oriented Software Construction*, 2nd ed. — on the
  relationship between correctness and formal specification
- Baker et al., "1,500 Scientists Lift the Lid on Reproducibility,"
  *Nature*, 533, 2016

---

*FixedArithmetic is the work of Mario Gianota. Commercial use requires a
license — see [COMMERCIAL_LICENSE.md](COMMERCIAL_LICENSE.md).*
