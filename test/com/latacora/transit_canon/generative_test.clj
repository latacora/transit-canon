(ns com.latacora.transit-canon.generative-test
  "Property-based tests for canonical serialization.

  These tests use test.check to generate random data structures
  and verify that canonicalization properties hold."
  (:require
   [clojure.test :as t :refer [deftest is]]
   [clojure.test.check.generators :as gen]
   [com.gfredericks.test.chuck.clojure-test :refer [checking]]
   [com.latacora.transit-canon.core :as canon]))

;; Generator for values that can be serialized and maintain equality
;; Note: We avoid whole-number doubles due to Transit/JSON canonicalization issues
(def serializable-scalar-gen
  "Generator for scalar values that serialize correctly."
  (gen/one-of
   [gen/boolean
    gen/small-integer
    gen/large-integer
    ;; Avoid doubles with zero fractional parts (e.g., 1.0, 2.0)
    ;; because JSON canonicalization converts 1.0 to 1
    (->> (gen/double* {:infinite? false :NaN? false})
         (gen/fmap (fn [x]
                     ;; Ensure non-zero fractional part
                     (let [bounded (mod x 1e15)]
                       (+ bounded 0.01 (* 0.99 (rand))))))
         (gen/such-that (fn [x] (not= 0.0 (mod x 1)))))
    gen/string
    gen/keyword
    gen/keyword-ns
    gen/symbol
    gen/symbol-ns]))

(def serializable-gen
  "Generator for nested data structures that can be serialized."
  (gen/recursive-gen
   (fn [inner-gen]
     (gen/one-of
      [(gen/list inner-gen)
       (gen/vector inner-gen)
       (gen/set inner-gen)
       ;; Use strings as map keys to avoid complex key ordering issues
       (gen/map gen/string inner-gen)]))
   serializable-scalar-gen))

;; Generator for map keys that work well with canonical comparison
(def canonical-key-gen
  "Generator for keys that compare canonically."
  (gen/one-of
   [gen/keyword
    gen/keyword-ns
    gen/string]))

(def canonical-map-gen
  "Generator for maps with canonical-friendly keys."
  (gen/recursive-gen
   (fn [inner-gen]
     (gen/one-of
      [(gen/vector inner-gen)
       (gen/map canonical-key-gen inner-gen)]))
   serializable-scalar-gen))

(deftest ^:generative roundtrip-property
  (checking "Serialization roundtrip preserves equality" 10000
    [x serializable-gen]
    (let [result (-> x canon/serialize canon/deserialize)]
      (is (= x result)
          (str "Roundtrip failed for: " (pr-str x))))))

(deftest ^:generative determinism-property
  (checking "Same value always serializes to same bytes" 10000
    [x serializable-gen]
    (let [bytes1 (canon/serialize x)
          bytes2 (canon/serialize x)]
      (is (java.util.Arrays/equals bytes1 bytes2)
          (str "Non-deterministic serialization for: " (pr-str x))))))

(deftest ^:generative map-canonicalization-property
  (checking "Maps with same entries serialize identically" 10000
    [entries (gen/vector-distinct-by first
                                     (gen/tuple canonical-key-gen serializable-scalar-gen)
                                     {:min-elements 1 :max-elements 10})]
    (let [m1 (into {} entries)
          m2 (into {} (shuffle entries))
          m3 (reduce (fn [m [k v]] (assoc m k v)) {} (shuffle entries))]
      (is (java.util.Arrays/equals (canon/serialize m1) (canon/serialize m2))
          "Different insertion order should produce same bytes")
      (is (java.util.Arrays/equals (canon/serialize m2) (canon/serialize m3))
          "Different construction method should produce same bytes"))))

(deftest ^:generative map-types-property
  (checking "Different map implementations serialize identically" 10000
    ;; Use only keywords for sorted-map compatibility (must be homogeneous, comparable keys)
    [entries (gen/vector-distinct-by first
                                     (gen/tuple gen/keyword serializable-scalar-gen)
                                     {:min-elements 1 :max-elements 10})]
    (let [hash-m (into (hash-map) entries)
          array-m (into (array-map) entries)
          sorted-m (into (sorted-map) entries)]
      ;; All three map types are = equal
      (is (= hash-m array-m sorted-m) "Maps should be equal")
      ;; All three should serialize to identical bytes
      (is (java.util.Arrays/equals (canon/serialize hash-m) (canon/serialize array-m))
          "hash-map and array-map should serialize identically")
      (is (java.util.Arrays/equals (canon/serialize hash-m) (canon/serialize sorted-m))
          "hash-map and sorted-map should serialize identically"))))

(deftest ^:generative nested-map-canonicalization-property
  (checking "Nested maps are also canonical" 10000
    [m canonical-map-gen]
    (when (and (map? m) (seq m))  ; Skip empty maps (shuffle nil fails)
      (let [reconstructed (into {} (shuffle (seq m)))]
        (is (java.util.Arrays/equals (canon/serialize m)
                                     (canon/serialize reconstructed)))))))

