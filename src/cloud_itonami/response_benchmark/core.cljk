(ns cloud-itonami.response-benchmark.core
  "Aggregate response-time benchmark for corporate transfer fraud, at
  country x sector granularity, and the refusals that keep it aggregate.

  ## Why this exists

  `com-junkawasaki/root` ADR-2607284000 found that the bank's screening
  control in a corporate vishing case fired 4.317 hours into a 23.317-hour
  transfer window -- 18.5% in, comfortably early -- and still had effective
  strength zero. One of its ranked interventions was to publish response
  times at an aggregate level, so that 'was 4.3 hours fast?' becomes a
  question with an answer instead of a feeling.

  It cannot be answered from one incident. That is the whole content of
  this library.

  ## The same 299

  Its sibling `cloud-itonami-pooled-incidence` computes how much exposure
  a claim of 'the rate is at most 1%' needs: 299 incident-free
  company-years. Reporting a p99 response time at 95% confidence needs
  **299 observations** -- the identical number, because it is the
  identical tail arithmetic asked from the other side:

      pooled-incidence/exposure-floor(0.01)  =  ln(0.05)/ln(0.99)  =  299
      observations-needed-for-quantile(0.99) =  ln(0.05)/ln(0.99)  =  299

  So this namespace does not reimplement it. `observations-needed-for-
  quantile` calls `pooled-incidence/exposure-floor` with `(- 1 p)`, which
  makes the identity a fact about the code rather than a claim in a
  comment. Lower quantiles are cheap by comparison: a median needs 5
  observations, a p90 needs 29, a p95 needs 59.

  The bound is distribution-free, from order statistics: with n
  observations, P(max >= q_p) = 1 - p^n, so n >= ln(1-confidence)/ln(p)
  observations are needed before the sample can support a statement about
  the p-quantile at all. Nothing here assumes response times are normal,
  log-normal, or anything else.

  ## Aggregate-only, and why that discipline came from elsewhere

  `etzhayyim/com-etzhayyim-fushin` is a benchmark actor whose
  constitutional discipline is aggregate-only ingestion, mandatory source
  provenance, non-adjudication, and no named-party scenario -- gated so
  that naming an individual body requires Council ratification. That
  discipline is exactly right for a benchmark that could otherwise become
  a list of firms that responded slowly.

  fushin's DOMAIN is municipal infrastructure repair, and its G1/N1 are
  marked immutable at R0, so this benchmark does not live there. The
  discipline transplants anyway, and is enforced here:

  - `:cohort-id` is country x sector. An observation carrying anything
    that identifies a firm is REFUSED, not anonymized -- silently
    stripping an identifier teaches callers that sending one is fine.
  - Every observation carries a source (`:source`, `:observed-at`).
  - Nothing here says a cohort responded badly. A distribution is
    descriptive; this namespace has no notion of a target or a pass mark.

  ## No decision authority

  A plain function library. No I/O, no ledger, no governor, no actor --
  the `cloud-itonami-regulatory-tracker` / `-pooled-incidence` posture."
  (:require [kotoba.lang.text :as str]
            [cloud-itonami.pooled-incidence.core :as pi]))

;; ---------------------------------------------------------------------------
;; Observation shape
;; ---------------------------------------------------------------------------
;;
;; {:cohort-id   {:country "JPN" :sector "6201"}  ; country x sector ONLY
;;  :phase       :bank-warning                     ; which interval this measures
;;  :hours       4.317                             ; the measured duration
;;  :observed-at "2026-07-24"
;;  :source      "..."                             ; citation, never blank
;;  :case-ref    "hatena-2026-07"}                 ; opaque, non-identifying handle

(def phases
  "The response intervals this benchmark distinguishes. They are different
  distributions and are never pooled together -- 'how long until the bank
  warned someone' and 'how long until the incident was published' are not
  the same measurement wearing different labels."
  {:bank-warning   "first fraudulent transfer -> a warning reached the firm"
   :detection      "first fraudulent transfer -> the firm recognised the fraud"
   :containment    "recognition -> outflow stopped"
   :public-report  "recognition -> the incident was published"})

