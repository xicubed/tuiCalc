#!/usr/bin/env bb

(ns tuiCalc-test
  "Tests for the tuiCalc calculator engine.

  Run with `bb test` (see bb.edn) or directly:
      bb test/tuiCalc_test.clj

  ----------------------------------------------------------------------
  GOTCHAS — read before adding a test (these cost hours to discover).
  ----------------------------------------------------------------------
  This babashka build evaluates every source file through sci's
  \"analysis\" pass, which changes how references resolve:

  * Use QUALIFIED symbols for anything from a required namespace.
    `(deftest ...)` / `(is ...)` (unqualified, via :refer) do NOT
    resolve. `(t/deftest ...)` / `(t/is ...)` DO.
  * NEVER reference a `charm.*` function in this file. It trips the
    same sci wall (\"Unable to resolve symbol: text-input/new\").
    Instead build a full state with `(first (tc/init))` — tuiCalc
    constructs its own text-input component internally, so we never
    touch charm here.
  * To simulate typed input, set the input component's `:value` (a
    vector of chars): `(update state :input assoc :value (vec \"42\"))`.
  * Requiring tuiCalc must NOT start the TUI. That is handled by the
    entry-guard at the bottom of src/tuiCalc.clj — don't remove it.
    (Without it, loading this file launches the TUI and hangs the run.)

  The functions under test are pure over the `state` map
  `{:stack [...] :input <component> :status ... :info ... :pending ...}`.
  "
  (:require [clojure.test :as t]
            [tuiCalc :as tc]))

;; A fresh state with a real (but empty) input component, built by
;; tuiCalc itself so this file never touches charm directly.
(def fresh (first (tc/init)))

;; Simulate the user having typed `txt` into the input box.
(defn typed [state txt]
  (update state :input assoc :value (vec txt)))

;; The stack after submitting whatever `txt` says (on `initial` stack).
(defn submit->stack [txt & [initial]]
  (-> (typed (assoc fresh :stack (or initial [])) txt)
      tc/submit first :stack))

;; The full new state after a submit (for asserting :status / :info /
;; :pending).
(defn submit->state [txt & [initial]]
  (-> (typed (assoc fresh :stack (or initial [])) txt)
      tc/submit first))

;; ---------------------------------------------------------------
;; level->index — HP-15c levels -> vector index
;; ---------------------------------------------------------------
(t/deftest level->index
  (t/is (= 2 (tc/level->index [1 2 3] 0)))    ; 0 = top
  (t/is (= 1 (tc/level->index [1 2 3] 1)))    ; 1 = below top
  (t/is (= 0 (tc/level->index [1 2 3] 2)))    ; 2 = bottom
  (t/is (= 0 (tc/level->index [1 2 3] -1)))   ; -1 = bottom
  (t/is (= 1 (tc/level->index [1 2 3] -2)))   ; -2 = second from bottom
  (t/is (nil? (tc/level->index [1 2 3] 3)))   ; out of range
  (t/is (nil? (tc/level->index [] 0)))        ; empty stack
  (t/is (nil? (tc/level->index [1 2 3] "0"))))  ; not an integer

;; ---------------------------------------------------------------
;; Bare ENTER — dups the top value (HP ENTER key); no-op when empty
;; ---------------------------------------------------------------
(t/deftest bare-enter-dups
  (t/is (= [3 7 7] (submit->stack "" [3 7])))
  (t/is (= [42 42] (submit->stack "" [42])))
  (t/is (= [] (submit->stack "" []))))

;; ---------------------------------------------------------------
;; submit: numbers, constants, unknown
;; ---------------------------------------------------------------
(t/deftest submit-pushes-numbers
  ;; typed numbers parse to doubles (parse-double)
  (t/is (= [3 42.0] (submit->stack "42" [3])))
  (t/is (= [1.5] (submit->stack "1.5")))
  (t/is (= [-4.0] (submit->stack "-4"))))

(t/deftest submit-constants
  (t/is (= [Math/PI] (submit->stack "pi")))
  (t/is (= [Math/PI] (submit->stack "Pi"))))    ; case-insensitive

(t/deftest submit-unknown-token
  (t/is (= "Unknown: foo" (:status (submit->state "foo"))))
  (t/is (= [] (:stack (submit->state "foo")))))

;; ---------------------------------------------------------------
;; submit: arithmetic operators
;; ---------------------------------------------------------------
(t/deftest submit-operators
  ;; seeded with doubles, as the app feeds the stack from typed input
  (t/is (= [9.0] (submit->stack "+" [3.0 6.0])))
  (t/is (= [3.0] (submit->stack "-" [9.0 6.0])))
  (t/is (= [18.0] (submit->stack "*" [3.0 6.0])))
  (t/is (= [18.0] (submit->stack "x" [3.0 6.0])))   ; 'x' is an alias for *
  (t/is (= [0.5] (submit->stack "/" [1.0 2.0])))
  ;; underflow: an operator needs two values, stack is left intact
  (t/is (= [3.0] (:stack (submit->state "+" [3.0]))))
  (t/is (= "Need two values" (:status (submit->state "+" [3.0])))))

