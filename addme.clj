#!/usr/bin/env bb

(ns calc)

(defn parse-number [x]
  (case x
    "Math/PI" Math/PI
    "PI"      Math/PI
    "pi"      Math/PI
    "π"       Math/PI
    (parse-double x)))
#_(defn addme [x y]
  (+ x y))

#_(defn -main [& args]
(when (seq *command-line-args*)
  (println
    (apply + (map parse-number *command-line-args*)))))

#_(apply -main *command-line-args*)

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