# ADR-0001 — publish aggregates, refuse percentiles the sample cannot support

- Status: accepted
- Date: 2026-07-29
- Upstream: `com-junkawasaki/root` ADR-2607284000 (corporate vishing fraud —
  system dynamics and interventions)

## Context

ADR-2607284000 ranked eighteen interventions against a corporate vishing
case. `:fushin/aggregate-benchmark` — publish corporate fraud
response-time benchmarks at country and sector level — scored 4.55,
band/B, third overall, and was the highest-scoring intervention nobody had
built. Its rationale was that the case's response times (a bank warning at
4.317 hours into a 23.317-hour window; recognition at 26.967 hours; 95
days to publication) are meaningless in isolation: nobody can say whether
4.3 hours was fast without a distribution to compare against.

The ranking named `etzhayyim/com-etzhayyim-fushin` as the asset, on the
strength of its constitutional discipline — aggregate-only ingestion,
mandatory provenance, non-adjudication, named-party scenarios gated behind
Council ratification. That discipline is exactly right here.

Reading fushin's charter showed the domain is not. fushin benchmarks
**municipal infrastructure repair** (MLIT road maintenance, MHLW water
pipes, World Bank/ASCE/IBNET), its G1 is aggregate-only ingestion *of
municipality data*, its N1 is "not a real-municipality audit", and the
whole discipline block is marked **immutable at R0**. Private companies
and financial crime are a different domain, and extending G1 would mean
overriding that immutability.

The owner chose a new `cloud-itonami` commons rather than amending
fushin's charter or standing up a new etzhayyim actor.

## Decision

A plain `.cljc` function library in the `cloud-itonami` family.

**The discipline transplants even though the domain does not.**
Aggregate-only is enforced structurally: `:cohort-id` must be exactly
`{:country .. :sector ..}`, and an observation carrying any identifying
key (`:firm-name`, `:lei`, `:domain`, …) is **refused rather than
anonymized**. Stripping the key would accept the submission and teach the
caller that sending one is acceptable; refusing teaches the opposite. This
is the one property that made fushin the natural home, and it survives the
move.

**Nothing adjudicates.** No target, no pass mark, no verdict about a
cohort. A test greps every returned shape for adjudicating vocabulary and
fails if any appears — a benchmark that acquires a pass mark has become a
ranking, and that is a different artifact requiring a different mandate.

**The quantile floor is delegated, not restated.** Reporting a p-quantile
at a confidence level needs `n ≥ ln(1−confidence)/ln(p)` observations,
distribution-free from order statistics. That is algebraically identical
to `pooled-incidence/exposure-floor(1−p)`, so this library *calls* it. At
p=0.99 both return **299** — the same 299 as "claim a ≤1% annual rate",
because it is the same tail asked from the other side. Calling rather than
restating is what keeps two libraries from drifting into two answers to
one question.

**Phases never pool.** `:bank-warning`, `:detection`, `:containment` and
`:public-report` are separate distributions; mixing them is a refusal.

**A refused cohort reports `nil`, not `0`/`[]`** — matching
`pooled-incidence`, and for the same reason: an empty benchmark and a
refused one must not share a representation.

## Consequences

The shipped seed is one case, and every phase cohort has `n=1`. A median
needs 5. So the library's first honest output is
`{:verdict :insufficient-observations :needed 5 :deficit 4}`.

That is the finding rather than a gap in the fixture, and it is worth
stating plainly: **the benchmark this library exists to publish cannot yet
be published.** The value delivered today is that the gap is now a
computed number with a deficit attached, instead of an impression that
more data would be nice.

`:min`/`:max` are deliberately ungated, being order statistics of the
sample rather than estimates of a population — `n=1` may say "the one
response we have took 4.317 hours" and may not say "the median is 4.317
hours". An unreportable quantile is *reported as unreportable* rather than
omitted, because an omitted key reads as "not asked for".

### Known gaps

**Nearest-rank only.** No interpolating quantile method, deliberately:
interpolation invents a value that was never observed, which is the wrong
default for a benchmark whose posture is that only observations are
reportable. A caller wanting an interpolated estimate needs a different
function with a different name, not a flag on this one.

**No cross-cohort comparison.** Nothing here compares JPN×6201 against
DEU×6419. That is a short step from ranking, and doing it safely needs a
mandate this library does not have.

**One case, one country, one sector.** Whether Japanese corporate response
times differ from anywhere else is exactly the kind of question this
library exists to answer and currently cannot.

**Sector granularity is unvalidated.** ISIC codes are used to match the
`cloud-itonami` family convention, but whether ISIC is the right cut for
fraud-response behaviour — as opposed to firm size, or bank relationship —
is an open empirical question, and picking it now is a guess this ADR is
naming rather than hiding.