(def observation-keys
  [:cohort-id :phase :hours :observed-at :source :case-ref])

(def named-party-keys
  "Keys whose presence means an observation is about an identifiable firm.
  Their presence is a refusal, not something to strip: quietly removing an
  identifier accepts the submission and teaches the caller that sending one
  is acceptable."
  #{:firm-id :firm-name :company-name :company-id :member-id
    :lei :legal-name :registration-number :domain})

(defn- finite-number? [v]
  (and (number? v) (= v v) (> v ##-Inf) (< v ##Inf)))

(defn- non-blank-string? [v]
  (and (string? v) (not (str/blank? v))))

(defn- valid-cohort? [c]
  (and (map? c)
       (= #{:country :sector} (set (keys c)))
       (every? non-blank-string? (vals c))))

(defn observation-violations
  "Admission discipline for a single response-time observation. Returns a
  vector of `{:rule .. :detail ..}`, empty when admissible."
  [obs]
  (let [{:keys [cohort-id phase hours observed-at source case-ref]} obs
        named (filter (set (keys obs)) named-party-keys)]
    (into []
          (concat
           (when (seq named)
             [{:rule :named-party-refused
               :detail (str "observation carries " (pr-str (vec (sort named)))
                            "; this benchmark is aggregate-only and refuses rather than"
                            " anonymizes -- stripping the key would accept the submission")}])
           (when-not (valid-cohort? cohort-id)
             [{:rule :cohort-invalid
               :detail (str ":cohort-id must be exactly {:country .. :sector ..} with non-blank"
                            " values, got " (pr-str cohort-id))}])
           (when-not (contains? phases phase)
             [{:rule :phase-unknown
               :detail (str (pr-str phase) " is not one of " (pr-str (set (keys phases))))}])
           (when-not (and (finite-number? hours) (not (neg? hours)))
             [{:rule :hours-invalid
               :detail (str ":hours must be a finite non-negative number, got " (pr-str hours))}])
           (when-not (non-blank-string? observed-at)
             [{:rule :observed-at-missing :detail ":observed-at is required"}])
           (when-not (non-blank-string? source)
             [{:rule :source-missing
               :detail ":source is required -- a benchmark number with no citation cannot be checked"}])
           (when-not (non-blank-string? case-ref)
             [{:rule :case-ref-missing
               :detail ":case-ref is required as an opaque handle so a number can be traced without naming a firm"}])))))

(defn admissible? [obs] (empty? (observation-violations obs)))

;; ---------------------------------------------------------------------------
;; Cohorts
;; ---------------------------------------------------------------------------

(defn cohort
  "Group admissible observations into ONE cohort, or refuse.

  Returns

    {:cohort-id .. :phase .. :n .. :hours [sorted]
     :admitted [..] :rejected [{:observation .. :violations [..]}]
     :refusal nil | {:rule .. :detail ..}}

  `:n` and `:hours` are `nil` -- not `0` and not `[]` -- whenever
  `:refusal` is set, for the same reason its sibling library reports a
  refused pool as `nil`: an empty benchmark and a refused one must not
  share a representation.

  Refuses on `:no-admissible-observations`, `:mixed-cohort` (different
  country x sector), and `:mixed-phase`."
  [observations]
  (let [{admitted true rejected false} (group-by admissible? (vec observations))
        rejected (mapv (fn [o] {:observation o :violations (observation-violations o)}) rejected)
        cohorts (set (map :cohort-id admitted))
        phs (set (map :phase admitted))
        refusal (cond
                  (empty? admitted)
                  {:rule :no-admissible-observations
                   :detail (str "none of the " (count rejected) " supplied observations passed admission")}

                  (> (count cohorts) 1)
                  {:rule :mixed-cohort
                   :detail (str "observations span more than one country x sector: " (pr-str cohorts))}

                  (> (count phs) 1)
                  {:rule :mixed-phase
                   :detail (str "observations span more than one phase: " (pr-str phs)
                                ". These are different distributions, not one measurement"
                                " wearing different labels.")})]
    (cond-> {:cohort-id (when (= 1 (count cohorts)) (first cohorts))
             :phase (when (= 1 (count phs)) (first phs))
             :admitted (vec admitted)
             :rejected rejected
             :refusal refusal}
      (nil? refusal) (assoc :n (count admitted)
                            :hours (vec (sort (map :hours admitted))))
      (some? refusal) (assoc :n nil :hours nil))))

;; ---------------------------------------------------------------------------
;; Quantiles
;; ---------------------------------------------------------------------------

(defn observations-needed-for-quantile
  "How many observations a cohort needs before it can support a statement
  about the `p`-quantile at `confidence`.

  Distribution-free, from order statistics: with n observations,
  P(max >= q_p) = 1 - p^n, so n >= ln(1-confidence)/ln(p).

  Delegates to `cloud-itonami.pooled-incidence.core/exposure-floor` with
  `(- 1 p)`, because that is literally the same expression --
  ln(1-confidence)/ln(1-(1-p)). Calling it rather than restating it is
  what keeps the two libraries from drifting into two different answers to
  one question.

    median (p=0.50)  ->   5
    p90              ->  29
    p95              ->  59
    p99              -> 299   <- the same 299 as exposure-floor(0.01)"
  [p & {:keys [confidence] :or {confidence 0.95}}]
  {:pre [(< 0 p 1) (< 0 confidence 1)]}
  (pi/exposure-floor (- 1 p) :confidence confidence))

(defn- ceil* [x]
  #?(:clj (long (Math/ceil (double x))) :cljs (js/Math.ceil x)))

(defn- quantile-at
  "The p-quantile of an ascending vector, nearest-rank: the smallest
  observation at or above rank ceil(p*n). Nearest-rank rather than an
  interpolating method because interpolation invents a value that was
  never observed, which is the wrong default for a benchmark whose whole
  posture is that only observations are reportable."
  [sorted p]
  (let [n (count sorted)
        idx (max 0 (min (dec n) (dec (ceil* (* p n)))))]
    (nth sorted idx)))

(defn quantile-verdict
  "Can this cohort support a statement about the `p`-quantile?

    {:verdict :reported  :p .. :hours .. :n .. :needed ..}
    {:verdict :insufficient-observations :p .. :n .. :needed .. :deficit ..}
    {:verdict :refused   :refusal {..}}

  An underpowered cohort gets `:deficit`, not a number. This is the
  failure the library exists to prevent: a p90 computed from five
  incidents looks exactly like a p90 computed from five hundred, and only
  one of them means anything."
  [c p & {:keys [confidence] :or {confidence 0.95}}]
  (if-let [r (:refusal c)]
    {:verdict :refused :refusal r}
    (let [needed (observations-needed-for-quantile p :confidence confidence)
          n (:n c)
          base {:p p :n n :needed needed :confidence confidence
                :cohort-id (:cohort-id c) :phase (:phase c)}]
      (if (>= n needed)
        (assoc base :verdict :reported :hours (quantile-at (:hours c) p))
        (assoc base :verdict :insufficient-observations :deficit (- needed n))))))

(defn summary
  "Everything the cohort can honestly say, and nothing it cannot.

  `:min` and `:max` are order statistics of the sample and are always
  reportable as such -- they are facts about the observations, not
  estimates of the population. `:quantiles` reports each requested
  quantile's verdict, so an unreportable p90 appears as
  `:insufficient-observations` rather than being omitted (an omitted
  quantile reads as 'not asked for'; a refused one reads as 'not enough
  data', and those are different)."
  [c & {:keys [ps confidence] :or {ps [0.5 0.9 0.95 0.99] confidence 0.95}}]
  (if-let [r (:refusal c)]
    {:refusal r}
    {:cohort-id (:cohort-id c)
     :phase (:phase c)
     :n (:n c)
     :min (first (:hours c))
     :max (last (:hours c))
     :quantiles (into {} (for [p ps] [p (quantile-verdict c p :confidence confidence)]))}))
