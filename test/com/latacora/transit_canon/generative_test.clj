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

(deftest roundtrip-property
  (checking "Serialization roundtrip preserves equality" 500
    [x serializable-gen]
    (let [result (-> x canon/serialize canon/deserialize)]
      (is (= x result)
          (str "Roundtrip failed for: " (pr-str x))))))

(deftest determinism-property
  (checking "Same value always serializes to same bytes" 500
    [x serializable-gen]
    (let [bytes1 (canon/serialize x)
          bytes2 (canon/serialize x)]
      (is (java.util.Arrays/equals bytes1 bytes2)
          (str "Non-deterministic serialization for: " (pr-str x))))))

(deftest map-canonicalization-property
  (checking "Maps with same entries serialize identically" 200
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

(deftest nested-map-canonicalization-property
  (checking "Nested maps are also canonical" 100
    [m canonical-map-gen]
    (when (and (map? m) (seq m))  ; Skip empty maps (shuffle nil fails)
      (let [reconstructed (into {} (shuffle (seq m)))]
        (is (java.util.Arrays/equals (canon/serialize m)
                                     (canon/serialize reconstructed)))))))

(deftest set-canonicalization-property
  (checking "Sets with same elements serialize identically" 200
    [elements (gen/vector serializable-scalar-gen 1 10)]
    (let [s1 (set elements)
          s2 (set (shuffle elements))
          s3 (into #{} (shuffle elements))]
      (is (java.util.Arrays/equals (canon/serialize s1) (canon/serialize s2)))
      (is (java.util.Arrays/equals (canon/serialize s2) (canon/serialize s3))))))

(deftest set-roundtrip-property
  (checking "Sets roundtrip correctly" 200
    [elements (gen/vector-distinct serializable-scalar-gen {:min-elements 0 :max-elements 10})]
    (let [s (set elements)
          result (-> s canon/serialize canon/deserialize)]
      (is (set? result) "Sets deserialize as sets")
      (is (= s result) "Elements are preserved"))))

(deftest metadata-ignored-property
  (checking "Metadata is ignored in serialization" 100
    [v (gen/vector serializable-scalar-gen)
     meta1 (gen/map gen/keyword gen/string)
     meta2 (gen/map gen/keyword gen/string)]
    (let [v1 (with-meta v meta1)
          v2 (with-meta v meta2)]
      (is (java.util.Arrays/equals (canon/serialize v1) (canon/serialize v2))
          "Different metadata should produce same bytes"))))

(deftest compression-determinism-property
  (checking "Compression is deterministic" 200
    [x serializable-gen]
    (let [results (repeatedly 5 #(vec (canon/serialize x)))]
      (is (apply = results)
          (str "Non-deterministic compression for: " (pr-str x))))))

(deftest uncompressed-determinism-property
  (checking "Uncompressed serialization is also deterministic" 200
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
        (is (nil? (meta result)) "Metadata stripped")))))
