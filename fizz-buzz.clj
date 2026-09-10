#!/usr/bin/env bb

(ns fizz-buzz)

(defn fizz-buzz []
  (doseq [i (range 1 101)]
    (cond
      (and (zero? (rem i 3)) (zero? (rem i 5))) (println "FizzBuzz")
      (zero? (rem i 3)) (println "Fizz")
      (zero? (rem i 5)) (println "Buzz")
      :else (println i))))

(fizz-buzz)