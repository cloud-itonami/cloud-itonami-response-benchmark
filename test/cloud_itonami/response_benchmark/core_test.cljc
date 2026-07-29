(ns cloud-itonami.response-benchmark.core-test
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer [deftest is testing]])
            #?(:cljs [cljs.reader :as reader])
            #?(:cljs ["fs" :as fs])
            [cloud-itonami.pooled-incidence.core :as pi]
            [cloud-itonami.response-benchmark.core :as rb]))

(def seed
  #?(:clj (read-string (slurp "resources/response-benchmark-seed.edn"))
     :cljs (reader/read-string (fs/readFileSync "resources/response-benchmark-seed.edn" "utf8"))))

(defn obs [& {:as overrides}]
  (merge {:cohort-id {:country "JPN" :sector "6201"}
          :phase :bank-warning
          :hours 4.317
          :observed-at "2026-07-24"
          :source "特別調査委員会報告書 (2026-07-24) の報道"
          :case-ref "jp-corporate-vishing-2026-07"}
         overrides))

(defn cohort-of
  "n observations with ascending hours, distinct case-refs."
  [n]
  (mapv #(obs :hours (+ 1.0 %) :case-ref (str "case-" %)) (range n)))

;; ---------------------------------------------------------------------------
;; the same 299
;; ---------------------------------------------------------------------------

(deftest quantile-floor-is-the-same-arithmetic-as-the-rate-floor
  ;; The identity this library is built around: reporting a p99 response
  ;; time and claiming a <=1% annual rate need the same number of
  ;; observations, because they are the same tail asked from two sides.
  (is (= 299 (rb/observations-needed-for-quantile 0.99)))
  (is (= 299 (pi/exposure-floor 0.01)))
  (is (= (pi/exposure-floor 0.01) (rb/observations-needed-for-quantile 0.99)))
  (testing "and it is delegated, not restated -- every p agrees with the sibling"
    (doseq [p [0.5 0.75 0.9 0.95 0.99 0.999]]
      (is (= (pi/exposure-floor (- 1 p)) (rb/observations-needed-for-quantile p))
          (str "p=" p))))
  (testing "the cheaper quantiles"
    (is (= 5 (rb/observations-needed-for-quantile 0.5)))
    (is (= 29 (rb/observations-needed-for-quantile 0.9)))
    (is (= 59 (rb/observations-needed-for-quantile 0.95))))
  (testing "more confidence costs more observations"
    (is (> (rb/observations-needed-for-quantile 0.9 :confidence 0.99)
           (rb/observations-needed-for-quantile 0.9 :confidence 0.95)))))

;; ---------------------------------------------------------------------------
;; aggregate-only
;; ---------------------------------------------------------------------------

(deftest a-named-party-is-refused-not-anonymized
  ;; Stripping the identifier would accept the submission and teach the
  ;; caller that sending one is fine. The refusal is the point.
  (doseq [k rb/named-party-keys]
    (let [o (obs k "something-identifying")
          v (rb/observation-violations o)]
      (is (some #{:named-party-refused} (map :rule v)) (str k " must be refused"))))
  (testing "and it refuses the whole cohort rather than dropping the observation quietly"
    (let [c (rb/cohort [(obs) (obs :firm-name "some firm" :case-ref "c2")])]
      (is (= 1 (count (:rejected c))))
      (is (= [:named-party-refused] (mapv :rule (:violations (first (:rejected c)))))))))

(deftest cohort-id-must-be-exactly-country-and-sector
  (is (= [:cohort-invalid] (mapv :rule (rb/observation-violations (obs :cohort-id {:country "JPN"})))))
  (is (= [:cohort-invalid] (mapv :rule (rb/observation-violations
                                        (obs :cohort-id {:country "JPN" :sector "6201" :city "Kyoto"})))))
  (is (= [:cohort-invalid] (mapv :rule (rb/observation-violations (obs :cohort-id {:country "" :sector "6201"})))))
  (is (= [:cohort-invalid] (mapv :rule (rb/observation-violations (obs :cohort-id "JPN/6201"))))))

(deftest every-observation-carries-a-source
  (is (= [:source-missing] (mapv :rule (rb/observation-violations (obs :source "  ")))))
  (is (= [:case-ref-missing] (mapv :rule (rb/observation-violations (obs :case-ref nil)))))
  (is (= [:observed-at-missing] (mapv :rule (rb/observation-violations (obs :observed-at nil)))))
  (is (= [:phase-unknown] (mapv :rule (rb/observation-violations (obs :phase :whenever)))))
  (is (= [:hours-invalid] (mapv :rule (rb/observation-violations (obs :hours -1)))))
  (is (= [:hours-invalid] (mapv :rule (rb/observation-violations (obs :hours ##Inf))))))

;; ---------------------------------------------------------------------------
;; refusals
;; ---------------------------------------------------------------------------

(deftest refused-cohort-reports-nil-never-zero
  (doseq [[label observations]
          [["nothing admissible" [(obs :source nil)]]
           ["mixed cohort" [(obs) (obs :cohort-id {:country "DEU" :sector "6201"} :case-ref "c2")]]
           ["mixed phase" [(obs) (obs :phase :detection :case-ref "c2")]]]]
    (let [c (rb/cohort observations)]
      (is (some? (:refusal c)) label)
      (is (nil? (:n c)) (str label " -- n must be nil, not 0"))
      (is (nil? (:hours c)) (str label " -- hours must be nil, not []"))
      (is (= :refused (:verdict (rb/quantile-verdict c 0.5))) label)
      (is (some? (:refusal (rb/summary c))) label))))

(deftest phases-are-separate-distributions
  (is (= :mixed-phase (:rule (:refusal (rb/cohort [(obs :phase :bank-warning)
                                                   (obs :phase :detection :case-ref "c2")])))))
  (testing "each phase alone is fine"
    (doseq [p (keys rb/phases)]
      (is (nil? (:refusal (rb/cohort [(obs :phase p)])))))))

;; ---------------------------------------------------------------------------
;; the seed -- one case, and what it cannot say
;; ---------------------------------------------------------------------------

(deftest one-case-cannot-support-even-a-median
  ;; The seed holds three observations from ONE case, one per phase, so
  ;; every phase cohort has n=1. A median needs 5.
  (let [c (rb/cohort [(obs)])
        v (rb/quantile-verdict c 0.5)]
    (is (= 1 (:n c)))
    (is (= :insufficient-observations (:verdict v)))
    (is (= 5 (:needed v)))
    (is (= 4 (:deficit v)))
    (is (not (contains? v :hours)) "no number leaks out of an insufficient verdict"))
  (testing "and certainly not a p90 or p99"
    (let [c (rb/cohort [(obs)])]
      (is (= 28 (:deficit (rb/quantile-verdict c 0.9))))
      (is (= 298 (:deficit (rb/quantile-verdict c 0.99)))))))

(deftest min-and-max-are-facts-about-the-sample-and-always-reportable
  ;; Order statistics of the observations themselves are not estimates, so
  ;; they are not gated. n=1 can honestly say "the one response we have
  ;; took 4.317 hours"; it cannot say "the median is 4.317 hours".
  (let [s (rb/summary (rb/cohort [(obs)]))]
    (is (= 1 (:n s)))
    (is (= 4.317 (:min s)))
    (is (= 4.317 (:max s)))
    (is (= :insufficient-observations (get-in s [:quantiles 0.5 :verdict])))))

(deftest unreportable-quantiles-appear-rather-than-being-omitted
  ;; An omitted quantile reads as "not asked for"; a refused one reads as
  ;; "not enough data". Those are different and the summary keeps them so.
  (let [s (rb/summary (rb/cohort (cohort-of 10)))]
    (is (= #{0.5 0.9 0.95 0.99} (set (keys (:quantiles s)))))
    (is (= :reported (get-in s [:quantiles 0.5 :verdict])) "n=10 supports a median")
    (is (= :insufficient-observations (get-in s [:quantiles 0.9 :verdict])) "but not a p90")
    (is (= 19 (get-in s [:quantiles 0.9 :deficit])))))

;; ---------------------------------------------------------------------------
;; quantiles once there are enough
;; ---------------------------------------------------------------------------

(deftest a-sufficient-cohort-reports-an-observed-value
  (let [c (rb/cohort (cohort-of 29))
        v (rb/quantile-verdict c 0.9)]
    (is (= :reported (:verdict v)))
    (is (= 29 (:n v)))
    (is (= 29 (:needed v)))
    (is (some #{(:hours v)} (:hours c))
        "nearest-rank returns an observation, never an interpolated value that was never seen"))
  (testing "one short and it refuses"
    (is (= :insufficient-observations (:verdict (rb/quantile-verdict (rb/cohort (cohort-of 28)) 0.9))))
    (is (= 1 (:deficit (rb/quantile-verdict (rb/cohort (cohort-of 28)) 0.9))))))

(deftest quantile-is-monotone-and-bounded-by-the-sample
  (let [c (rb/cohort (cohort-of 299))
        q (fn [p] (:hours (rb/quantile-verdict c p)))]
    (is (<= (q 0.5) (q 0.9) (q 0.95) (q 0.99)))
    (is (>= (q 0.99) (first (:hours c))))
    (is (<= (q 0.99) (last (:hours c))))
    (is (= 299 (:n c)) "299 observations is what a p99 costs")))

(deftest nothing-here-adjudicates
  ;; There is no target, no pass mark, and no verdict about a cohort's
  ;; performance anywhere in the returned shapes -- the benchmark is
  ;; descriptive, matching the discipline it inherited.
  (let [s (rb/summary (rb/cohort (cohort-of 29)))
        ks (set (concat (keys s) (mapcat keys (vals (:quantiles s)))))]
    (is (empty? (filter #(re-find #"(?i)target|pass|fail|grade|rank|score|good|bad|compliant" (name %)) ks))
        (str "adjudicating key found in " (pr-str ks)))))

;; ---------------------------------------------------------------------------
;; the shipped seed
;; ---------------------------------------------------------------------------

(deftest shipped-seed-is-admissible-and-aggregate-only
  (is (= 3 (count seed)))
  (doseq [o seed]
    (is (= [] (rb/observation-violations o)) (str (:phase o))))
  (testing "nothing in the shipped data identifies a firm"
    (doseq [o seed]
      (is (empty? (filter (set (keys o)) rb/named-party-keys)))
      (is (= #{:country :sector} (set (keys (:cohort-id o))))))))

(deftest shipped-seed-is-one-case-and-says-so
  ;; Three observations, three phases, ONE case. Every phase cohort is
  ;; n=1. This is the finding, not a gap in the fixture: the benchmark
  ;; this library exists to publish cannot yet be published.
  (is (= #{"jp-corporate-vishing-2026-07"} (set (map :case-ref seed))))
  (is (= #{:bank-warning :detection :public-report} (set (map :phase seed))))
  (testing "pooling the phases together is refused"
    (is (= :mixed-phase (:rule (:refusal (rb/cohort seed))))))
  (testing "and each phase alone has n=1, which supports no quantile at all"
    (doseq [p (set (map :phase seed))]
      (let [c (rb/cohort (filter #(= p (:phase %)) seed))
            s (rb/summary c)]
        (is (= 1 (:n c)) (str p))
        (is (= (:min s) (:max s)) (str p " -- one observation is its own min and max"))
        (doseq [q [0.5 0.9 0.95 0.99]]
          (is (= :insufficient-observations (get-in s [:quantiles q :verdict]))
              (str p " q=" q)))))))

(deftest the-case-numbers-survive-the-round-trip
  ;; The three intervals ADR-2607284000 measured, readable back out of the
  ;; benchmark rather than restated in prose.
  (let [by-phase (into {} (map (juxt :phase :hours)) seed)]
    (is (= 4.317 (:bank-warning by-phase)) "warning reached the firm 4.317h in")
    (is (= 26.967 (:detection by-phase)) "fraud recognised at 26.967h")
    (is (= 2280 (:public-report by-phase)) "published 95 days = 2280h after recognition")
    (testing "detection landed after the 23.317h transfer window had closed"
      (is (> (:detection by-phase) 23.317)))))