;; ---------------------------------------------------------------
;; submit: stack commands
;; ---------------------------------------------------------------
(t/deftest submit-stack-commands
  (t/is (= [3] (submit->stack "drop" [3 7])))
  (t/is (= [3] (submit->stack "pop" [3 7])))         ; drop alias
  (t/is (= [3 7] (submit->stack "peek" [3 7])))      ; peek: no change
  (t/is (= "peek = 7" (:info (submit->state "peek" [3 7]))))
  (t/is (= "Stack empty" (:status (submit->state "peek"))))
  (t/is (= [3 7] (submit->stack "count" [3 7])))     ; count: no change
  (t/is (= "count = 2" (:info (submit->state "count" [3 7]))))
  (t/is (= [7 3] (submit->stack "reverse" [3 7])))
  (t/is (= [] (submit->stack "clear" [1 2 3]))))

(t/deftest submit-chs
  (t/is (= [-5] (submit->stack "chs" [5])))
  (t/is (= "Stack empty" (:status (submit->state "chs")))))

;; ---------------------------------------------------------------
;; submit: get / nth / remove (two-step level prompt)
;; ---------------------------------------------------------------
(t/deftest submit-get
  (t/is (= [5 9 9] (submit->stack "get 0" [5 9])))   ; level 0 = top
  (t/is (= [5 9 5] (submit->stack "get 1" [5 9])))   ; level 1 = below
  (t/is (= [5 9 5] (submit->stack "get -1" [5 9])))  ; level -1 = bottom
  (t/is (= [5 9 9] (submit->stack "nth 0" [5 9])))   ; nth is an alias
  ;; bare `get` (no level) starts the prompt, does not push
  (t/is (= [5 9] (:stack (submit->state "get" [5 9]))))
  (t/is (= "get" (:pending (submit->state "get" [5 9])))))

(t/deftest submit-remove
  (t/is (= [5] (submit->stack "remove 0" [5 9])))    ; remove top (9)
  (t/is (= [9] (submit->stack "remove 1" [5 9])))    ; remove below (5)
  ;; bare `remove` (no level) starts the prompt, does not remove
  (t/is (= [5 9] (:stack (submit->state "remove" [5 9]))))
  (t/is (= "remove" (:pending (submit->state "remove" [5 9])))))

;; ---------------------------------------------------------------
;; ieee-div edge cases
;; ---------------------------------------------------------------
(t/deftest ieee-div
  (t/is (Double/isNaN (tc/ieee-div 0.0 0.0)))
  (t/is (= Double/POSITIVE_INFINITY (tc/ieee-div 1.0 0.0)))
  (t/is (= Double/NEGATIVE_INFINITY (tc/ieee-div -1.0 0.0)))
  (t/is (= 0.5 (tc/ieee-div 1.0 2.0))))

;; ---------------------------------------------------------------
;; parsers
;; ---------------------------------------------------------------
(t/deftest parsers
  (t/is (= Math/PI (tc/parse-number "pi")))
  (t/is (= 42.0 (tc/parse-number "42")))
  (t/is (nil? (tc/parse-number "abc")))
  (t/is (= 0 (tc/parse-integer "0")))
  (t/is (= -1 (tc/parse-integer "-1")))
  (t/is (nil? (tc/parse-integer "1.5")))
  (t/is (nil? (tc/parse-integer "abc"))))

;; ---------------------------------------------------------------
;; flash-for-action — which button lights up for a given activation
;; ---------------------------------------------------------------
(t/deftest flash-for-action
  ;; a bare enter flashes [ENTER]
  (t/is (= "enter" (tc/flash-for-action (typed fresh "") "enter")))
  ;; typing "chs" then Enter flashes [CHS]
  (t/is (= "CHS" (tc/flash-for-action (typed fresh "chs") "enter")))
  ;; a bare number flashes [ENTER]
  (t/is (= "enter" (tc/flash-for-action (typed fresh "7") "enter")))
  ;; a level typed into a get? prompt flashes [GET]
  (let [prompt (tc/start-level-prompt (assoc fresh :stack [5]) "get" "get? ")]
    (t/is (= "get" (tc/flash-for-action (typed prompt "0") "enter")))))

;; ---------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------
;; bb's stripped clojure.test has no add-report/all-tests, and its
;; default reporter prints only the final summary + failures. Run each
;; test var explicitly so every test shows as it runs:
;;
;;   level->index ... ok
;;   bare-enter-dups ... FAIL
;; (plus the full expected/actual detail for any FAIL)
(defn var-name [v]
  (get (meta v) :name))

(defn test-vars []
  (->> (ns-publics 'tuiCalc-test)
       (vals)
       (filter (comp :test meta))
       (sort-by var-name)))

(defn run-one [var]
  ;; Capture the test's own output (bb's clojure.test writes to
  ;; *test-out*, not *out*) to silence the per-test "Ran 1 tests"
  ;; noise; the return map carries the failure counts.
  (let [w (java.io.StringWriter.)]
    (binding [t/*test-out* w]
      (let [r (t/run-test-var var)]
        {:out    (str w)
         :failed (pos? (+ (get r :fail 0) (get r :error 0)))}))))

(let [results (map (fn [v]
                     (let [{:keys [out failed]} (run-one v)]
                       (println (str "  " (var-name v) " ... "
                                     (if failed "FAIL" "ok")))
                       (when failed (println out))
                       (if failed 1 0)))
                   (test-vars))
      failed (apply + results)]
  (println (str "\n" (count results) " tests, " failed
                (if (= failed 1) " failure" " failures")))
  (System/exit (if (pos? failed) 1 0)))
