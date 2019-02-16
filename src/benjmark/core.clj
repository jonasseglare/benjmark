(ns benjmark.core
  (:import [java.io File])
  (:require [clojure.spec.alpha :as spec]
            [clojure.java.io :as io]
            [cheshire.core :as cheshire]
            [clojure.pprint :as pp]
            [clojure.java.shell :as shell]))

(spec/def ::root string?)
(spec/def ::format #{:json})

(spec/def ::size number?)
(spec/def ::data any?)
(spec/def ::problem (spec/keys :req [::size
                                     ::data]))

(spec/def ::problem-sizes (spec/coll-of number?))

(spec/def ::name string?)

(spec/def ::candidate (spec/keys :req [::name ::fn]))

(spec/def ::combine-time fn?)

(spec/def ::candidates (spec/map-of ::name ::candidate))

(spec/def ::try-count number?)

(spec/def ::max-duration-seconds number?)

(spec/def ::index number?)

(spec/def ::problem-indices (spec/coll-of ::index))

(spec/def ::project (spec/keys :req [::root
                                     ::format
                                     ::combine-time
                                     ::candidates
                                     ::try-count
                                     ::max-duration-seconds
                                     ::trim-output]
                               :opt [::problem-indices]))

(spec/def ::time-seconds number?)
(spec/def ::output any?)
(spec/def ::candidate-results (spec/keys
                               :req-un
                               [::time-seconds
                                ::output]))




(defn project-file [filename project]
  (io/file (::root project) filename))

(defn generate-problem [generator size]
  {:data (generator size)
   :size size})

(defn problem-info-file [project]
  (project-file "probleminfo.json" project))

(defn problem-file [i project]
  (project-file (format "problem%04d.json" i) project))

(defn with-make-parents [f]
  (io/make-parents f)
  f)

(defn load-problem-count [project]
  (let [file (problem-info-file project)]
    (if (not (.exists file))
      (throw (ex-info "No problem info file"
                      {:file file})))

    (-> file
        slurp
        (cheshire/decode true)
        :count)))

(defn initialize-state [total-count]
  {:counter 0
   :total-count total-count
   :results {}
   :outputs {}
   :max-sizes {}})

(defn combine-output [a b]
  (if (= a b)
    a
    (throw (ex-info "Inconsistent output"
                    {:a a
                     :b b}))))

(defn combine-results [a b project]
  {:time-seconds ((::combine-time project)
                  (:time-seconds a)
                  (:time-seconds b))
   :output (combine-output (:output a)
                           (:output b))})

(defn load-problem [index project]
  (-> (problem-file index project)
      slurp
      (cheshire/decode true)))

(defn run-experiment? [state cand-key problem-size]
  (if-let [max-size (-> state :max-sizes (get cand-key))]
    (<= problem-size max-size)
    true))

(defn update-max-size [state cand-key problem-size time-seconds max-dur]
  (if (< max-dur time-seconds)
    (do (println (format "Max duration exceeded by '%s' at problem size %d (%.3g seconds)"
                         cand-key
                         problem-size
                         (double max-dur)))
        (assoc-in state [:max-sizes cand-key] problem-size))
    state))

(defn step-counter [state]
  (update state :counter inc))

(defn run-experiment [project state setup]
  (let [problem-index (:problem-index setup)
        problem (load-problem problem-index project)
        problem-size (:size problem)
        cand-key (:cand-key setup)
        trimmer (::trim-output project)]
    (println (format  "Running experiment %d/%d" (inc (:counter state)) (:total-count state)))
    (step-counter
     (if (run-experiment? state cand-key problem-size)
       (let [f (-> setup :cand ::fn)
             experiment-results (merge (select-keys setup [:cand-key :problem-index])
                                       (f (.getAbsolutePath (problem-file problem-index project))))]
         (when (not (spec/valid? ::candidate-results experiment-results))
           (throw (ex-info (str "Invalid experiment results: "
                                (spec/explain-str
                                 ::candidate-results
                                 experiment-results))
                           {:results experiment-results})))
         (-> state
             (update-max-size cand-key problem-size
                              (:time-seconds experiment-results)
                              (::max-duration-seconds project))
             (update-in [:results {:problem-index problem-index
                                   :cand-key cand-key}]
                        (fn [results]
                          (if results
                            (combine-results results experiment-results project)
                            experiment-results)))
             (update-in [:outputs problem-index
                         (trimmer (:output experiment-results))]
                        (fn [dst]
                          (conj (or dst #{}) cand-key)))))
       (do (println (format "Skipping '%s' at size %d" cand-key problem-size))
           state)))))

(defn results-file [project]
  (project-file "results.json" project))

(defn result-order [x]
  (mapv (fn [k] (get x k)) [:cand-key :problem-index]))

(defn flatten-results [results]
  (mapv
   (fn [[k v]]
     (merge k (select-keys v [:time-seconds])))
   results))



(defn unflatten-results [results]
  (into
   {}
   (map (fn [x]
          [(select-keys x [:problem-index :cand-key])
           (select-keys x [:time-seconds])])
        results)))

(defn update-results-file [results project]
  (let [file (results-file project)
        prev (if (.exists file)
               (-> file slurp (cheshire/decode true) unflatten-results)
               {})
        merged-results (sort-by result-order (flatten-results (merge prev results)))]
    (spit (with-make-parents file) (cheshire/encode merged-results))
    merged-results))

(defn cand-file [project]
  (project-file "candidates.json" project))

(defn update-candidates-info [project]
  (let [file (with-make-parents (cand-file project))
        prev (if (.exists file)
               (-> file slurp (cheshire/decode true))
               {})]
    (spit file
          (cheshire/encode (into
                            prev (map (fn [[k v]] [k {:name (::name v)}])
                                      (::candidates project)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  Interface
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def default-project {::root "benjmark/"
                      ::format :json
                      ::combine-time min
                      ::try-count 3
                      ::max-duration-seconds 4
                      ::trim-output identity})

(defn capture-results [f input]
  (let [start (System/nanoTime)
        output (f input)
        stop (System/nanoTime)]
    {:output output
     :time-seconds (* 1.0e-9 (- stop start))}))

(defn wrap-load [f]
  "Converts a function that accepts data as input to a function that loads the data from a file"
  (fn [input-filename]
    (f (-> input-filename
           slurp
           (cheshire/decode true)
           :data))))

(defn wrap-capture-results [f]
  "Creates a new function that exports time and output of f on the arguments"
  (fn [input]
    (capture-results f input)))

(defn wrap-dry-run [f]
  "Given a function that already produces a valid result map, run that function twice and take the results of the second run to be the results map."
  (fn [input]
    (let [dry-results (f input)]
      (merge (f input)
             {:dry-results dry-results}))))

(defn wrap-results-conversion
  ([f input-conversion output-conversion]
   (fn [input]
     (let [input (input-conversion input)
           results (capture-results f input)]
       (update results :output output-conversion))))
  ([f input-conversion]
   (wrap-results-conversion input-conversion identity)))

(defn wrap-fn
  
  "Wraps a clojure function that computes something on data to load the data, measure time and do a dry run. When measuring Clojure code, you probably want to wrap your function with this."
  ([f input-conv output-conv]
   (-> f
       (wrap-results-conversion input-conv output-conv)
       wrap-dry-run
       wrap-load))
  ([f input-conv]
   (wrap-fn f input-conv identity))
  ([f]
   (wrap-fn f identity identity)))

(defn wrap-executable [path]
  (fn [input-filename]
    (let [path (io/file path)
          input-file (io/file input-filename)
          out-file (File/createTempFile "benjmark" ".json")
          output (shell/sh (.getAbsolutePath path)
                           (.getAbsolutePath input-file)
                           (.getAbsolutePath out-file))]
      (-> out-file
          slurp
          (cheshire/decode true)))))

(defn generate-and-save-problems [project
                                  generator
                                  problem-sizes]
  {:pre [(spec/valid? ::project project)
         (fn? generator)
         (spec/valid? ::problem-sizes problem-sizes)]}
  (spit (with-make-parents (problem-info-file project))
        (cheshire/encode {:count (count problem-sizes)}))
  (doseq [[i problem-size] (map vector (range) problem-sizes)]
    (spit (problem-file i project)
          (cheshire/encode (generate-problem generator problem-size)))))

(defn list-experiments [project]
  (for [[cand-key cand] (::candidates project)
        
        problem-index
        (or (::problem-indices project)
            (range (load-problem-count project)))
        
        try-index (range (::try-count project))]
    {:problem-index problem-index
     :cand-key cand-key
     :cand cand
     :try-index try-index}))

(defn run-experiments [experiments project]
  (reduce
   (partial run-experiment project)
   (initialize-state (count experiments))
   experiments))

(defn clear-results [project]
  (-> project
      results-file
      io/delete-file))




(defn check-consistent-outputs [results]
  (doseq [[problem-index output-map] (:outputs results)]
    (if (not= 1 (count output-map))
      (throw (ex-info "Inconsistent outputs for problem"
                      {:problem-index problem-index
                       :outputs output-map}))))
  results)

(defn run-benchmark [project]
  (update-candidates-info project)
  (-> project
      list-experiments
      (run-experiments project)
      check-consistent-outputs
      :results
      (update-results-file project)
      ))


(defn max-time [results]
  (apply max (map :time-seconds (vals results))))

(defn exponential-sizes [n lower upper]
  (let [l (Math/log lower)
        u (Math/log upper)
        k (/ (- u l) (- n 1))
        m l]
    (mapv (fn [i] (int (Math/round (Math/exp (+ m (* k i)))))) (range n))))

;;;------- Experimenting -------


(comment
  
  (do

    (defn fib0 [n]
      (if (<= n 1)
        n
        (+ (fib0 (- n 1))
           (fib0 (- n 2)))))

    (defn fib1 [n]
      (if (<= n 1)
        n ;; 1
        (+ (fib0 (- n 1))
           (fib0 (- n 2)))))
    
    (def test-project (merge
                       default-project
                       {::root "/tmp/testproj"
                        ::max-duration-seconds 0.01
                        ::candidates 
                        {"fib0" {::fn (wrap-fn fib0) ::name "Basic fibonacci"}
                         "fib1" {::fn (wrap-fn fib1) ::name "Fib starting at 1"}
                         }}))

    (generate-and-save-problems test-project identity (range 30))

    (load-problems test-project)

    (def br (run-benchmark test-project))
    (pp/pprint br)
    
    )



  )


