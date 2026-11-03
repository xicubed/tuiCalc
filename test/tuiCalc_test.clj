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
  (t/is (= "1.0*^10" (tc/wl-num 1.0E10)))
  (t/is (= "1.0*^-7" (tc/wl-num 1.0E-7)))
  (t/is (= "-3.0" (tc/wl-num -3.0)))
  (t/is (= "3.0" (tc/wl-num 3))))

(t/deftest wolfram-call-line
  (t/is (= "tcReply[tcCall[\"Total\", {1.0, 2.0}]]"
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
  ;; SYM reply
  (let [r (tc/parse-wolfram-reply "TC\tOK\t0\tSYM\t0. + 2.*I\tSqrt[-4] = 0. + 2.*I")]
    (t/is (= 0 (:k r)))
    (t/is (= "SYM" (:kind r)))
    (t/is (= [] (:values r)))
    (t/is (= "Sqrt[-4] = 0. + 2.*I" (:info r))))
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
    (t/is (= ["tcReply[tcCall[\"Total\", {1.0, 2.0, 3.0, 4.0}]]"] @sent))))

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
  ;; SYM reply: stack unchanged, info set
  (let [state (assoc (first (tc/init)) :stack [5.0]
                     :wolfram (fn [_] "TC\tOK\t0\tSYM\t0. + 2.*I\tSqrt[-4] = 0. + 2.*I"))
        state (typed state "Sqrt")
        [result-state _] (tc/submit state)]
    (t/is (= [5.0] (:stack result-state)))
    (t/is (= "Sqrt[-4] = 0. + 2.*I" (:info result-state))))
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
    (t/is (some #(clojure.string/includes? % "{9.0}") @sent))
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

(t/deftest wolfram-button-and-help
  (t/is (= "Sqrt" (tc/button-for-token "sqrt")))
  (t/is (= "Total" (tc/button-for-token "total")))
  ;; 4th help row contains all 8 labels
  (let [row (nth (tc/help-lines) 3)]  ; 0-indexed, so row 3 = 4th row
    (t/is (clojure.string/includes? row "[Sqrt]"))
    (t/is (clojure.string/includes? row "[Mod]"))
    (t/is (clojure.string/includes? row "[Total]"))
    (t/is (clojure.string/includes? row "[Mean]"))
    (t/is (clojure.string/includes? row "[Sort]"))
    (t/is (clojure.string/includes? row "[Max]"))
    (t/is (clojure.string/includes? row "[Min]"))
    (t/is (clojure.string/includes? row "[ListPlot]"))))

(t/deftest click-wolfram-total-button
  (let [line (nth (tc/help-lines) 3)  ; 4th row
        start (.indexOf line "[Total]")
        y (+ tc/help-row0 4)]
    (t/is (<= 0 start))
    (t/is (= "Total" (tc/find-button-at (inc start) y)))
    (t/is (= "Total" (tc/find-button-at (+ start 6) y)))
    (t/is (not= "Total" (tc/find-button-at (+ start 8) y)))))

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
