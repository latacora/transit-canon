(ns com.latacora.transit-canon.benchmark
  "Benchmarks comparing transit-canon to plain Transit."
  (:require
   [cognitect.transit :as transit]
   [com.latacora.transit-canon.core :as canon])
  (:import
   [java.io ByteArrayOutputStream ByteArrayInputStream]))

(defn plain-transit-serialize
  "Serialize with plain Transit (no canonicalization)."
  ^bytes [obj]
  (let [out (ByteArrayOutputStream.)]
    (-> out
        (transit/writer :json)
        (transit/write obj))
    (.toByteArray out)))

(defn plain-transit-roundtrip
  "Full roundtrip with plain Transit."
  [obj]
  (let [bytes (plain-transit-serialize obj)]
    (-> bytes
        ByteArrayInputStream.
        (transit/reader :json)
        transit/read)))

;; Test data generators
(defn small-map []
  {:a 1 :b 2 :c 3})

(defn medium-map []
  (zipmap (map #(keyword (str "key" %)) (range 100))
          (range 100)))

(defn large-map []
  (zipmap (map #(keyword (str "key" %)) (range 1000))
          (range 1000)))

(defn nested-map []
  {:level1 {:level2 {:level3 {:level4 {:level5 "deep"}}}}
   :siblings (mapv #(hash-map :id % :name (str "item" %)) (range 50))})

(defn map-with-sets []
  {:users #{:alice :bob :charlie :diana :eve}
   :roles #{:admin :user :guest}
   :permissions #{:read :write :delete :create}})

(defn mixed-types []
  {:string "hello"
   :int 42
   :float 3.14159
   :bool true
   :nil nil
   :keyword :example
   :symbol 'my-symbol
   :vector [1 2 3 4 5]
   :list '(a b c)
   :set #{:x :y :z}
   :nested {:a {:b {:c 1}}}})

;; Timing utilities
(defmacro time-ms
  "Return elapsed time in milliseconds."
  [& body]
  `(let [start# (System/nanoTime)
         _# (do ~@body)
         end# (System/nanoTime)]
     (/ (- end# start#) 1000000.0)))

(defn benchmark-serialize
  "Benchmark serialization, return {:plain-ms :canon-ms :overhead-x}."
  [data iterations]
  (let [;; Warm up
        _ (dotimes [_ 100]
            (plain-transit-serialize data)
            (canon/serialize data {:compress? false}))
        ;; Measure plain Transit
        plain-ms (time-ms
                  (dotimes [_ iterations]
                    (plain-transit-serialize data)))
        ;; Measure canon
        canon-ms (time-ms
                  (dotimes [_ iterations]
                    (canon/serialize data {:compress? false})))]
    {:plain-ms plain-ms
     :canon-ms canon-ms
     :overhead-x (/ canon-ms plain-ms)}))

(defn run-benchmarks
  "Run all benchmarks and print results."
  []
  (let [iterations 1000
        tests [["Small map (3 keys)" (small-map)]
               ["Medium map (100 keys)" (medium-map)]
               ["Large map (1000 keys)" (large-map)]
               ["Nested structure" (nested-map)]
               ["Maps with sets" (map-with-sets)]
               ["Mixed types" (mixed-types)]]]

    (println "=== transit-canon Benchmark ===")
    (println (str "Iterations per test: " iterations))
    (println)
    (println (format "%-25s %10s %10s %10s" "Test" "Plain(ms)" "Canon(ms)" "Overhead"))
    (println (apply str (repeat 57 "-")))

    (doseq [[name data] tests]
      (let [{:keys [plain-ms canon-ms overhead-x]} (benchmark-serialize data iterations)]
        (println (format "%-25s %10.1f %10.1f %9.1fx"
                         name plain-ms canon-ms overhead-x))))

    (println)
    (println "Note: Canon includes sorting, number normalization, RFC 8785 canonicalization.")
    (println "      Compression disabled for fair comparison.")))

(defn -main [& _]
  (run-benchmarks))

;; For REPL use
(comment
  (run-benchmarks))
