(ns profile
  "Profiling transit-canon with Tufte."
  (:require
   [cognitect.transit :as transit]
   [com.latacora.transit-canon.core :as canon]
   [taoensso.tufte :as tufte :refer [defnp p profiled profile]])
  (:import
   [java.io ByteArrayOutputStream]
   [org.erdtman.jcs JsonCanonicalizer]))

;; Enable profiling
(tufte/add-basic-println-handler! {})

(def test-data
  {:small-map {:a 1 :b 2 :c 3}
   :medium-map (zipmap (map #(keyword (str "k" %)) (range 100)) (range 100))
   :large-map (zipmap (map #(keyword (str "k" %)) (range 1000)) (range 1000))
   :small-set #{1 2 3}
   :medium-set (set (range 100))
   :large-set (set (range 1000))
   :nested {:users (mapv #(hash-map :id % :name (str "user" %)) (range 50))}
   :vector (vec (range 100))})

(defn profile-serialize
  "Profile the serialize function."
  [data iterations]
  (profile
   {}
   (dotimes [_ iterations]
     (p :total
        (canon/serialize data {:compress? false})))))

(defn profile-plain-transit
  "Profile plain Transit for comparison."
  [data iterations]
  (profile
   {}
   (dotimes [_ iterations]
     (p :total
        (let [out (ByteArrayOutputStream.)]
          (transit/write (transit/writer out :json) data))))))

(defn run-profiles
  "Run all profiles."
  []
  (println "\n=== Profiling transit-canon ===\n")

  (doseq [[label data] test-data]
    (println (str "\n--- " (name label) " ---"))
    (println "\nCanonical:")
    (profile-serialize data 1000)
    (println "\nPlain Transit:")
    (profile-plain-transit data 1000)))

(comment
  ;; Run in REPL:
  (run-profiles)

  ;; Profile single case:
  (profile-serialize (:medium-map test-data) 1000)

  ;; More detailed profiling of internals:
  (profile
   {}
   (let [data (:medium-map test-data)]
     (dotimes [_ 1000]
       (p :sort (doall (sort-by key data)))
       (p :transit
          (let [out (ByteArrayOutputStream.)]
            (transit/write (transit/writer out :json) data)))))))
