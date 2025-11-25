(ns com.latacora.transit-canon.core-test
  (:require
   [clojure.test :as t :refer [deftest is testing]]
   [com.latacora.transit-canon.core :as canon]))

(deftest basic-serialization-roundtrip
  (testing "Basic types roundtrip correctly"
    (doseq [value [nil
                   true
                   false
                   "hello"
                   :keyword
                   :namespaced/keyword
                   'symbol
                   'namespaced/symbol
                   []
                   [1 2 3]
                   {}
                   {:nested {:deeply {:nested 42}}}]]
      (is (= value (-> value canon/serialize canon/deserialize))
          (str "Value should roundtrip: " (pr-str value))))))

(deftest numeric-roundtrip
  (testing "Numeric types preserve equality"
    ;; Note: plain integers become BigInt after roundtrip
    (doseq [value [0 1 -1 100 -100
                   0.5 1.5 -1.5
                   (bigint 42)
                   (bigdec "3.14159")
                   22/7]]
      (let [result (-> value canon/serialize canon/deserialize)]
        (is (= value result)
            (str "Numeric value should be equal: " (pr-str value)))))))

(deftest maps-are-canonical
  (testing "Maps with same data serialize identically regardless of construction order"
    (let [m1 (zipmap [:a :b :c] [1 2 3])
          m2 (zipmap [:c :b :a] [3 2 1])
          m3 {:a 1 :b 2 :c 3}
          m4 (into {} (shuffle [[:a 1] [:b 2] [:c 3]]))]
      (is (java.util.Arrays/equals (canon/serialize m1)
                                   (canon/serialize m2))
          "Maps built in different orders should serialize identically")
      (is (java.util.Arrays/equals (canon/serialize m1)
                                   (canon/serialize m3))
          "Literal maps should serialize same as constructed")
      (is (java.util.Arrays/equals (canon/serialize m1)
                                   (canon/serialize m4))
          "Shuffled map entries should serialize same"))))

(deftest nested-maps-are-canonical
  (testing "Nested maps are also canonical"
    (let [m1 {:outer {:a 1 :b 2} :data [1 2 3]}
          m2 (assoc {} :data [1 2 3] :outer (zipmap [:b :a] [2 1]))]
      (is (java.util.Arrays/equals (canon/serialize m1)
                                   (canon/serialize m2))))))

(deftest sets-serialization
  (testing "Sets with same elements serialize identically"
    (let [s1 #{:a :b :c}
          s2 (into #{} [:c :b :a])
          s3 (set [:a :b :c])]
      (is (java.util.Arrays/equals (canon/serialize s1)
                                   (canon/serialize s2)))
      (is (java.util.Arrays/equals (canon/serialize s1)
                                   (canon/serialize s3)))))

  (testing "Sets deserialize as vectors but with same elements"
    (let [s #{:a :b :c}
          result (-> s canon/serialize canon/deserialize)]
      (is (vector? result) "Sets deserialize as vectors")
      (is (= (set s) (set result)) "Elements are preserved"))))

(deftest metadata-is-ignored
  (testing "Values with different metadata serialize identically"
    (let [v1 [1 2 3]
          v2 (with-meta [1 2 3] {:source "test"})
          v3 (with-meta [1 2 3] {:different "metadata"})]
      (is (java.util.Arrays/equals (canon/serialize v1)
                                   (canon/serialize v2)))
      (is (java.util.Arrays/equals (canon/serialize v2)
                                   (canon/serialize v3))))))

(deftest complex-keys
  (testing "Maps with complex keys are canonical"
    (let [m1 {[1 2] "vec-key"
              {:a 1} "map-key"
              :simple "simple-key"}
          m2 (into {} (shuffle (seq m1)))]
      (is (java.util.Arrays/equals (canon/serialize m1)
                                   (canon/serialize m2))))))

(deftest edge-cases
  (testing "Edge cases are handled"
    (doseq [value [nil
                   []
                   {}
                   ""
                   (vec (range 1000))
                   {:deeply {:nested {:value 42}}}]]
      (is (= value (-> value canon/serialize canon/deserialize))
          (str "Edge case should roundtrip: " (pr-str value))))))

(deftest compression-options
  (testing "Compression can be disabled"
    (let [data {:some "data"}
          compressed (canon/serialize data)
          uncompressed (canon/serialize data {:compress? false})]
      ;; Uncompressed should be valid JSON (starts with [ for Transit)
      (is (= (aget uncompressed 0) (byte \[)))
      ;; Compressed should start with zstd magic number
      (is (= (aget compressed 0) (unchecked-byte 0x28)))
      ;; Both should deserialize to same value
      (is (= (canon/deserialize compressed)
             (canon/deserialize uncompressed))))))

(deftest serialize-uncompressed-helper
  (testing "serialize-uncompressed convenience function"
    (let [data {:test "value"}
          result (canon/serialize-uncompressed data)]
      ;; Should be valid JSON (Transit array format)
      (is (= (aget result 0) (byte \[)))
      (is (= data (canon/deserialize result))))))

(deftest canonical-bytes-equality
  (testing "canonical-bytes= works correctly"
    (let [m1 {:a 1 :b 2}
          m2 (zipmap [:b :a] [2 1])]
      (is (canon/canonical-bytes= m1 m2))
      (is (not (canon/canonical-bytes= m1 {:a 1 :b 3}))))))

(deftest canonical?-check
  (testing "canonical? returns true for normal values"
    (is (canon/canonical? {:a 1}))
    (is (canon/canonical? [1 2 3]))
    (is (canon/canonical? "string"))))
