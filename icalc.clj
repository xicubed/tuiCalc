#!/usr/bin/env bb

(ns calc)

(require '[clojure.string :as str])

;; Variable to hold the height of the stack
(def stack-height 8)

;; Variable to store the current stack
(def stack [])

;; Function to parse a string into a number or constant
(defn parse-number [s]
  (case (str/lower-case s)
    "pi" Math/PI
    "π"       Math/PI
    "math/pi" Math/PI
    (try
      (parse-double s)
      (catch Exception _
        nil))))

;; Custom division function to handle division by zero
(defn ieee-div [a b]
  (cond
    (and (zero? a) (zero? b)) Double/NaN
    (zero? b)                 (if (neg? a)
                                Double/NEGATIVE_INFINITY
                                Double/POSITIVE_INFINITY)
    :else                     (/ a b)))

;; Map of operations to their corresponding functions
(def ops
  {"+" +
   "-" -
   "x" *
   "*" *
   "/" ieee-div})

;; Function to clear the terminal and move cursor to top-left
(defn clear-screen []
  ;; ANSI: clear terminal and move cursor to top-left
  (print "\033[2J\033[H")
  (flush))

;; Function to apply an operator to the top two values of the stack
(defn apply-op [stack op]
  (if (< (count stack) 2)
    stack
    (let [b     (peek stack)
          stack (pop stack)
          a     (peek stack)
          stack (pop stack)]
      (conj stack (op a b)))))

;; Function to show the stack in a user-friendly format
(defn show-stack [stack]
  (clear-screen)

  (println "┌──────────── icalc ───────────┐")

  (let [visible (vec (take-last stack-height stack))
        blanks  (- stack-height (count visible))]

    ;; Blank rows go above the values,
    ;; so the stack grows upward.
    (dotimes [_ blanks]
      (println "│                              │"))

    ;; Oldest visible value at top,
    ;; newest / X register at bottom.
    (doseq [n visible]
      (printf "│ %28s │\n" (str n))))

  (println "├──────────────────────────────┤")
  (println "│ +   -   x   /    pi          │")
  (println "│ clear            q           │")
  (println "└──────────────────────────────┘")
  (println))

;; Main loop
(loop [stack []
       error nil]

  (show-stack stack)

  (print "> ")
  (flush)

  (when-let [input (read-line)]

    (let [input (str/trim input)]

      (cond

        ;; Quit
        (#{"q" "quit" "exit"} input)
        nil

        ;; Clear stack
        (= input "clear")
        (recur [] nil)

        ;; Operator
        (contains? ops input)
        (recur (apply-op stack (ops input)) nil)

        ;; Number / constant
        :else
        (if-let [n (parse-number input)]
          (recur (conj stack n) nil)
          (recur stack "INVALID INPUT"))))))