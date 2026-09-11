# cloud-itonami/cloud-itonami-response-benchmark

Aggregate response-time benchmark for corporate transfer fraud, at
**country × sector** granularity, and the refusals that keep it aggregate.

A plain `.cljc` function library — no I/O, no ledger, no governor, no
actor. Same posture as its siblings
[`cloud-itonami-regulatory-tracker`](https://github.com/cloud-itonami/cloud-itonami-regulatory-tracker)
and
[`cloud-itonami-pooled-incidence`](https://github.com/cloud-itonami/cloud-itonami-pooled-incidence).

## Why this exists

`com-junkawasaki/root` ADR-2607284000 found that a bank's screening
control fired **4.317 hours into a 23.317-hour transfer window** — 18.5%
in, comfortably early — and still had effective strength zero. One of its
ranked interventions was to publish response times at an aggregate level,
so that *"was 4.3 hours fast?"* becomes a question with an answer instead
of a feeling.

It cannot be answered from one incident. That is the whole content of this
library.

## The same 299

Its sibling `cloud-itonami-pooled-incidence` computes how much exposure a
claim of *"the rate is at most 1%"* needs: **299** incident-free
company-years. Reporting a **p99 response time** at 95% confidence needs
**299 observations** — the identical number, because it is the identical
tail arithmetic asked from the other side:

```
pooled-incidence/exposure-floor(0.01)   =  ln(0.05)/ln(0.99)  =  299
observations-needed-for-quantile(0.99)  =  ln(0.05)/ln(0.99)  =  299
```

So this library does not reimplement it. `observations-needed-for-quantile`
calls `pooled-incidence/exposure-floor` with `(- 1 p)`, which makes the
identity a fact about the code rather than a claim in a comment — and a
test asserts the two agree across every `p`.

Lower quantiles are cheap by comparison:

| statistic | observations needed (95% confidence) |
|---|---|
| median | **5** |
| p90 | **29** |
| p95 | **59** |
| p99 | **299** |

The bound is distribution-free, from order statistics: with *n*
observations, `P(max ≥ q_p) = 1 − pⁿ`, so `n ≥ ln(1−confidence)/ln(p)`.
Nothing here assumes response times are normal, log-normal, or anything
else.

## What the shipped seed can and cannot say

`resources/response-benchmark-seed.edn` holds **one case** — three
observations, one per phase, all from the ADR's incident:

```clojure
:bank-warning   4.317 h      ; warning reached the firm
:detection     26.967 h      ; fraud recognised — after the 23.317h window closed
:public-report  2280 h       ; 95 days to publication
```

Every phase cohort therefore has `n=1`, and:

```clojure
(quantile-verdict cohort 0.5)
;=> {:verdict :insufficient-observations :n 1 :needed 5 :deficit 4}
```

**That is the finding, not a gap in the fixture.** The benchmark this
library exists to publish cannot yet be published, and the tests assert it
refuses rather than manufacturing a percentile.

`:min` and `:max` are *not* gated — they are order statistics of the
sample, facts about the observations rather than estimates of a
population. `n=1` can honestly say "the one response we have took 4.317
hours". It cannot say "the median is 4.317 hours".

An unreportable quantile appears in `summary` as
`:insufficient-observations` rather than being omitted: an omitted
quantile reads as *not asked for*, a refused one reads as *not enough
data*, and those are different.

## Aggregate-only, and where that discipline came from

[`etzhayyim/com-etzhayyim-fushin`](https://github.com/etzhayyim/com-etzhayyim-fushin)
is a benchmark actor whose constitutional discipline is aggregate-only
ingestion, mandatory source provenance, non-adjudication, and no
named-party scenario — gated so that naming an individual body requires
Council ratification. That discipline is exactly right for a benchmark
that could otherwise become a list of firms that responded slowly.

fushin's **domain** is municipal infrastructure repair, and its G1/N1 are
marked immutable at R0, so this benchmark does not live there. The
discipline transplants anyway and is enforced here:

- `:cohort-id` is `{:country .. :sector ..}` and nothing else. An
  observation carrying `:firm-name`, `:lei`, `:domain`, or any other
  identifying key is **refused, not anonymized** — silently stripping an
  identifier accepts the submission and teaches the caller that sending
  one is fine.
- Every observation carries `:source`, `:observed-at` and an opaque
  `:case-ref`, so a number can be traced to a citation without naming
  anyone.
- Nothing here adjudicates. There is no target, no pass mark, and no
  verdict about a cohort's performance — a test greps the returned shapes
  for `target|pass|fail|grade|rank|score|good|bad|compliant` and fails if
  one appears.

## Refusals

`cohort` refuses rather than producing a number on:

- **`:no-admissible-observations`**
- **`:mixed-cohort`** — observations spanning more than one country ×
  sector
- **`:mixed-phase`** — "how long until the bank warned someone" and "how
  long until the incident was published" are different distributions, not
  one measurement wearing different labels

A refused cohort reports `:n` and `:hours` as `nil` — never `0` and never
`[]` — for the same reason `pooled-incidence` reports a refused pool as
`nil`: an empty benchmark and a refused one must not share a
representation.

## Running

```bash
# nbb
kbb --backend sci --classpath "src:test:resources:../cloud-itonami-pooled-incidence/src:../../kotoba-lang/dynamics/src" \
    test/run_tests.cljk

# JVM
kbb -M:test
kbb -M:lint
```

## License

AGPL-3.0-or-later, matching the `cloud-itonami-*` convention.
