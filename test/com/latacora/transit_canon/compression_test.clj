(ns com.latacora.transit-canon.compression-test
  "Tests for compression determinism.

  These tests investigate whether zstd compression produces
  deterministic output, which is critical for canonical serialization."
  (:require
   [clojure.test :as t :refer [deftest is testing]]
   [com.latacora.transit-canon.core :as canon]
   [com.latacora.transit-canon.impl.compress :as compress]))

(deftest compression-basic-determinism
  (testing "Same input produces same compressed output"
    (let [data {:some "data" :with [:nested "structures"]}
          bytes1 (canon/serialize data)
          bytes2 (canon/serialize data)
          bytes3 (canon/serialize data)]
      (is (java.util.Arrays/equals bytes1 bytes2)
          "First and second serialization should be identical")
      (is (java.util.Arrays/equals bytes2 bytes3)
          "Second and third serialization should be identical"))))

(deftest compression-repeated-determinism
  (testing "Compression is deterministic over many iterations"
    (let [data {:large (vec (range 100))
                :nested {:a 1 :b 2 :c {:d 3}}}
          results (repeatedly 100 #(canon/serialize data))]
      (is (apply = (map vec results))
          "All 100 serializations should produce identical bytes"))))

(deftest compression-decompression-roundtrip
  (testing "Compress/decompress roundtrip preserves data"
    ;; Note: Sets become vectors after roundtrip (documented limitation)
    (let [test-data [{:a 1 :b "hello"}
                     [1 2 3 4 5]
                     {:large-string (apply str (repeat 1000 "abcdef"))}
                     42
                     "simple string"]]
      (doseq [data test-data]
        (let [serialized (canon/serialize data)
              deserialized (canon/deserialize serialized)]
          (is (= data deserialized)
              (str "Roundtrip should preserve: " (pr-str data)))))))

  (testing "Sets roundtrip correctly"
    (let [s #{"keyword" :key 'symbol}
          serialized (canon/serialize s)
          deserialized (canon/deserialize serialized)]
      (is (set? deserialized) "Sets deserialize as sets")
      (is (= s deserialized) "Set elements are preserved"))))

(deftest raw-compression-determinism
  (testing "Raw compress function is deterministic"
    (let [data (.getBytes "test data for compression" "UTF-8")
          compressed1 (compress/compress data)
          compressed2 (compress/compress data)
          compressed3 (compress/compress data)]
      (is (java.util.Arrays/equals compressed1 compressed2))
      (is (java.util.Arrays/equals compressed2 compressed3)))))

(deftest compression-level-determinism
  (testing "Same compression level produces same output"
    (let [data (.getBytes (apply str (repeat 1000 "test")) "UTF-8")]
      (doseq [level [1 3 9 15]]
        (let [results (repeatedly 10 #(compress/compress data level))]
          (is (apply = (map vec results))
              (str "Level " level " should be deterministic")))))))

(deftest different-levels-different-output
  (testing "Different compression levels produce different sized output"
    ;; Use larger, more varied data to see compression level differences
    (let [data (.getBytes (apply str (for [i (range 10000)]
                                       (str "test data block " i " with some variation ")))
                          "UTF-8")
          level1 (compress/compress data 1)
          level19 (compress/compress data 19)]
      ;; Higher level should produce smaller output for sufficiently large data
      (is (< (alength level19) (alength level1))
          "Higher compression level should produce smaller output"))))

(deftest compression-provides-savings
  (testing "Compression provides space savings for repetitive data"
    (let [large-data {:entries (vec (repeat 100 {:same "data"
                                                  :repeated "structure"
                                                  :numbers (vec (range 50))}))}
          compressed (canon/serialize large-data)
          uncompressed (canon/serialize large-data {:compress? false})]
      (is (< (alength compressed) (alength uncompressed))
          "Compressed should be smaller than uncompressed")
      ;; Verify both deserialize correctly
      (is (= large-data (canon/deserialize compressed)))
      (is (= large-data (canon/deserialize uncompressed))))))

(deftest small-data-compression
  (testing "Small data still compresses correctly"
    (let [small-data {:a 1}
          compressed (canon/serialize small-data)]
      (is (= small-data (canon/deserialize compressed))))))

(deftest empty-data-compression
  (testing "Empty structures compress correctly"
    (doseq [data [nil {} [] ""]]
      (let [compressed (canon/serialize data)]
        (is (= data (canon/deserialize compressed))
            (str "Empty structure should roundtrip: " (pr-str data)))))))

;; This test documents our findings about compression determinism
(deftest ^:determinism-investigation compression-determinism-findings
  (testing "Document compression determinism status"
    ;; This test runs multiple serializations and compares them.
    ;; If this test passes, we have evidence that compression is
    ;; deterministic within a single JVM session.
    (let [test-cases [{:description "Simple map"
                       :data {:a 1 :b 2}}
                      {:description "Large vector"
                       :data (vec (range 1000))}
                      {:description "Nested structure"
                       :data {:level1 {:level2 {:level3 {:value 42}}}}}
                      {:description "Mixed types"
                       :data {:int 1 :string "hello" :keyword :test
                              :vector [1 2 3] :nested {:a 1}}}]]
      (doseq [{:keys [description data]} test-cases]
        (let [serializations (repeatedly 50 #(vec (canon/serialize data)))]
          (is (apply = serializations)
              (str description " should serialize deterministically")))))))

;; TODO: Cross-process determinism test
;; This would require writing serialized bytes to a file,
;; then reading and comparing in a separate JVM process.
;; Add as a manual test or CI integration test.
(comment
  ;; Example approach for cross-process test:
  ;; 1. Serialize data and write to file
  ;; 2. Start new JVM process
  ;; 3. Serialize same data
  ;; 4. Compare bytes
  ;;
  ;; This is important because compression libraries might
  ;; use thread-local state or JIT-compiled code paths
  ;; that could differ between processes.
  )
