(ns example.core
  (:require [benjmark.core :as bj]))

(defn fibonacci [n]
  (if (<= n 1)
    n
    (+ (fibonacci (- n 1))
       (fibonacci (- n 2)))))


(def project (merge
              bj/default-project
              {::bj/root "benchmarks/fibonacci"
               ::bj/max-duration-seconds 3.0

               ;;::bj/problem-indices [0]
               ;;::bj/try-count 1
               
               ::bj/candidates
               {"clojure" {::bj/name "Clojure"
                           ::bj/fn
                           (bj/wrap-fn fibonacci)}
                "cpp" {::bj/name "C++"
                       ::bj/fn
                       (bj/wrap-executable "cpp/fibonacci")}}}))

(defn generate-problem []
  (bj/generate-and-save-problems
   project
   identity
   (range 35)))

;; (bj/update-candidates-info project)

;; (generate-problem)
;; (bj/clear-results project)
;; (bj/run-benchmark project)
