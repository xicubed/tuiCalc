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

  ----------------------------------------------------------------------
  STACK ORDER vs VECTOR ORDER vs THE BOX — the thing that trips people up
  ----------------------------------------------------------------------
  There are THREE ways to think about the stack and they don't line up
  the same direction. 'stack-top' below always means the value you'd
  get by pressing ENTER (HP's X register, the newest push) — NOT the
  top of the drawn box.

  `:stack` is a Clojure vector in *chronological* order: oldest push
  first, newest (the stack-top / X) LAST. Push 5, then 9 -> [5 9]:

      vector :stack =  [5 9]
                          ^  ^
                          |  stack-top / X (newest)
                       oldest

  The TUI draws it so the newest (X) is on the BOTTOM row — like an HP
  15c, where X is the bottom register and Y sits above it:

      │              5.0 │   <- level 1 / Y  (older)
      │              9.0 │   <- level 0 / X  (stack-top, newest)

  So 5 is the OLDER value (the T register); the stack-top (9) is X. The
  stack is pinned to the bottom of the box and grows up, so for a 2-deep
  stack 5 sits just above X (the box's top rows are blank until it fills).
  Same vector, three lenses:
    - vector index 0 = leftmost  = oldest = T register
    - level 0        = rightmost = newest = X register
    - level -1       = oldest    = T register

  *levels* (get/nth/remove) are an OFFSET FROM X, not a vector index, and
  not a real HP-15c level (the machine has no numbered levels). They walk
  the vector: level 0 -> index (count-1-0), level 1 -> (count-2), ... and
  level -1 -> the oldest (T). `get 0` copies the stack-top (X). The exact
  mapping is in `level->index`.
  "
  (:require [clojure.test :as t]
            [tuiCalc :as tc]))

;; A fresh state with a real (but empty) input component, built by
;; tuiCalc itself so this file never touches charm directly.
(def fresh (first (tc/init)))

;; Simulate the user having typed `txt` into the input box.
(defn typed [state txt]
  (update state :input assoc :value (vec txt) :pos (count txt)))

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
;; level->index — offset-from-X level -> vector index
;;
;; On [1 2 3]: 3 is X (newest), 2 is Y, 1 is Z/T (oldest).
;;   level 0  -> X (3)  -> index 2      (newest)
;;   level 1  -> Y (2)  -> index 1
;;   level 2  -> Z (1)  -> index 0      (oldest of 3)
;;   level -1 -> T (1)  -> index 0      (first-in; == Z when 3 deep)
;;   level -2 -> Y (2)  -> index 1      (negatives count from T toward X)
;; ---------------------------------------------------------------
(t/deftest level->index
  (t/is (= 2 (tc/level->index [1 2 3] 0)))    ; 0 = X (newest)
  (t/is (= 1 (tc/level->index [1 2 3] 1)))    ; 1 = Y
  (t/is (= 0 (tc/level->index [1 2 3] 2)))    ; 2 = Z (oldest of 3)
  (t/is (= 0 (tc/level->index [1 2 3] -1)))   ; -1 = T (first-in)
  (t/is (= 1 (tc/level->index [1 2 3] -2)))   ; -2 = one older than T
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
  (t/is (= [1024.0] (submit->stack "^" [2.0 10.0])))  ; 2^10 = 1024
  (t/is (= [3.0] (submit->stack "pow" [9.0 0.5])))    ; 9^0.5 = 3
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

;; inv (1/x) replaces only X; Y and below are untouched.
(t/deftest submit-inv
  (t/is (= [0.2] (submit->stack "inv" [5])))
  (t/is (= [4 0.2] (submit->stack "inv" [4 5])))
  (t/is (= [-0.5] (submit->stack "inv" [-2])))
  (t/is (= [Double/POSITIVE_INFINITY] (submit->stack "inv" [0])))  ; 1/0 via ieee-div
  (t/is (= "Stack empty" (:status (submit->state "inv")))))

;; The keystroke path: type 5, press [ENTER], then press [INV] (button
;; or ^v) -> 0.2.
(t/deftest five-enter-inv
  (let [after-enter (first (tc/submit (typed fresh "5")))]
    (t/is (= [5.0] (:stack after-enter)))
    (t/is (= [0.2] (:stack (first (tc/run-action after-enter "INV")))))
    (t/is (= "INV" (get tc/shortcuts "v")))))

;; y^x: 2 [ENTER] 10 [y^x] -> 1024. Y is the base, X the exponent.
(t/deftest two-enter-ten-pow
  (let [after-enter (first (tc/submit (typed fresh "2")))
        after-10    (first (tc/submit (typed after-enter "10")))]
    (t/is (= [2.0 10.0] (:stack after-10)))
    (t/is (= [1024.0] (:stack (first (tc/run-action after-10 "^"))))))
  (t/is (= [0.125] (submit->stack "^" [2.0 -3.0])))     ; negative exponent
  (t/is (= [7 8.0] (submit->stack "pow" [7 2 3])))      ; only X and Y consumed
  (t/is (= [2.0] (:stack (submit->state "^" [2.0]))))   ; underflow leaves stack
  (t/is (= "Need two values" (:status (submit->state "pow" [2.0])))))

;; ---------------------------------------------------------------
;; submit: get / nth / remove (two-step level prompt)
;; ---------------------------------------------------------------
;; The seed [5 9] means "pushed 5, then 9", so 9 is X (newest) and 5 is
;; the one below it. With only two values, 5 is simultaneously Y (one
;; below X) and T (the first-in/oldest) — level 1 and level -1 both
;; point at it. (At 3+ deep they'd differ: Y is not yet T.)
;;
;;     level  ->  register  ->  value
;;     0       ->  X        ->  9   (newest, stack-top)
;;     1       ->  Y        ->  5
;;     -1      ->  T        ->  5   (oldest; == Y when only 2 deep)
;;
;; So `get 0` copies X (9); `get 1` and `get -1` both copy 5 here.
(t/deftest submit-get
  (t/is (= [5 9 9] (submit->stack "get 0" [5 9])))   ; get X (9)
  (t/is (= [5 9 5] (submit->stack "get 1" [5 9])))   ; get Y (5)
  (t/is (= [5 9 5] (submit->stack "get -1" [5 9])))  ; get T (5)
  (t/is (= [5 9 9] (submit->stack "nth 0" [5 9])))   ; nth is an alias
  ;; bare `get` (no level) starts the prompt, does not push
  (t/is (= [5 9] (:stack (submit->state "get" [5 9]))))
  (t/is (= "get" (:pending (submit->state "get" [5 9])))))

(t/deftest submit-remove
  (t/is (= [5] (submit->stack "remove 0" [5 9])))    ; remove X (9)
  (t/is (= [9] (submit->stack "remove 1" [5 9])))    ; remove Y (5)
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
  ;; typing "inv" then Enter flashes [INV]
  (t/is (= "INV" (tc/flash-for-action (typed fresh "inv") "enter")))
  ;; typing "pow" or "^" then Enter flashes [y^x] (action "^")
  (t/is (= "^" (tc/flash-for-action (typed fresh "pow") "enter")))
  (t/is (= "^" (tc/flash-for-action (typed fresh "^") "enter")))
  ;; a bare number flashes [ENTER]
  (t/is (= "enter" (tc/flash-for-action (typed fresh "7") "enter")))
  ;; a level typed into a get? prompt flashes [GET]
  (let [prompt (tc/start-level-prompt (assoc fresh :stack [5]) "get" "get? ")]
    (t/is (= "get" (tc/flash-for-action (typed prompt "0") "enter")))))

;; ---------------------------------------------------------------
;; find-button-at — clicking the rendered [INV] hits INV
;; ---------------------------------------------------------------
;; Mouse coords are 1-based; locate [INV] in the rendered first help
;; line rather than hard-coding its column.
(t/deftest click-inv-button
  (let [line  (first (tc/help-lines))
        start (.indexOf line "[INV]")
        y     (inc tc/help-row0)]
    (t/is (<= 0 start))
    (t/is (= "INV" (tc/find-button-at (inc start) y)))         ; the '['
    (t/is (= "INV" (tc/find-button-at (+ start 5) y)))         ; the ']'
    (t/is (not= "INV" (tc/find-button-at (+ start 6) y)))))    ; gap after

(t/deftest click-pow-button
  (let [line  (first (tc/help-lines))
        start (.indexOf line "[y^x]")
        y     (inc tc/help-row0)]
    (t/is (<= 0 start))
    (t/is (= "^" (tc/find-button-at (inc start) y)))           ; the '['
    (t/is (= "^" (tc/find-button-at (+ start 5) y)))           ; the ']'
    (t/is (not= "^" (tc/find-button-at (+ start 6) y)))))      ; gap after

;; ---------------------------------------------------------------
;; swap command (HP-15c x<>y)
;; ---------------------------------------------------------------
(t/deftest submit-swap
  ;; swap exchanges the top two values (X and Y)
  (t/is (= [1 3 2] (submit->stack "swap" [1 2 3])))
  (t/is (= [1 3 2] (submit->stack "x<>y" [1 2 3])))
  (t/is (= [7 3] (submit->stack "swap" [3 7])))
  (t/is (= [7 3] (submit->stack "x<>y" [3 7])))
  ;; underflow: fewer than two values leaves stack unchanged and reports need
  (t/is (= [42] (:stack (submit->state "swap" [42]))))
  (t/is (= "Need two values" (:status (submit->state "swap" [42]))))
  (t/is (= [] (:stack (submit->state "swap" []))))
  (t/is (= "Need two values" (:status (submit->state "swap" [])))))

(t/deftest button-for-swap
  (t/is (= "swap" (tc/button-for-token "swap")))
  (t/is (= "swap" (tc/button-for-token "x<>y"))))

(t/deftest flash-swap
  (t/is (= "swap" (tc/flash-for-action (typed fresh "swap") "enter")))
  (t/is (= "swap" (tc/flash-for-action (typed fresh "x<>y") "enter"))))

(t/deftest run-action-swap
  (let [[new-state _] (tc/run-action (assoc fresh :stack [1 2 3]) "swap")]
    (t/is (= [1 3 2] (:stack new-state)))))

;; ---------------------------------------------------------------
;; map — apply a unary/binary op to every element in the stack
;; ---------------------------------------------------------------
;; map + 4: [1 2 3] -> [5 6 7]
(t/deftest map-binary-with-arg
  (t/is (= [5.0 6.0 7.0] (submit->stack "map + 4" [1 2 3])))
  (t/is (= [0.0 1.0 2.0] (submit->stack "map - 1" [1 2 3])))
  (t/is (= [1.0 2.0] (submit->stack "map / 2" [2 4])))
  (t/is (= [4.0 9.0] (submit->stack "map ^ 2" [2 3])))
  ;; x displays as *
  (t/is (= [6.0 12.0] (submit->stack "map * 2" [3 6]))))

;; map chs, map inv — unary ops
(t/deftest map-unary
  (t/is (= [-1.0 2.0] (submit->stack "map chs" [1 -2])))
  (t/is (= [0.5 0.25] (submit->stack "map inv" [2 4]))))

;; map + with no arg: X is the operand (HP-style)
;; Bug 2 fix: explicit arg maps over WHOLE stack, so [1 2 3 10] + map + 10 = [11 12 13 20]
(t/deftest map-binary-with-x-as-operand
  (t/is (= [11.0 12.0 13.0 20.0] (submit->stack "map + 10" [1 2 3 10])))
  (t/is (= "map" (:pending (submit->state "map" [1 2 3 10])))))

;; map with pi constant
(t/deftest map-with-pi
  (t/is (= (+ 1 Math/PI) (first (submit->stack "map + pi" [1])))))

;; map errors
(t/deftest map-errors
  ;; empty stack
  (t/is (= "Stack empty" (:status (submit->state "map + 4"))))
  (t/is (= [] (:stack (submit->state "map + 4"))))
  ;; map + with no arg and only X on stack - Bug 2 fix: explicit arg always works
  ;; map + 5 on [5] -> [10.0] (not "Need two values")
  (t/is (= [10.0] (submit->stack "map + 5" [5])))
  ;; unknown op
  (t/is (= "Unknown: foo" (:status (submit->state "map foo 2" [1 2]))))
  (t/is (= [1 2] (:stack (submit->state "map foo 2" [1 2]))))
  ;; bad argument
  (t/is (= "Unknown: abc" (:status (submit->state "map + abc" [1 2]))))
  (t/is (= [1 2] (:stack (submit->state "map + abc" [1 2]))))
  ;; unary op with argument
  (t/is (= "chs takes no argument" (:status (submit->state "map chs 3" [1 2]))))
  (t/is (= [1 2] (:stack (submit->state "map chs 3" [1 2])))))

;; map info messages
(t/deftest map-info
  (t/is (= "(mapv #(+ % 4) stack)" (:info (submit->state "map + 4" [1 2 3]))))
  (t/is (= "(mapv #(- % 1) stack)" (:info (submit->state "map - 1" [1 2 3]))))
  (t/is (= "(mapv #(/ % 2) stack)" (:info (submit->state "map / 2" [2 4]))))
  (t/is (= "(mapv #(Math/pow % 2) stack)" (:info (submit->state "map ^ 2" [2 3]))))
  (t/is (= "(mapv - stack)" (:info (submit->state "map chs" [1 -2]))))
  (t/is (= "(mapv #(/ 1 %) stack)" (:info (submit->state "map inv" [2 4])))))

;; map button and flash
(t/deftest map-button-and-flash
  (t/is (= "map" (tc/button-for-token "map")))
  (t/is (= "map" (tc/flash-for-action (typed fresh "map") "enter"))))

;; ---------------------------------------------------------------
;; reduce — collapse the stack with a binary op
;; ---------------------------------------------------------------
(t/deftest reduce-basic
  (t/is (= [6.0] (submit->stack "reduce +" [1 2 3])))
  (t/is (= [24.0] (submit->stack "reduce *" [2 3 4])))
  (t/is (= [5.0] (submit->stack "reduce -" [10 3 2]))))

;; reduce with init value
(t/deftest reduce-with-init
  (t/is (= [16.0] (submit->stack "reduce + 10" [1 2 3])))
  (t/is (= [10.0] (submit->stack "reduce + 10"))))

;; reduce single element
(t/deftest reduce-single
  (t/is (= [7.0] (submit->stack "reduce +" [7]))))

;; reduce errors
(t/deftest reduce-errors
  ;; empty stack with no init
  (t/is (= "Stack empty" (:status (submit->state "reduce +" []))))
  (t/is (= [] (:stack (submit->state "reduce +" []))))
  ;; unknown op
  (t/is (= "Unknown: foo" (:status (submit->state "reduce foo" [1 2]))))
  (t/is (= [1 2] (:stack (submit->state "reduce foo" [1 2]))))
  ;; unary op
  (t/is (= "Unknown: chs" (:status (submit->state "reduce chs" [1 2]))))
  (t/is (= [1 2] (:stack (submit->state "reduce chs" [1 2])))))

;; reduce info messages
(t/deftest reduce-info
  (t/is (= "(reduce + stack)" (:info (submit->state "reduce +" [1 2 3]))))
  (t/is (= "(reduce * stack)" (:info (submit->state "reduce *" [2 3 4]))))
  (t/is (= "(reduce - stack)" (:info (submit->state "reduce -" [10 3 2]))))
  (t/is (= "(reduce + 10 stack)" (:info (submit->state "reduce + 10" [1 2 3])))))

;; reduce button and flash
(t/deftest reduce-button-and-flash
  (t/is (= "reduce" (tc/button-for-token "reduce")))
  (t/is (= "reduce" (tc/flash-for-action (typed fresh "reduce") "enter"))))

;; ---------------------------------------------------------------
;; linreg — linear regression over the whole stack (HP-15c L.R.)
;; ---------------------------------------------------------------
(defn- approx= [a b]
  (< (Math/abs (- a b)) 1e-9))

(t/deftest linreg-basic
  ;; points (0,1) (1,3) (2,5) -> y = 2x + 1
  (t/is (approx= 2.0 (first (submit->stack "linreg" [0 1 1 3 2 5]))))
  (t/is (approx= 1.0 (second (submit->stack "linreg" [0 1 1 3 2 5]))))
  ;; [1 2 2 4] -> y = 2x + 0
  (t/is (approx= 2.0 (first (submit->stack "linreg" [1 2 2 4]))))
  (t/is (approx= 0.0 (second (submit->stack "linreg" [1 2 2 4]))))
  ;; [1 1 2 2 3 2] -> y = 0.5x + 0.666...
  (t/is (approx= 0.5 (first (submit->stack "linreg" [1 1 2 2 3 2]))))
  (t/is (approx= 0.6666666666666666 (second (submit->stack "linreg" [1 1 2 2 3 2])))))

(t/deftest linreg-alias-lr
  (t/is (= (submit->stack "linreg" [1 2 2 4])
           (submit->stack "lr" [1 2 2 4]))))

(t/deftest linreg-info-message
  (t/is (= "y = 2x + 1  (3 points)"
           (:info (submit->state "linreg" [0 1 1 3 2 5])))))

(t/deftest linreg-error-odd-count
  (t/is (= [1 2 3] (:stack (submit->state "linreg" [1 2 3]))))
  (t/is (= "Need x y pairs" (:status (submit->state "linreg" [1 2 3])))))

(t/deftest linreg-error-not-enough-points
  (t/is (= [1 2] (:stack (submit->state "linreg" [1 2]))))
  (t/is (= "Need two points" (:status (submit->state "linreg" [1 2]))))
  (t/is (= [] (:stack (submit->state "linreg" []))))
  (t/is (= "Need two points" (:status (submit->state "linreg" [])))))

(t/deftest linreg-error-vertical-line
  (t/is (= [2 1 2 5] (:stack (submit->state "linreg" [2 1 2 5]))))
  (t/is (= "Vertical line" (:status (submit->state "linreg" [2 1 2 5])))))

(t/deftest linreg-button-for-token
  (t/is (= "linreg" (tc/button-for-token "linreg")))
  (t/is (= "linreg" (tc/button-for-token "lr"))))

;; ---------------------------------------------------------------
;; nice-step — grid step calculation for blueprint plot
;; ---------------------------------------------------------------
(t/deftest nice-step
  (t/is (= 1.0 (tc/nice-step 10 10)))
  (t/is (= 20.0 (tc/nice-step 100 8)))
  (t/is (approx= 0.5 (tc/nice-step 3 8))))

;; ---------------------------------------------------------------
;; plot-html — pure HTML generation (never execute the cmd's fn)
;; ---------------------------------------------------------------
(t/deftest plot-html-basic
  (let [html (tc/plot-html [[0 1] [1 3] [2 5]] 2.0 1.0)]
    (t/is (string? html))
    (t/is (clojure.string/includes? html "<svg"))
    (t/is (clojure.string/includes? html "y = 2x"))
    (t/is (clojure.string/includes? html "+ 1"))
    (t/is (= 3 (count (re-seq #"<circle" html))))))

(t/deftest plot-html-flat-y
  (let [html (tc/plot-html [[0 2] [1 2]] 0.0 2.0)]
    (t/is (string? html))
    (t/is (not (clojure.string/includes? html "NaN")))
    (t/is (not (clojure.string/includes? html "Infinity")))))

(t/deftest plot-html-blueprint
  (let [html (tc/plot-html [[0 1] [1 3] [2 5]] 2.0 1.0 "2026-10-29")]
    (t/is (string? html))
    (t/is (clojure.string/includes? html "LINEAR REGRESSION"))
    (t/is (clojure.string/includes? html "DATE"))
    (t/is (clojure.string/includes? html "2026-10-29"))
    (t/is (clojure.string/includes? html "POINTS"))
    (t/is (clojure.string/includes? html ">3<"))
    (t/is (clojure.string/includes? html "y = 2x"))
    (t/is (clojure.string/includes? html "+ 1"))
    (t/is (clojure.string/includes? html "<title>1, 3</title>"))
    (t/is (= 3 (count (re-seq #"<circle" html))))
    (t/is (not (clojure.string/includes? html "NaN")))
    (t/is (not (clojure.string/includes? html "Infinity")))
    (t/is (nil? (re-find #"[^#]>\d+/\d+<" html)))))  ; no uncoerced ratios in attributes

(t/deftest plot-html-arity-3
  (let [html (tc/plot-html [[0 1] [1 3]] 1.0 0.5)]
    (t/is (string? html))
    (t/is (clojure.string/includes? html "LINEAR REGRESSION"))))

(t/deftest plot-html-viewbox-and-clip
  (let [html (tc/plot-html [[0 1] [1 3] [2 5]] 2.0 1.0 "2026-10-29")]
    (t/is (clojure.string/includes? html "viewBox=\"0 0 960 1000\""))
    (t/is (clojure.string/includes? html "clip-path="))))

;; ---------------------------------------------------------------
;; Wolfram Engine helpers (phase 1, numeric)
;; ---------------------------------------------------------------
(t/deftest wl-num
  (t/is (= "2.5" (tc/wl-num 2.5)))
  (t/is (= "10000000000" (tc/wl-num 1.0E10)))
  (t/is (= "1.0*^20" (tc/wl-num 1.0E20)))       ; too big to be exact: stays real
  (t/is (= "1.0*^-7" (tc/wl-num 1.0E-7)))
  (t/is (= "-3" (tc/wl-num -3.0)))              ; whole -> exact integer
  (t/is (= "3" (tc/wl-num 3)))
  ;; Non-finite values keep the real format (wolfram-op blocks them
  ;; before sending anyway); -0.0 is the integer 0.
  (t/is (= "Infinity" (tc/wl-num Double/POSITIVE_INFINITY)))
  (t/is (= "-Infinity" (tc/wl-num Double/NEGATIVE_INFINITY)))
  (t/is (= "NaN" (tc/wl-num Double/NaN)))
  (t/is (= "0" (tc/wl-num -0.0))))

(t/deftest wolfram-call-line
  (t/is (= "tcReply[tcCall[\"Total\", {1, 2}]]"
           (tc/wolfram-call-line "Total" [1.0 2.0])))
  (t/is (= "tcReply[tcCall[\"Total\", {}]]"
           (tc/wolfram-call-line "Total" []))))

(t/deftest parse-wolfram-reply
  ;; NUM reply
  (let [r (tc/parse-wolfram-reply "TC\tOK\t4\tNUM\t10.\tTotal[{1, 2, 3, 4}] = 10")]
    (t/is (= 4 (:k r)))
    (t/is (= "NUM" (:kind r)))
    (t/is (= [10.0] (:values r)))
    (t/is (= "Total[{1, 2, 3, 4}] = 10" (:info r))))
  ;; LIST reply
  (let [r (tc/parse-wolfram-reply "TC\tOK\t3\tLIST\t1. 2. 3.\tSort[{3, 1, 2}] = {1, 2, 3}")]
    (t/is (= 3 (:k r)))
    (t/is (= "LIST" (:kind r)))
    (t/is (= [1.0 2.0 3.0] (:values r))))
  ;; empty LIST reply
  (let [r (tc/parse-wolfram-reply "TC\tOK\t3\tLIST\t\tSort[{}] = {}")]
    (t/is (= 3 (:k r)))
    (t/is (= "LIST" (:kind r)))
    (t/is (= [] (:values r))))
  ;; SYM reply (updated for symbolic values: k = 1, value is expr)
  (let [r (tc/parse-wolfram-reply "TC\tOK\t1\tSYM\t2*I\tSqrt[-4] = 2*I")]
    (t/is (= 1 (:k r)))
    (t/is (= "SYM" (:kind r)))
    (t/is (= [(tc/expr "2*I")] (:values r)))
    (t/is (= "Sqrt[-4] = 2*I" (:info r))))
  ;; GFX reply
  (let [r (tc/parse-wolfram-reply "TC\tOK\t0\tGFX\t/var/tuiCalc-wolfram.svg\tListPlot[{1, 4, 9}]")]
    (t/is (= 0 (:k r)))
    (t/is (= "GFX" (:kind r)))
    (t/is (= "/var/tuiCalc-wolfram.svg" (:path r))))
  ;; ERR reply
  (let [r (tc/parse-wolfram-reply "TC\tERR\tNeed 2 values")]
    (t/is (= "Need 2 values" (:error r))))
  ;; nil line
  (t/is (= {:error "Wolfram: bad reply"} (tc/parse-wolfram-reply nil)))
  ;; garbage line
  (t/is (= {:error "Wolfram: bad reply"} (tc/parse-wolfram-reply "garbage"))))

(t/deftest submit-wolfram-total
  (let [sent (atom [])
        state (assoc (first (tc/init)) :stack [1.0 2.0 3.0 4.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t4\tNUM\t10.\tTotal[{1, 2, 3, 4}] = 10"))
        state (typed state "Total")
        [result-state cmd] (tc/submit state)]
    (t/is (= [10.0] (:stack result-state)))
    (t/is (= "Total[{1, 2, 3, 4}] = 10" (:info result-state)))
    (t/is (nil? cmd))
    (t/is (= ["tcReply[tcCall[\"Total\", {1, 2, 3, 4}]]"] @sent))))

(t/deftest submit-wolfram-sqrt
  (let [sent (atom [])
        state (assoc (first (tc/init)) :stack [5.0 9.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tNUM\t3.\tSqrt[9] = 3"))
        state (typed state "Sqrt")
        [result-state _] (tc/submit state)]
    (t/is (= [5.0 3.0] (:stack result-state)))))

(t/deftest submit-wolfram-sort
  (let [sent (atom [])
        state (assoc (first (tc/init)) :stack [3.0 1.0 2.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t3\tLIST\t1. 2. 3.\tSort[{3, 1, 2}] = {1, 2, 3}"))
        state (typed state "Sort")
        [result-state _] (tc/submit state)]
    (t/is (= [1.0 2.0 3.0] (:stack result-state)))))

(t/deftest submit-wolfram-sym-and-err
  ;; SYM reply: stack gets symbolic result, info set
  (let [state (assoc (first (tc/init)) :stack [-4.0]
                     :wolfram (fn [_] "TC\tOK\t1\tSYM\t2*I\tSqrt[-4] = 2*I"))
        state (typed state "Sqrt")
        [result-state _] (tc/submit state)]
    (t/is (= [(tc/expr "2*I")] (:stack result-state)))
    (t/is (= "Sqrt[-4] = 2*I" (:info result-state))))
  ;; ERR reply: stack unchanged, status set
  (let [state (assoc (first (tc/init)) :stack [5.0]
                     :wolfram (fn [_] "TC\tERR\tNeed 2 values"))
        state (typed state "Sqrt")
        [result-state _] (tc/submit state)]
    (t/is (= [5.0] (:stack result-state)))
    (t/is (= "Need 2 values" (:status result-state)))))

(t/deftest submit-wolfram-no-engine
  (let [state (assoc (first (tc/init)) :stack [9.0] :wolfram nil)]
    (t/is (= "No Wolfram Engine (install wolframscript)"
             (:status (first (tc/submit (typed state "Sqrt"))))))))

(t/deftest submit-wolfram-nan
  (let [sent (atom [])
        state (assoc (first (tc/init)) :stack [Double/NaN]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tNUM\t0.\tSqrt[NaN] = 0"))
        state (typed state "Sqrt")
        [result-state _] (tc/submit state)]
    (t/is (= "Wolfram needs finite numbers" (:status result-state)))
    (t/is (= [] @sent))))

(t/deftest run-action-wolfram-sqrt
  (let [sent (atom [])
        state (assoc (first (tc/init)) :stack []
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tNUM\t3.\tSqrt[9] = 3"))
        state (update state :input assoc :value (vec "9"))
        [result-state _] (tc/run-action state "Sqrt")]
    (t/is (some #(clojure.string/includes? % "{9}") @sent))
    (t/is (= [3.0] (:stack result-state)))))

(t/deftest run-action-wolfram-plot
  (let [sent (atom [])
        state (assoc (first (tc/init)) :stack [1.0 4.0 9.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t0\tGFX\t/var/tuiCalc-wolfram.svg\tListPlot[{1, 4, 9}]"))
        [result-state cmd] (tc/run-action state "ListPlot")]
    (t/is (= [1.0 4.0 9.0] (:stack result-state)))
    (t/is (clojure.string/includes? (:info result-state) "ListPlot"))
    (t/is (some? cmd))))

(t/deftest submit-lowercase-unknown-and-pi
  ;; lowercase total -> Unknown
  (let [state (assoc (first (tc/init)) :stack []
                     :wolfram (fn [_] (throw (Exception. "should not call"))))]
    (t/is (= "Unknown: total" (:status (first (tc/submit (typed state "total")))))))
  ;; Pi still pushes Math/PI
  (t/is (= [Math/PI] (submit->stack "Pi"))))

;; ---------------------------------------------------------------
;; sig6 — 6-significant-digit formatting
;; ---------------------------------------------------------------
(t/deftest sig6
  (t/is (= "2" (tc/sig6 2.0)))
  (t/is (= "0.882353" (tc/sig6 0.8823529411764706)))
  (t/is (= "1.83824" (tc/sig6 1.838235294117647))))

;; ---------------------------------------------------------------
;; linreg info with negative intercept
;; ---------------------------------------------------------------
(t/deftest linreg-info-negative-intercept
  (t/is (= "y = 2x − 1  (3 points)"
           (:info (submit->state "lr" [0 -1 1 1 2 3])))))

;; ---------------------------------------------------------------
;; plot-geometry — equal-scale square graph paper with near-origin axes
;; ---------------------------------------------------------------
(t/deftest plot-geometry-near-origin
  (let [g (tc/plot-geometry [[4 5] [6 6.5] [7 7.2] [10 10.4]] 0.905333 1.164)]
    (t/is (= 0.0 (:xmin g)))
    (t/is (= 0.0 (:ymin g)))))

(t/deftest plot-geometry-origin-always-included
  (let [g (tc/plot-geometry [[1000 1] [1010 2]] 0.1 -99)]
    (t/is (= 0.0 (:xmin g)))
    (t/is (= 0.0 (:ymin g)))))

(t/deftest plot-geometry-square-cluster
  (let [g (tc/plot-geometry [[8 8] [9 9] [10 10]] 1.0 0.0)]
    (t/is (= 0.0 (:xmin g)))
    (t/is (= 0.0 (:ymin g)))
    (t/is (approx= (:w g) (:h g)))))

(t/deftest plot-geometry-equal-scale
  ;; Case 1: near-origin points
  (let [g1 (tc/plot-geometry [[4 5] [6 6.5] [7 7.2] [10 10.4]] 0.905333 1.164)]
    (t/is (approx= (:w g1) (* (:scale g1) (- (:xmax g1) (:xmin g1)))))
    (t/is (approx= (:h g1) (* (:scale g1) (- (:ymax g1) (:ymin g1)))))
    (t/is (<= (:w g1) 760.0001))
    (t/is (<= (:h g1) 760.0001))
    (t/is (or (approx= (:w g1) 760) (approx= (:h g1) 760))))
  ;; Case 2: far-from-origin points (now includes origin)
  (let [g2 (tc/plot-geometry [[1000 1] [1010 2]] 0.1 -99)]
    (t/is (approx= (:w g2) (* (:scale g2) (- (:xmax g2) (:xmin g2)))))
    (t/is (approx= (:h g2) (* (:scale g2) (- (:ymax g2) (:ymin g2)))))
    (t/is (<= (:w g2) 760.0001))
    (t/is (<= (:h g2) 760.0001))
    (t/is (or (approx= (:w g2) 760) (approx= (:h g2) 760)))))

(t/deftest linreg-submit-returns-cmd-on-success
  (let [state (first (tc/init))
        state (assoc state :stack [0 1 1 3 2 5])
        state (typed state "lr")
        [result-state cmd] (tc/submit state)]
    (t/is (= :cmd (:type cmd)))
    (t/is (= [2.0 1.0] (:stack result-state))))
  ;; odd count returns nil cmd
  (let [state (first (tc/init))
        state (assoc state :stack [1 2 3])
        state (typed state "lr")
        [result-state cmd] (tc/submit state)]
    (t/is (nil? cmd))
    (t/is (= "Need x y pairs" (:status result-state)))))

;; ---------------------------------------------------------------
;; linreg-run-action
;; ---------------------------------------------------------------
(t/deftest linreg-run-action
  (let [state (assoc (first (tc/init)) :stack [0 1 1 3 2 5])
        ret (tc/run-action state "linreg")
        [result-state cmd] ret]
    (t/is (vector? ret))
    (t/is (= 2 (count ret)))
    (t/is (= :cmd (:type cmd)))
    (t/is (approx= 2.0 (first (:stack result-state))))
    (t/is (approx= 1.0 (second (:stack result-state))))))

;; ---------------------------------------------------------------
;; Regression tests for map/reduce bugs (fix-map-reduce.md)
;; ---------------------------------------------------------------
;; Bug 1: Typed operator keys fire instantly when input is not blank/number
;; Outside a prompt, typing "map " then "+" should NOT execute + immediately
(t/deftest map-operator-key-does-not-fire-instantly
  ;; Type "map " then press "+": stack unchanged, input is "map +"
  ;; Message map structure: {:type :key-press, :key "+", :alt false, :ctrl false, :shift false}
  ;; update-fn returns [state command]
  (let [state1 (typed fresh "map ")
        msg-plus {:type :key-press :key "+" :alt false :ctrl false :shift false}
        [state2 _cmd] (tc/update-fn state1 msg-plus)]
    (t/is (= [] (:stack state2)))
    (t/is (= "map +" (apply str (:value (:input state2))))))
  ;; "12" then "+" still adds (HP-style: number then op commits)
  ;; Type "12" into input on stack [2], send key "+" -> [14.0]
  (let [state1 (typed (assoc fresh :stack [2.0]) "12")
        msg-plus {:type :key-press :key "+" :alt false :ctrl false :shift false}
        [state2 _cmd] (tc/update-fn state1 msg-plus)]
    (t/is (= [14.0] (:stack state2)))))

;; Bug 2: Typed arg equal to X deletes X (remove the use-x-as-operand branch)
(t/deftest map-arg-equal-to-x-does-not-delete
  ;; [1 2 3 4] + map + 4 -> [5 6 7 8] (not [5 6 7])
  (t/is (= [5.0 6.0 7.0 8.0] (submit->stack "map + 4" [1 2 3 4])))
  ;; [5] + map + 5 -> [10] (not "Need two values")
  (t/is (= [10.0] (submit->stack "map + 5" [5]))))

;; Bug 3: reduce? prompt runs map (must call handle-reduce for pending "reduce")
(t/deftest reduce-two-step-runs-reduce-not-map
  ;; Start reduce prompt, then enter "+": should collapse to single value
  (let [state1 (typed (assoc fresh :stack [1 2 3]) "reduce")
        state2 (first (tc/submit state1))]
    (t/is (= "reduce" (:pending state2)))
    (t/is (= [1 2 3] (:stack state2)))
    ;; Now type "+" and submit
    (let [state3 (typed state2 "+")
          state4 (first (tc/submit state3))]
      (t/is (= [6.0] (:stack state4)))))
  ;; Verify: [1 2 3] reduce + = 6, not [6 6 6] (which map + would give)
  (t/is (= [6.0] (submit->stack "reduce +" [1 2 3]))))

;; reduce? prompt with key press (RPN style)
(t/deftest reduce-key-press-at-prompt
  (let [msg-plus {:type :key-press :key "+" :alt false :ctrl false :shift false}]
    ;; [1 2 3]: reduce Enter, key "+" -> [6]
    (let [state1 (typed (assoc fresh :stack [1 2 3]) "reduce")
          [state2 _] (tc/submit state1)
          [state3 _] (tc/update-fn state2 msg-plus)]
      (t/is (= [6.0] (:stack state3))))
    ;; [1 2 3]: reduce Enter, type "10", key "+" -> [16]
    (let [state1 (typed (assoc fresh :stack [1 2 3]) "reduce")
          [state2 _] (tc/submit state1)
          state3 (typed state2 "10")
          [state4 _] (tc/update-fn state3 msg-plus)]
      (t/is (= [16.0] (:stack state4))))))

;; Bug 4: Number at map?/reduce? prompt crashes (no clause for map/reduce in case)
(t/deftest number-at-map-prompt-no-crash
  ;; At map? prompt, entering a number (not a valid level) -> "Unknown: N", no crash
  (let [state1 (typed fresh "map")
        state2 (first (tc/submit state1))]
    (t/is (= "map" (:pending state2)))
    ;; Now type a number and submit - this used to crash with IllegalArgumentException
    (let [state3 (typed state2 "2")
          state4 (first (tc/submit state3))]
      (t/is (= "Unknown: 2" (:status state4)))
      (t/is (= [] (:stack state4)))))
  ;; Same for reduce
  (let [state1 (typed fresh "reduce")
        state2 (first (tc/submit state1))]
    (t/is (= "reduce" (:pending state2)))
    (let [state3 (typed state2 "3")
          state4 (first (tc/submit state3))]
      (t/is (= "Unknown: 3" (:status state4)))
      (t/is (= [] (:stack state4))))))

;; Bug 5: map foo on empty stack should say "Unknown: foo" (check op before empty-stack)
(t/deftest map-unknown-op-on-empty-stack
  (t/is (= "Unknown: foo" (:status (submit->state "map foo"))))
  (t/is (= [] (:stack (submit->state "map foo"))))
  ;; Same for reduce - note: "bar" is parsed as the op, not "reduce bar"
  (t/is (= "Unknown: bar" (:status (submit->state "reduce bar"))))
  (t/is (= [] (:stack (submit->state "reduce bar")))))

;; RPN-style operator key at map?/reduce? prompt (amendment to bug 1)
(t/deftest rpn-operator-key-at-map-prompt
  ;; Message map: {:type :key-press, :key "+", :alt false, :ctrl false, :shift false}
  ;; update-fn returns [state command]
  (let [msg-plus {:type :key-press :key "+" :alt false :ctrl false :shift false}
        msg-minus {:type :key-press :key "-" :alt false :ctrl false :shift false}]
    ;; [1 2 3]: map Enter, type "4", key "+" -> [5.0 6.0 7.0]
    (let [state1 (typed (assoc fresh :stack [1 2 3]) "map")
          [state2 _] (tc/submit state1)
          state3 (typed state2 "4")
          [state4 _] (tc/update-fn state3 msg-plus)]
      (t/is (= [5.0 6.0 7.0] (:stack state4))))
    ;; [1 2 3 10]: map Enter, key "+" -> [11 12 13] (X=10 is operand)
    (let [state1 (assoc fresh :stack [1 2 3 10])
          state2 (typed state1 "map")
          [state3 _] (tc/submit state2)
          [state4 _] (tc/update-fn state3 msg-plus)]
      (t/is (= [11.0 12.0 13.0] (mapv double (:stack state4)))))
    ;; [1 2 3]: reduce Enter, key "+" -> [6]
    (let [state1 (typed (assoc fresh :stack [1 2 3]) "reduce")
          [state2 _] (tc/submit state1)
          [state3 _] (tc/update-fn state2 msg-plus)]
      (t/is (= [6.0] (:stack state3))))
    ;; [1 2 3]: reduce Enter, type "10", key "+" -> [16]
    (let [state1 (typed (assoc fresh :stack [1 2 3]) "reduce")
          [state2 _] (tc/submit state1)
          state3 (typed state2 "10")
          [state4 _] (tc/update-fn state3 msg-plus)]
      (t/is (= [16.0] (:stack state4))))
    ;; [1 2 3]: map Enter, type "1", key "-" -> [0 1 2]
    (let [state1 (typed (assoc fresh :stack [1 2 3]) "map")
          [state2 _] (tc/submit state1)
          state3 (typed state2 "1")
          [state4 _] (tc/update-fn state3 msg-minus)]
      (t/is (= [0.0 1.0 2.0] (:stack state4))))))

;; ---------------------------------------------------------------
;; Two-step prompt: map? / reduce?
;; ---------------------------------------------------------------
(t/deftest map-two-step-prompt
  ;; bare map starts prompt
  (t/is (= "map" (:pending (submit->state "map" [1 2 3]))))
  (t/is (= [1 2 3] (:stack (submit->state "map" [1 2 3]))))
  ;; empty input cancels (no dup)
  (t/is (= [1 2 3] (:stack (submit->state "map" [1 2 3])))))

(t/deftest reduce-two-step-prompt
  ;; bare reduce starts prompt
  (t/is (= "reduce" (:pending (submit->state "reduce" [1 2 3]))))
  (t/is (= [1 2 3] (:stack (submit->state "reduce" [1 2 3])))))

;; ---------------------------------------------------------------
;; Undo (spec-undo.md): every stack change saves the old stack in
;; :undo, recorded once in the update-fn wrapper (track-undo).
;; ---------------------------------------------------------------
(defn ctrl-key [k]
  {:type :key-press :key k :alt false :ctrl true :shift false})

(defn wolfram-stub [reply]
  (fn [_line]
    reply))

;; One typed command + Enter, recorded the way update-fn records it.
(defn submit+track [state txt]
  (let [s (typed state txt)]
    (tc/track-undo s (first (tc/submit s)))))

(t/deftest undo-basic
  (let [s (tc/undo (assoc fresh :stack [3.0] :undo [[1.0 2.0]]))]
    (t/is (= [1.0 2.0] (:stack s)))
    (t/is (= [] (:undo s)))
    (t/is (= "undo" (:info s)))))

(t/deftest undo-nothing-to-undo
  (let [s (tc/undo (assoc fresh :stack [1.0] :undo []))]
    (t/is (= [1.0] (:stack s)))
    (t/is (= "Nothing to undo" (:status s)))))

(t/deftest track-undo-records-changes
  (let [old (assoc fresh :stack [1.0 2.0] :undo [[5.0]])]
    ;; stack changed, history untouched -> old stack appended
    (t/is (= [[5.0] [1.0 2.0]]
             (:undo (tc/track-undo old (assoc old :stack [3.0])))))
    ;; stack unchanged -> nothing recorded
    (t/is (= [[5.0]]
             (:undo (tc/track-undo old (assoc old :info "x")))))
    ;; the action itself touched history (an undo) -> nothing recorded
    (t/is (= []
             (:undo (tc/track-undo old (assoc old :stack [3.0] :undo [])))))))

(t/deftest track-undo-limit
  (let [old (assoc fresh :stack [100.0] :undo (mapv vector (range 100)))
        s   (tc/track-undo old (assoc old :stack [101.0]))]
    (t/is (= 100 (count (:undo s))))
    (t/is (= [1] (first (:undo s))))      ; oldest ([0]) dropped
    (t/is (= [100.0] (peek (:undo s))))))

(t/deftest undo-user-story-total-undo-mean
  (let [s1 (submit+track (assoc fresh
                                :stack [1.0 2.0 3.0 4.0]
                                :wolfram (wolfram-stub "TC\tOK\t4\tNUM\t10.\tTotal[{1, 2, 3, 4}] = 10"))
                         "Total")
        [s2 _] (tc/update-fn s1 (ctrl-key "z"))
        s3 (submit+track (assoc s2 :wolfram (wolfram-stub "TC\tOK\t4\tNUM\t2.5\tMean[{1, 2, 3, 4}] = 2.5"))
                         "Mean")]
    (t/is (= [10.0] (:stack s1)))
    (t/is (= [1.0 2.0 3.0 4.0] (:stack s2)))
    (t/is (= [] (:undo s2)))
    (t/is (= [2.5] (:stack s3)))))

(t/deftest undo-two-steps
  (let [s1 (submit+track fresh "5")
        s2 (submit+track s1 "chs")
        [s3 _] (tc/update-fn s2 (ctrl-key "z"))
        [s4 _] (tc/update-fn s3 (ctrl-key "z"))]
    (t/is (= [5.0] (:stack s1)))
    (t/is (= [-5.0] (:stack s2)))
    (t/is (= [5.0] (:stack s3)))
    (t/is (= [] (:stack s4)))))

(t/deftest update-fn-records-undo
  ;; ctrl+s = CHS through the real update-fn wrapper
  (let [[s _] (tc/update-fn (assoc fresh :stack [2.0]) (ctrl-key "s"))]
    (t/is (= [-2.0] (:stack s)))
    (t/is (= [[2.0]] (:undo s)))))

(t/deftest undo-entry-points
  (let [s (assoc fresh :stack [3.0] :undo [[1.0 2.0]])]
    (t/is (= [1.0 2.0] (:stack (first (tc/run-action s "undo")))))
    (t/is (= [1.0 2.0] (:stack (first (tc/submit (typed s "undo")))))))
  (t/is (= "undo" (tc/button-for-token "undo")))
  (let [line  (nth (tc/help-lines) 2)
        start (.indexOf line "[undo]")
        y     (+ tc/help-row0 3)]
    (t/is (<= 0 start))
    (t/is (= "undo" (tc/find-button-at (inc start) y)))
    (t/is (= "undo" (tc/find-button-at (+ start 6) y)))
    (t/is (not= "undo" (tc/find-button-at (+ start 7) y)))))

(t/deftest init-has-undo
  (t/is (= [] (:undo (first (tc/init))))))

(t/deftest wolfram-plot-html-utf8-and-console-caption
  ;; The title has an em dash: without a charset it shows as "â€”".
  (let [html (tc/wolfram-plot-html "/tmp/x.svg" "Total[{1, 2}] = 3")]
    (t/is (clojure.string/includes? html "<meta charset=\"utf-8\">"))
    (t/is (clojure.string/includes? html "ui-monospace"))
    (t/is (clojure.string/includes? html "Total[{1, 2}] = 3"))))

;; ---------------------------------------------------------------
;; Wolfram menu folders (spec-menus-r1.md)
;; ---------------------------------------------------------------

;; Test 1: top-level menu row shows folder buttons
(t/deftest menu-01-top-level-row
  (t/is (= [["menu:Math" "[Math ▸]"]
            ["menu:Stats" "[Stats ▸]"]
            ["menu:Lists" "[Lists ▸]"]
            ["menu:Plots" "[Plots ▸]"]]
           (tc/menu-row {}))))

;; Test 2: folder view shows back button plus functions
(t/deftest menu-02-folder-row
  (t/is (= [["menu:back" "[◂ back]"]
            ["Total" "[Total]"]
            ["Mean" "[Mean]"]
            ["Median" "[Median]"]
            ["StandardDeviation" "[StandardDeviation]"]
            ["Variance" "[Variance]"]]
           (tc/menu-row {:menu "Stats"}))))

;; Test 3: run-action for menu:folder sets :menu
(t/deftest menu-03-run-action-sets-menu
  (let [[new-state cmd] (tc/run-action fresh "menu:Stats")]
    (t/is (= "Stats" (:menu new-state)))
    (t/is (= [] (:stack new-state)))
    (t/is (nil? cmd))))

;; Test 4: run-action for menu:back clears :menu
(t/deftest menu-04-run-action-back
  (let [[new-state cmd] (tc/run-action (assoc fresh :menu "Stats") "menu:back")]
    (t/is (nil? (:menu new-state)))
    (t/is (nil? cmd))))

;; Test 5: help-lines has 4 rows, 4th row shows folders or functions
(t/deftest menu-05-help-lines-count-and-content
  (t/is (= "[Math ▸]  [Stats ▸]  [Lists ▸]  [Plots ▸]"
           (nth (tc/help-lines) 3)))
  (t/is (= 4 (count (tc/help-lines))))
  (t/is (= 4 (count (tc/help-lines {:menu "Plots"})))))

;; Test 6: find-button-at works in folder view, 2-arity defaults to top level
(t/deftest menu-06-find-button-at-in-folder
  (let [st {:menu "Stats"}
        line (nth (tc/help-lines st) 3)
        start (.indexOf line "[Mean]")
        y (+ tc/help-row0 4)]
    (t/is (<= 0 start))
    (t/is (= "Mean" (tc/find-button-at st (inc start) y)))
    (t/is (= "Mean" (tc/find-button-at st (+ start 6) y)))
    (t/is (not= "Mean" (tc/find-button-at (inc start) y)))))

;; Test 7: clicking a folder button opens that folder
(t/deftest menu-07-click-folder-button
  (let [st fresh
        start (.indexOf (nth (tc/help-lines) 3) "[Stats ▸]")
        y (+ tc/help-row0 4)]
    (t/is (<= 0 start))
    (let [[new-state _] (tc/handle-click st (inc start) y)]
      (t/is (= "Stats" (:menu new-state))))))

;; Test 8: button-for-token looks up menu functions
(t/deftest menu-08-button-for-token-menu
  (t/is (= "Median" (tc/button-for-token "median")))
  (t/is (= "Sqrt" (tc/button-for-token "sqrt")))
  (t/is (= "ListPlot" (tc/button-for-token "listplot")))
  (t/is (= "Factorial" (tc/button-for-token "factorial")))
  (t/is (nil? (tc/button-for-token "plot3d")))
  (t/is (= "drop" (tc/button-for-token "drop"))))

;; Test 9: menu-folder-of finds the folder for a function
(t/deftest menu-09-menu-folder-of
  (t/is (= "Stats" (tc/menu-folder-of "Mean")))
  (t/is (= "Math" (tc/menu-folder-of "Sqrt")))
  (t/is (= "Plots" (tc/menu-folder-of "Histogram")))
  (t/is (nil? (tc/menu-folder-of "Plot3D"))))

;; Test 10: with-flash sets :menu for menu functions
(t/deftest menu-10-with-flash
  (t/is (= "Stats" (:menu (tc/with-flash fresh "Median"))))
  (t/is (= "Plots" (:menu (tc/with-flash (assoc fresh :menu "Plots") "drop")))))

;; Test 11: folder rows fit screen share (<=70 chars)
(t/deftest menu-11-folder-row-length
  (doseq [[folder-name _] tc/wolfram-menu]
    (let [row (nth (tc/help-lines {:menu folder-name}) 3)]
      (t/is (<= (count row) 70) (str "Folder " folder-name " row too long: " (count row) " chars")))))

;; Update test 12a: wolfram-button-and-help uses folder view
(t/deftest wolfram-button-and-help
  (t/is (= "Sqrt" (tc/button-for-token "sqrt")))
  (t/is (= "Total" (tc/button-for-token "total")))
  ;; Look up labels in their folder's row
  (let [math-row (nth (tc/help-lines {:menu "Math"}) 3)
        stats-row (nth (tc/help-lines {:menu "Stats"}) 3)
        lists-row (nth (tc/help-lines {:menu "Lists"}) 3)
        plots-row (nth (tc/help-lines {:menu "Plots"}) 3)]
    (t/is (clojure.string/includes? math-row "[Sqrt]"))
    (t/is (clojure.string/includes? math-row "[Mod]"))
    (t/is (clojure.string/includes? stats-row "[Total]"))
    (t/is (clojure.string/includes? stats-row "[Mean]"))
    (t/is (clojure.string/includes? lists-row "[Sort]"))
    (t/is (clojure.string/includes? lists-row "[Max]"))
    (t/is (clojure.string/includes? lists-row "[Min]"))
    (t/is (clojure.string/includes? plots-row "[ListPlot]"))))

;; Update test 12b: click-wolfram-total-button uses 3-arity with :menu
(t/deftest click-wolfram-total-button
  (let [st {:menu "Stats"}
        line (nth (tc/help-lines st) 3)
        start (.indexOf line "[Total]")
        y (+ tc/help-row0 4)]
    (t/is (<= 0 start))
    (t/is (= "Total" (tc/find-button-at st (inc start) y)))
    (t/is (= "Total" (tc/find-button-at st (+ start 6) y)))
    (t/is (not= "Total" (tc/find-button-at st (+ start 8) y)))))

;; handle-click must hit-test the rows on screen, not the top level:
;; [◂ back] sits where [Math ▸] is at the top level.
(t/deftest menu-12-click-back-inside-folder
  (let [st    (assoc fresh :menu "Stats")
        start (.indexOf (nth (tc/help-lines st) 3) "[◂ back]")
        [s _] (tc/handle-click st (inc start) (+ tc/help-row0 4))]
    (t/is (= 0 start))
    (t/is (nil? (:menu s)))))

(t/deftest pow-token-flashes-power-button
  (t/is (= "^" (tc/button-for-token "pow"))))

;; ---------------------------------------------------------------
;; Wolfram type-to-filter search (spec-menus-r2.md)
;; ---------------------------------------------------------------

;; Test 1: search-matches returns Mean first for "Mea", count 13
(t/deftest search-01-search-matches-mea
  (t/is (= "Mean" (first (tc/search-matches "Mea"))))
  (t/is (= 13 (count (tc/search-matches "Mea")))))

;; Test 2: search-matches for "Fib"
(t/deftest search-02-search-matches-fib
  (t/is (= ["Fibonacci" "Fibonorial"] (tc/search-matches "Fib"))))

;; Test 3: search-matches for "Lis" - menu functions first, then shortest
(t/deftest search-03-search-matches-lis
  (t/is (= ["ListPlot" "ListLinePlot" "List"] (take 3 (tc/search-matches "Lis")))))

;; Test 4: search-matches for non-existent prefix
(t/deftest search-04-search-matches-zzq
  (t/is (= [] (tc/search-matches "Zzq"))))

;; Test 5: search-pick returns exact match or first match or unchanged
(t/deftest search-05-search-pick
  (t/is (= "Fibonacci" (tc/search-pick "Fib")))
  (t/is (= "Sqrt" (tc/search-pick "Sqrt")))
  (t/is (= "Max" (tc/search-pick "Max")))
  (t/is (= "Zzq" (tc/search-pick "Zzq"))))

;; Test 6: search-row for "Fib"
(t/deftest search-06-search-row-fib
  (t/is (= [["run:Fibonacci" "[Fibonacci]"]
            ["run:Fibonorial" "[Fibonorial]"]]
           (tc/search-row "Fib"))))

;; Test 7: search-row for "Mea" and "Zzq"
(t/deftest search-07-search-row-mea-zzq
  (t/is (= [["run:Mean" "[Mean]"]
            ["run:MeanShift" "[MeanShift]"]
            ["run:MeanAround" "[MeanAround]"]
            ["run:MeanFilter" "[MeanFilter]"]
            ["run:MeanDeviation" "[MeanDeviation]"]
            [nil "+8"]]
           (tc/search-row "Mea")))
  (t/is (= [[nil "(no Wolfram match)"]]
           (tc/search-row "Zzq"))))

;; Test 8: row length constraint for various prefixes
(t/deftest search-08-row-length-constraint
  (doseq [p ["M" "Me" "Mea" "Lis" "S" "P"]]
    (t/is (<= (count (nth (tc/help-lines (typed fresh p)) 3)) 70)
          (str "Prefix " p " row too long: "
               (count (nth (tc/help-lines (typed fresh p)) 3)) " chars"))))

;; Test 9: menu-row picks search only for Capitalized word
(t/deftest search-09-menu-row-search-vs-folders
  (t/is (= (tc/search-row "Fib")
           (tc/menu-row (typed fresh "Fib"))))
  (t/is (= (tc/menu-row fresh)
           (tc/menu-row (typed fresh "map"))))
  (t/is (= (tc/menu-row fresh)
           (tc/menu-row (typed fresh "Mod 3"))))
  (t/is (= (tc/menu-row fresh)
           (tc/menu-row (assoc (typed fresh "Fib") :pending "get")))))

;; Test 10: ENTER runs the pick (Fibonacci on stack [10])
(t/deftest search-10-enter-runs-pick
  (let [sent (atom [])
        state (assoc fresh :stack [10.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tNUM\t55.\tFibonacci[10] = 55"))
        state (typed state "Fib")
        [result-state _] (tc/submit state)]
    (t/is (some #(clojure.string/includes? % "Fibonacci") @sent))
    (t/is (= [55.0] (:stack result-state)))))

;; Test 11: clicking a search result runs the action
(t/deftest search-11-click-search-result
  (let [sent (atom [])
        state (assoc fresh :stack [10.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tNUM\t55.\tFibonacci[10] = 55"))
        state (typed state "Fib")
        line (nth (tc/help-lines state) 3)
        start (.indexOf line "[Fibonacci]")]
    (t/is (<= 0 start))
    (t/is (= "run:Fibonacci"
             (tc/find-button-at state (inc start) (+ tc/help-row0 4))))
    (let [[result-state _] (tc/run-action state "run:Fibonacci")]
      (t/is (= [55.0] (:stack result-state)))
      (t/is (= "" (tc/input-text result-state))))
    (let [[result-state _] (tc/handle-click state (inc start) (+ tc/help-row0 4))]
      (t/is (= [55.0] (:stack result-state))))))

;; Test 12: flash-for-action for search and non-clickable +N
(t/deftest search-12-flash-and-non-clickable
  (t/is (= "Mean" (tc/flash-for-action (typed fresh "Mea") "enter")))
  (t/is (= "Median" (tc/flash-for-action fresh "run:Median")))
  (t/is (= "CHS" (tc/flash-for-action (typed fresh "chs") "enter")))
  (let [line (nth (tc/help-lines (typed fresh "Mea")) 3)
        start (.indexOf line "+8")]
    (t/is (<= 0 start))
    (t/is (nil? (tc/find-button-at (typed fresh "Mea")
                                   (inc start)
                                   (+ tc/help-row0 4))))))

(t/deftest search-13-flash-uses-first-word
  ;; flash-for-action looks at the command word, not the whole input.
  (t/is (= "map" (tc/flash-for-action (typed fresh "map + 4") "enter")))
  (t/is (= "Mod" (tc/flash-for-action (typed fresh "Mod 3") "enter"))))

(t/deftest search-14-view-renders-search-row
  ;; A nil-action label ("+8") must not be mistaken for the flashing
  ;; button when no flash is running (that crashed view).
  (let [out (tc/view (typed fresh "Mea"))]
    (t/is (clojure.string/includes? out "[MeanDeviation]"))
    (t/is (clojure.string/includes? out "+8"))))

;; ---------------------------------------------------------------
;; Symbolic stack values (spec-sym-r1.md)
;; ---------------------------------------------------------------

;; Test 1: expr and expr?
(t/deftest sym-01-expr-and-expr-question
  (t/is (= {:wl "x"} (tc/expr "x")))
  (t/is (true? (tc/expr? (tc/expr "x"))))
  (t/is (false? (tc/expr? 2.0))))

;; Test 2: show-value and show-cell
(t/deftest sym-02-show-value-and-show-cell
  (t/is (= "2.5" (tc/show-value 2.5)))
  (t/is (= "x^2" (tc/show-value (tc/expr "x^2"))))
  (let [long-expr (tc/expr (apply str (repeat 40 "a")))]
    (t/is (= 28 (count (tc/show-cell long-expr))))
    (t/is (= "…" (subs (tc/show-cell long-expr) 27 28))))
  (let [exact-expr (tc/expr (apply str (repeat 28 "b")))]
    (t/is (= 28 (count (tc/show-cell exact-expr))))))

;; Test 3: parse-value
(t/deftest sym-03-parse-value
  (t/is (= (tc/expr "x") (tc/parse-value "'x")))
  (t/is (= (tc/expr "alpha2") (tc/parse-value "'alpha2")))
  (t/is (= 2.5 (tc/parse-value "2.5")))
  (t/is (nil? (tc/parse-value "'2x")))
  (t/is (nil? (tc/parse-value "x"))))

;; Test 4: submit pushes quoted symbols
(t/deftest sym-04-submit-quoted-symbols
  (t/is (= [(tc/expr "x")] (submit->stack "'x")))
  (t/is (= [(tc/expr "Pi")] (submit->stack "'Pi"))))

;; Test 5: wl-value for mixed args
(t/deftest sym-05-wl-value-mixed
  (t/is (= "tcReply[tcCall[\"Total\", {1, (x)}]]"
           (tc/wolfram-call-line "Total" [1.0 (tc/expr "x")]))))

;; Test 6: wolfram-apply-line
(t/deftest sym-06-wolfram-apply-line
  (t/is (= "tcReply[tcApply[\"Plus\", {(x), 1}]]"
           (tc/wolfram-apply-line "Plus" [(tc/expr "x") 1.0]))))

;; Test 7: parse-wolfram-reply SYM
(t/deftest sym-07-parse-wolfram-reply-sym
  (let [r (tc/parse-wolfram-reply "TC\tOK\t2\tSYM\t1 + x\tPlus[x, 1] = 1 + x")]
    (t/is (= 2 (:k r)))
    (t/is (= "SYM" (:kind r)))
    (t/is (= [(tc/expr "1 + x")] (:values r)))
    (t/is (= "Plus[x, 1] = 1 + x" (:info r)))))

;; Test 8: execute-op on expressions
(t/deftest sym-08-execute-op-expr
  (let [sent (atom [])
        state (assoc fresh :stack [(tc/expr "x") 1.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t2\tSYM\t1 + x\tPlus[x, 1] = 1 + x"))]
    (let [result-state (tc/execute-op state "+")]
      (t/is (= [(tc/expr "1 + x")] (:stack result-state)))
      (t/is (= "Plus[x, 1] = 1 + x" (:info result-state)))
      (t/is (= ["tcReply[tcApply[\"Plus\", {(x), 1}]]"] @sent)))))

;; Test 9: numbers stay native
(t/deftest sym-09-execute-op-native
  (let [state (assoc fresh :stack [2.0 3.0])]
    (t/is (= [5.0] (:stack (tc/execute-op state "+"))))))

;; Test 10: op names map to Wolfram names
(t/deftest sym-10-op-names
  (doseq [[op wl-name] [["-" "Subtract"] ["*" "Times"] ["/" "Divide"] ["^" "Power"]]
          :let [sent (atom [])
                state (assoc fresh :stack [(tc/expr "x") 2.0]
                             :wolfram (fn [line]
                                        (swap! sent conj line)
                                        "TC\tOK\t1\tSYM\tx^2\tPower[x, 2] = x^2"))
                _ (tc/execute-op state op)]]
    (t/is (some #(clojure.string/includes? % (str "tcApply[\"" wl-name "\"")) @sent)
          (str "Op " op " should call " wl-name))))

;; Test 11: CHS and INV on expressions
(t/deftest sym-11-chs-and-inv-expr
  (let [sent (atom [])
        state (assoc fresh :stack [(tc/expr "x")]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tSYM\t-x\tMinus[x] = -x"))]
    (let [result-state (tc/chs state)]
      (t/is (= [(tc/expr "-x")] (:stack result-state)))
      (t/is (= ["tcReply[tcApply[\"Minus\", {(x)}]]"] @sent))))
  (let [sent (atom [])
        state (assoc fresh :stack [(tc/expr "x")]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tSYM\t1/x\tDivide[1, x] = 1/x"))]
    (let [_ (tc/inv state)]
      (t/is (some #(clojure.string/includes? % "tcApply[\"Divide\", {1, (x)}]") @sent)))))

;; Test 12: no engine
(t/deftest sym-12-no-engine
  (let [state (assoc fresh :stack [(tc/expr "x") 1.0] :wolfram nil)]
    (let [result-state (tc/execute-op state "+")]
      (t/is (= [(tc/expr "x") 1.0] (:stack result-state)))
      (t/is (= "No Wolfram Engine (install wolframscript)" (:status result-state))))))

;; Test 13: native-only ops reject expressions
(t/deftest sym-13-native-only-reject-expr
  (let [state (assoc fresh :stack [(tc/expr "x") 1.0 2.0 3.0])
        [result-state _] (tc/linreg-action state)]
    (t/is (= "linreg needs numbers" (:status result-state)))
    (t/is (= [(tc/expr "x") 1.0 2.0 3.0] (:stack result-state)))
    (let [[s _] (tc/submit (typed state "map + 1"))]
      (t/is (= "map needs numbers" (:status s)))
      (t/is (= [(tc/expr "x") 1.0 2.0 3.0] (:stack s))))
    (let [[s _] (tc/submit (typed state "reduce +"))]
      (t/is (= "reduce needs numbers" (:status s)))
      (t/is (= [(tc/expr "x") 1.0 2.0 3.0] (:stack s))))))

;; Test 14: display and Wolfram call with mixed stack
(t/deftest sym-14-display-and-call-mixed
  (t/is (some #(clojure.string/includes? % "x^2") (tc/stack-lines [(tc/expr "x^2")])))
  (t/is (= "peek = x^2 + 1" (:info (tc/peek-top (assoc fresh :stack [(tc/expr "x^2 + 1")])))))
  (let [sent (atom [])
        state (assoc fresh :stack [(tc/expr "x") 2.0]
                     :wolfram (fn [line]
                                (swap! sent conj line)
                                "TC\tOK\t1\tNUM\t5\tTotal[{x, 2}] = 5"))]
    (let [[result-state _] (tc/submit (typed state "Total"))]
      (t/is (some? result-state))
      (t/is (some #(clojure.string/includes? % "tcCall[\"Total\", {(x), 2}]") @sent)))))

(t/deftest sym-16-op-key-commits-quoted-symbol
  ;; Stack [1], type 'x, press + : x is committed and Plus[1, x] sent
  ;; (HP style, like "12" then +). update-fn, so the undo entry too.
  (let [sent  (atom [])
        st    (typed (assoc fresh :stack [1.0]
                            :wolfram (fn [line]
                                       (swap! sent conj line)
                                       "TC\tOK\t2\tSYM\t1 + x\tPlus[1, x] = 1 + x"))
                     "'x")
        msg   {:type :key-press :key "+" :alt false :ctrl false :shift false}
        [s _] (tc/update-fn st msg)]
    (t/is (= [(tc/expr "1 + x")] (:stack s)))
    (t/is (= ["tcReply[tcApply[\"Plus\", {1, (x)}]]"] @sent))
    (t/is (= "" (tc/input-text s)))
    (t/is (= [[1.0]] (:undo s)))))

(t/deftest sym-17-plot-expression
  ;; expr 'x -5 3 Plot: the whole stack goes to tcCall (the engine
  ;; picks the range off it); a GFX reply opens the plot, stack kept.
  (let [sent  (atom [])
        stack [(tc/expr "(1 + x)^2") (tc/expr "x") -5.0 3.0]
        st    (typed (assoc fresh :stack stack
                            :wolfram (fn [line]
                                       (swap! sent conj line)
                                       "TC\tOK\t0\tGFX\t/var/tuiCalc-wolfram.svg\tPlot[(1 + x)^2, {x, -5, 3}]"))
                     "Plot")
        [s cmd] (tc/submit st)]
    (t/is (= ["tcReply[tcCall[\"Plot\", {((1 + x)^2), (x), -5, 3}]]"] @sent))
    (t/is (= stack (:stack s)))
    (t/is (= "Plot[(1 + x)^2, {x, -5, 3}]" (:info s)))
    (t/is (some? cmd))))

(t/deftest no-engine-row-search-and-notice
  ;; Without wolframscript, init shows a launch notice and the Wolfram
  ;; row becomes one unclickable label; search is off.
  (reset! tc/engine-missing true)
  (try
    (let [st (first (tc/init))]
      (t/is (= tc/no-engine-notice (:info st)))
      (t/is (true? (:no-engine st)))
      (t/is (= [[nil "Wolfram functions: not installed (see README)"]]
               (tc/menu-row st)))
      (t/is (= (tc/menu-row st) (tc/menu-row (typed st "Mea"))))
      (t/is (nil? (tc/search-prefix (typed st "Mea"))))
      ;; renders (nil-action label) and nothing on that row is clickable
      (t/is (clojure.string/includes? (tc/view st) "not installed"))
      (t/is (nil? (tc/find-button-at st 3 (+ tc/help-row0 4)))))
    (finally (reset! tc/engine-missing false)))
  ;; With the default (false) nothing changes.
  (t/is (nil? (:info (first (tc/init)))))
  (t/is (= "[Math ▸]  [Stats ▸]  [Lists ▸]  [Plots ▸]" (nth (tc/help-lines) 3))))

(t/deftest engine-failed-to-start-message
  (let [engine (future nil)]
    @engine
    (t/is (= "TC\tERR\tWolfram Engine failed to start (try: wolframscript -configure)"
             ((tc/wolfram-sender engine) "anything"))))
  ;; The engine's own reason is passed through (e.g. the license limit).
  (let [engine (future {:error "license error: single machine process limit reached."})]
    @engine
    (t/is (= "TC\tERR\tWolfram Engine failed to start: license error: single machine process limit reached."
             ((tc/wolfram-sender engine) "anything")))))

;; ---------------------------------------------------------------
;; Opening plots on macOS / Windows / Linux
;; ---------------------------------------------------------------

(t/deftest browser-command-per-os
  (t/is (= ["open" "/tmp/p.html"] (tc/browser-command "Mac OS X" "/tmp/p.html")))
  (t/is (= ["rundll32" "url.dll,FileProtocolHandler" "C:\\Temp\\p.html"]
           (tc/browser-command "Windows 11" "C:\\Temp\\p.html")))
  (t/is (= ["xdg-open" "/tmp/p.html"] (tc/browser-command "Linux" "/tmp/p.html"))))

(t/deftest wolfram-plot-html-file-url
  ;; A proper file:/// URL, not "file://" glued to a path.
  (t/is (clojure.string/includes? (tc/wolfram-plot-html "/tmp/x.svg" "i")
                                  "src=\"file:///tmp/x.svg\"")))

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

