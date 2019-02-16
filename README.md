# benjmark

A tool to perform benchmarks.

## Leiningen dependency

```
[benjmark "0.1.0"]
```

## Usage

The benchmark is defined using a map, e.g.
```clj
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
```
This map defines where the data should be stored, in the ```::bj/root``` key.

Then list the candidate implementations that you want to test in the ```::bj/candidates``` map.

Start by building the C++ code:
```
cd cpp
make fibonacci
```
Then load ```example.core``` in the REPL and evaluate
```clj
;; (generate-problem)
```
to generate a new problem.

To run the benchmarks, call
```
(bj/run-benchmark project)
```

There is also some Python3 code under ```python/``` for producing bar and line plots.

To produce plots for this example, do
```
cd python/
python3 fibonacci.py
```
which will result in a bunch of PDF files with prefix ```tmpfib```.

## Requirements
  * Leiningen
  * Python3
  * Matplotlib
  * Pyrsistent

## License

Copyright © 2019 Jonas Östlund

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
