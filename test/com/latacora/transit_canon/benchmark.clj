(ns com.latacora.transit-canon.benchmark
  "Benchmarks for transit-canon.

  Run from the REPL with (run-benchmarks) or individual bench calls.
  Uses criterium for statistically rigorous JVM benchmarking:
  explicit warmup, GC control, confidence intervals."
  (:require
   [cognitect.transit :as transit]
   [com.latacora.transit-canon.core :as canon]
   [criterium.core :as crit])
  (:import
   [java.io ByteArrayOutputStream]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- plain-serialize ^bytes [obj]
  (let [out (ByteArrayOutputStream.)]
    (-> out (transit/writer :json) (transit/write obj))
    (.toByteArray out)))

(defn- canon-serialize ^bytes [obj]
  (canon/serialize obj {:compress? false}))

;; ---------------------------------------------------------------------------
;; Datasets
;; ---------------------------------------------------------------------------

(def small-map {:a 1 :b 2 :c 3})

(def medium-int-map
  (zipmap (map #(keyword (str "k" %)) (range 100))
          (range 100)))

(def large-int-map
  (zipmap (map #(keyword (str "k" %)) (range 1000))
          (range 1000)))

;; Float-heavy: specifically exercises emitDouble on every value
(def medium-float-map
  (zipmap (map #(keyword (str "k" %)) (range 100))
          (map #(* % 1.1) (range 100))))

(def large-float-map
  (zipmap (map #(keyword (str "k" %)) (range 1000))
          (map #(* % 1.1) (range 1000))))

;; Whole-number doubles: exercises the (== d (long d)) branch in canonical-float-str
(def whole-double-map
  (zipmap (map #(keyword (str "k" %)) (range 1000))
          (map double (range 1000))))

(def nested-map
  {:level1 {:level2 {:level3 {:level4 {:level5 "deep"}}}}
   :siblings (mapv #(hash-map :id % :name (str "item" %)) (range 50))})

(def mixed-types
  {:string "hello"
   :int 42
   :float 3.14159
   :bool true
   :nil nil
   :keyword :example
   :vector [1 2 3 4 5]
   :nested {:a {:b {:c 1}}}})

;; ---------------------------------------------------------------------------
;; Benchmark runners
;; ---------------------------------------------------------------------------

(defn bench-pair
  "Run criterium quick-bench on plain and canon serialization of obj.
  Returns {:plain :canon} each with criterium result maps."
  [obj]
  {:plain (crit/quick-benchmark (plain-serialize obj) {})
   :canon (crit/quick-benchmark (canon-serialize obj) {})})

(defn report-pair
  "Print a comparison summary for one dataset."
  [label obj]
  (println (str "\n=== " label " ==="))
  (let [{:keys [plain canon]} (bench-pair obj)
        plain-ns (-> plain :mean first (* 1e9))
        canon-ns (-> canon :mean first (* 1e9))]
    (println (format "  plain: %.0f ns  (±%.0f ns)"
                     plain-ns
                     (* (-> plain :variance first) 1e9 0.5)))
    (println (format "  canon: %.0f ns  (±%.0f ns)"
                     canon-ns
                     (* (-> canon :variance first) 1e9 0.5)))
    (println (format "  overhead: %.2fx" (/ canon-ns plain-ns)))))

(defn run-benchmarks
  "Run all benchmarks. Takes ~5-10 minutes due to criterium warmup."
  []
  (println "transit-canon benchmarks (criterium quick-bench, 6 warmup + 60 sample iterations)")
  (report-pair "small map (ints)"        small-map)
  (report-pair "medium map (ints)"       medium-int-map)
  (report-pair "large map (ints)"        large-int-map)
  (report-pair "medium map (floats)"     medium-float-map)
  (report-pair "large map (floats)"      large-float-map)
  (report-pair "whole-number doubles"    whole-double-map)
  (report-pair "nested structure"        nested-map)
  (report-pair "mixed types"             mixed-types))

(comment
  ;; Run everything
  (run-benchmarks)

  ;; Single dataset — fast iteration while changing code
  (crit/quick-bench (canon-serialize large-float-map))
  (crit/quick-bench (plain-serialize large-float-map))

  ;; Compare float vs int maps to isolate emitDouble cost
  (crit/quick-bench (canon-serialize large-int-map))
  (crit/quick-bench (canon-serialize large-float-map))

  ;; Full bench (slower, more accurate) for a final measurement
  (crit/bench (canon-serialize large-float-map)))
