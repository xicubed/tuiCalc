#!/usr/bin/env bb

(ns calc)

(defn parse-number [x]
  (case x
    "Math/PI" Math/PI
    "PI"      Math/PI
    "pi"      Math/PI
    "π"       Math/PI
    (parse-double x)))

(def ops
  {"+" +
   "-" -
   "x" *
   "/" /})

(defn rpn [tokens]
  (reduce
    (fn [stack token]
      (if-let [op (ops token)]
        [(apply op stack)]
        (conj stack (parse-number token))))
    []
    tokens))

(println (peek (rpn *command-line-args*)))