(deftest ^:generative set-canonicalization-property
  (checking "Sets with same elements serialize identically" 10000
    [elements (gen/vector serializable-scalar-gen 1 10)]
    (let [s1 (set elements)
          s2 (set (shuffle elements))
          s3 (into #{} (shuffle elements))]
      (is (java.util.Arrays/equals (canon/serialize s1) (canon/serialize s2)))
      (is (java.util.Arrays/equals (canon/serialize s2) (canon/serialize s3))))))

(deftest ^:generative set-types-property
  (checking "Different set implementations serialize identically" 10000
    [elements (gen/vector-distinct gen/small-integer {:min-elements 1 :max-elements 10})]
    (let [hash-s (into (hash-set) elements)
          sorted-s (into (sorted-set) elements)]
      ;; Both set types are = equal
      (is (= hash-s sorted-s) "Sets should be equal")
      ;; Both should serialize to identical bytes
      (is (java.util.Arrays/equals (canon/serialize hash-s) (canon/serialize sorted-s))
          "hash-set and sorted-set should serialize identically"))))

(deftest ^:generative set-roundtrip-property
  (checking "Sets roundtrip correctly" 10000
    [elements (gen/vector-distinct serializable-scalar-gen {:min-elements 0 :max-elements 10})]
    (let [s (set elements)
          result (-> s canon/serialize canon/deserialize)]
      (is (set? result) "Sets deserialize as sets")
      (is (= s result) "Elements are preserved"))))

(deftest ^:generative numeric-types-property
  (checking "Equal numeric values serialize identically regardless of type" 10000
    [n gen/small-integer]
    (let [as-long (long n)
          as-int (int n)
          as-bigint (bigint n)
          as-biginteger (biginteger n)]
      ;; All are = equal
      (is (= as-long as-int as-bigint as-biginteger) "Numerics should be equal")
      ;; All should serialize to identical bytes
      (is (java.util.Arrays/equals (canon/serialize as-long) (canon/serialize as-int))
          "long and int should serialize identically")
      (is (java.util.Arrays/equals (canon/serialize as-long) (canon/serialize as-bigint))
          "long and bigint should serialize identically")
      (is (java.util.Arrays/equals (canon/serialize as-long) (canon/serialize as-biginteger))
          "long and BigInteger should serialize identically"))))

(deftest ^:generative metadata-ignored-property
  (checking "Metadata is ignored in serialization" 10000
    [v (gen/vector serializable-scalar-gen)
     meta1 (gen/map gen/keyword gen/string)
     meta2 (gen/map gen/keyword gen/string)]
    (let [v1 (with-meta v meta1)
          v2 (with-meta v meta2)]
      (is (java.util.Arrays/equals (canon/serialize v1) (canon/serialize v2))
          "Different metadata should produce same bytes"))))

(deftest ^:generative compression-determinism-property
  (checking "Compression is deterministic" 10000
    [x serializable-gen]
    (let [results (repeatedly 5 #(vec (canon/serialize x)))]
      (is (apply = results)
          (str "Non-deterministic compression for: " (pr-str x))))))

(deftest ^:generative uncompressed-determinism-property
  (checking "Uncompressed serialization is also deterministic" 10000
    [x serializable-gen]
    (let [results (repeatedly 5 #(vec (canon/serialize x {:compress? false})))]
      (is (apply = results)
          (str "Non-deterministic uncompressed for: " (pr-str x))))))

;; Test that documents limitations
(deftest known-limitations
  (t/testing "Document known type changes after roundtrip"
    (t/testing "Plain integers become BigInt"
      (let [original 42
            result (-> original canon/serialize canon/deserialize)]
        (is (= original result) "Numeric equality preserved")
        (is (instance? clojure.lang.BigInt result) "Type changed to BigInt")))

    (t/testing "Sets roundtrip correctly"
      (let [original #{1 2 3}
            result (-> original canon/serialize canon/deserialize)]
        (is (set? result) "Sets remain sets")
        (is (= original result) "Set equality preserved")))

    (t/testing "Metadata is stripped"
      (let [original (with-meta {:a 1} {:source "test"})
            result (-> original canon/serialize canon/deserialize)]
        (is (= original result) "Value preserved")
        (is (nil? (meta result)) "Metadata stripped")))

    (t/testing "Lists and vectors are NOT equal after serialization"
      ;; This is a known limitation: (= [1 2 3] '(1 2 3)) is true in Clojure,
      ;; but Transit encodes them differently (vector vs ~#list), so they
      ;; serialize to different bytes. This is intentional - we preserve
      ;; Transit's type distinctions.
      (let [v [1 2 3]
            l '(1 2 3)]
        (is (= v l) "Vector and list are = equal in Clojure")
        (is (not (java.util.Arrays/equals (canon/serialize v) (canon/serialize l)))
            "But they serialize differently (different Transit types)")))))
