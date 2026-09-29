#!/usr/bin/env bb

(ns tuiCalc
  (:require
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as program]
   [charm.style.core :as style]
   [clojure.string :as str]))

;; NOTE (babashka): defn bodies are analyzed at load time, so each
;; function must appear *after* the things it calls. Keep this order.

(def stack-height 8)

;; ------------------------------------------------------------
;; Calculator engine
;; ------------------------------------------------------------

(defn ieee-div [a b]
  (cond
    (and (zero? a) (zero? b))
    Double/NaN

    (zero? b)
    (if (neg? a)
      Double/NEGATIVE_INFINITY
      Double/POSITIVE_INFINITY)

    :else
    (/ a b)))

(def ops
  {"+" +
   "-" -
   "x" *
   "*" *
   "/" ieee-div})

(defn parse-number [s]
  (case (str/lower-case s)
    "pi" Math/PI
    (try
      (parse-double s)
      (catch Exception _
        nil))))

(defn parse-integer [s]
  (when (and (string? s) (re-matches #"^-?\d+$" s))
    (Integer/parseInt s)))

(defn apply-op [stack op]
  (if (< (count stack) 2)

    ;; Don't alter the stack on underflow.
    [stack "Need two values"]

    (let [b     (peek stack)
          stack (pop stack)
          a     (peek stack)
          stack (pop stack)]
      [(conj stack (op a b)) nil])))

;; ok getting fancy
;; instead of 4 [Enter] 5 [Enter] + [Enter]
;; we want    4 [Enter] 5 + ==> 9.0
(defn commit-input [state]
  (let [token (str/trim (text-input/value (:input state)))]
    (if-let [n (and (not (str/blank? token))
                    (parse-number token))]
      (-> state
          (update :stack conj n)
          (assoc :input (text-input/reset (:input state))))
      state)))
(defn execute-op [state token]
  (let [state (commit-input state)
        [stack status] (apply-op (:stack state) (ops token))]
    (assoc state
           :stack stack
           :status status)))

;; Push pi onto the stack (committing any pending input first).
(defn push-pi [state]
  (update (commit-input state) :stack conj Math/PI))

;; Change sign of the top stack value.
(defn chs [state]
  (if (empty? (:stack state))
    (assoc state :status "Stack empty")
    (let [top   (peek (:stack state))
          stack (pop (:stack state))]
      (assoc state :stack (conj stack (* -1 top))))))

;; -------------------------------------------------------------
;; Stack manipulation. The stack is a plain Clojure vector (top =
;; last element); these are Clojure collection ops run on it.
;; Indexed ops take an HP level: 0 = top (X), 1 = below (Y), ...;
;; negative levels count up from the bottom. level->index maps a
;; level to a vector index (nil when out of range).
;; -------------------------------------------------------------

(defn level->index [stack level]
  (when (integer? level)
    (let [n    (count stack)
          vidx (if (neg? level)
                 (- (abs level) 1)   ; -1 = bottom, -2 = second up, ...
                 (- n 1 level))]     ;  0 = top, 1 = below it, ...
      (when (and (>= vidx 0) (< vidx n))
        vidx))))

;; Remove the top value (Clojure pop; HP CLx). `drop` is an alias.
(defn pop-top [state]
  (if (empty? (:stack state))
    (assoc state :status "Stack empty")
    (assoc state :stack (pop (:stack state)))))

;; Show the top value without touching the stack (Clojure peek/last).
;; Sets :info (not :status) so the buttons stay visible while the
;; result lingers.
(defn peek-top [state]
  (if (empty? (:stack state))
    (assoc state :status "Stack empty")
    (assoc state :info (str "peek = " (peek (:stack state))))))

;; Show the stack depth (Clojure count).
(defn count-stack [state]
  (assoc state :info (str "count = " (count (:stack state)))))

;; Reverse the stack order (Clojure reverse).
(defn reverse-stack [state]
  (assoc state :stack (vec (reverse (:stack state)))))

;; Push a copy of the value at level i (Clojure get/nth, HP levels).
;; get 0 = dup, get 1 = roll, get -1 = bottom.
(defn get-index [state i]
  (let [stack (:stack state)]
    (if-let [vi (level->index stack i)]
      (update state :stack conj (nth stack vi))
      (assoc state :status (str "No value at level " i)))))

;; Remove the element at index vi from a vector.
(defn remove-at [s vi]
  (vec (concat (subvec s 0 vi) (subvec s (inc vi)))))

;; Remove the value at level i (HP levels).
(defn remove-index [state i]
  (let [stack (:stack state)]
    (if-let [vi (level->index stack i)]
      (assoc state :stack (remove-at stack vi))
      (assoc state :status (str "No value at level " i)))))

;; ------------------------------------------------------------
;; Process one entered token
;; ------------------------------------------------------------

(defn submit [state]
  (let [raw   (str/trim (text-input/value (:input state)))

        ;; Split into the command word and any argument.
        [head & rest] (str/split raw #"\s+")
        token        (str/lower-case head)

        ;; Clear the input after Enter, plus any stale status/info.
        state (assoc state
                     :input (text-input/reset (:input state))
                     :status nil
                     :info nil)]

    (cond

      ;; Empty Enter -- do nothing.
      (str/blank? raw)
      [state nil]

      ;; Quit commands.
      (#{"q" "quit" "exit"} token)
      [state program/quit-cmd]

      ;; Clear the whole stack.
      (= token "clear")
      [(assoc state :stack []) nil]

      ;; Change sign of the top value.
      (= token "chs")
      [(chs state) nil]

      ;; Pop the top value (drop is an alias).
      (#{"drop" "pop"} token)
      [(pop-top state) nil]

      ;; Peek the top value.
      (= token "peek")
      [(peek-top state) nil]

      ;; Show the stack depth.
      (= token "count")
      [(count-stack state) nil]

      ;; Reverse the stack.
      (= token "reverse")
      [(reverse-stack state) nil]

      ;; Get the value at level <i> (dup/roll).
      (#{"get" "nth"} token)
      (let [i (parse-integer (first rest))]
        (if i
          [(get-index state i) nil]
          [(assoc state :status (str "Need a level after " token)) nil]))

      ;; Remove the value at level <i>.
      (= token "remove")
      (let [i (parse-integer (first rest))]
        (if i
          [(remove-index state i) nil]
          [(assoc state :status "Need a level after remove") nil]))

      ;; Arithmetic operator.
      (contains? ops token)
      (let [[stack status]
            (apply-op (:stack state)
                      (ops token))]
        [(assoc state
                :stack stack
                :status status)
         nil])

      ;; Otherwise try to push a number.
      :else
      (if-let [n (parse-number token)]
        [(update state :stack conj n) nil]
        [(assoc state
                :status (str "Unknown: " raw))
         nil]))))

;; Help buttons, grouped into rows. Each button is [action display];
;; the rows render as the help lines and drive click hit-testing, so
;; labels and positions can never drift apart.
(def help-rows
  [[["+" "[+]"] ["-" "[-]"] ["*" "[*]"] ["/" "[/]"] ["CHS" "[CHS]"] ["pi" "[pi]"]]
   [["enter" "[ENTER]"] ["clear" "[CLEAR]"] ["drop" "[drop]"]
    ["peek" "[peek]"] ["count" "[count]"] ["reverse" "[reverse]"]]
   [["q" "[q]"]]])

(defn help-lines []
  (map #(str/join "  " (map second %)) help-rows))

;; Terminal row (0-based) of the top help line.
;; Layout: title(1) + stack(stack-height) + border(1) + blank(1) +
;; input(1) + info(1) = stack-height + 5 rows before the first help
;; line. The info line is always rendered (blank when empty) so this
;; stays fixed. (See the render-position test -- a miscount here
;; breaks every click silently.)
(def help-row0 (+ stack-height 5))

;; Which button (if any) sits at column x on help row y?
;; charm passes the terminal's raw 1-based mouse coords (top row is y=1,
;; leftmost column is x=1); help-row0 and indexOf are 0-based, so
;; normalize here.
(defn find-button-at [x y]
  (let [x       (dec x)
        y       (dec y)
        row-idx (- y help-row0)]
    (when (and (>= row-idx 0) (< row-idx (count help-rows)))
      (let [row  (nth help-rows row-idx)
            line (str/join "  " (map second row))]
        (some
         (fn [[action display]]
           (let [start (.indexOf line display)]
             (when (and (>= x start)
                        (< x (+ start (count display))))
               action)))
         row)))))

(defn handle-click [state x y]
  ;; Buttons are only shown when the help line isn't showing an error.
  (let [btn (when (not (:status state))
              (find-button-at x y))]
    (if-let [btn btn]
      (case btn
        "CHS"     [(chs state) nil]
        "pi"      [(push-pi state) nil]
        "clear"   [(assoc state :stack []) nil]
        "drop"    [(pop-top state) nil]
        "peek"    [(peek-top state) nil]
        "count"   [(count-stack state) nil]
        "reverse" [(reverse-stack state) nil]
        "q"       [state program/quit-cmd]
        "enter"   (submit state)
        [(execute-op state btn) nil])
      [state nil])))

;; ------------------------------------------------------------
;; Charm state
;; ------------------------------------------------------------

(defn init []
  [{:stack  []
    :input  (text-input/text-input
             :prompt "> "
             :placeholder "number, pi, chs, drop, peek, get 0, +, -, *"
             :placeholder-style (style/style :faint true)
             :focused true
             :width 30)
    :status nil
    :info   nil}
   nil])


;; ------------------------------------------------------------
;; Charm update
;; ------------------------------------------------------------

(defn update-fn [state message]
  (let [key (:key message)]

    (cond
      ;; Quit
      (msg/key-match? message "ctrl+c")
      [state program/quit-cmd]

      ;; Mouse clicks on help-line buttons.
      (and (msg/mouse? message)
           (= :press (:action message))
           (= :left (:button message)))
      (handle-click state (:x message) (:y message))

      ;; Operators execute immediately (keyboard shortcuts, except - for
      ;; negative numbers and x so words like "exit" can be typed).
      (and (msg/key-press? message)
           (contains? ops key)
           (not (#{"-" "x"} key)))
      [(execute-op state key) nil]

      ;; Enter submits whatever is currently typed.
      (msg/key-match? message :enter)
      (submit state)

      ;; Everything else goes into the text field.
      :else
      (let [[input cmd]
            (text-input/text-input-update
             (:input state)
             message)]
        [(assoc state
                :input input
                :status nil)
         cmd]))))

;; ------------------------------------------------------------
;; View
;; ------------------------------------------------------------

(def title-style
  (style/style
   :fg style/cyan
   :bold true))

(def help-style
  (style/style
   :fg 240))

(def error-style
  (style/style
   :fg style/red))

(def info-style
  (style/style
   :fg style/green))

(defn stack-lines [stack]
  (let [visible (vec (take-last stack-height stack))
        blanks  (- stack-height
                   (count visible))]

    (concat

     ;; Empty rows are ABOVE the values.
     ;; This makes the stack grow upward.
     (repeat blanks
             "│                              │")

     ;; Oldest visible value first,
     ;; newest / X register at bottom.
     (map
      #(format "│ %28s │" (str %))
      visible))))

(defn view [state]
  (let [stack (:stack state)

        ;; Help rows to display: normally the three button rows; when an
        ;; error is showing, the error takes row 0 and the rest go blank.
        rows (if-let [status (:status state)]
               (cons (style/render error-style status)
                     (repeat 2 ""))
               (help-lines))

        help (str/join "\n"
                       (map (fn [ln]
                              (if (empty? ln)
                                ln
                                (style/render help-style ln)))
                            rows))

        ;; Probe result (peek/count), always one line so the help rows
        ;; -- and their click positions -- never move.
        info-line (if-let [info (:info state)]
                    (style/render info-style info)
                    "")]

    (str

     (style/render
      title-style
      "┌─────────── tuiCalc ──────────┐")

     "\n"

     (str/join "\n"
               (stack-lines stack))

     "\n"
     "└──────────────────────────────┘"
     "\n\n"

     ;; Charm owns the editing/cursor here.
     (text-input/text-input-view
      (:input state))

     ;; Reserved probe line (blank unless a probe is showing).
     "\n"
     info-line
     "\n"

     help
     "\n")))


;; ------------------------------------------------------------
;; Go
;; ------------------------------------------------------------

(defn -main [& _]
  (program/run
   {:init init
    :update update-fn
    :view view
    :alt-screen true
    :mouse :normal}))  ; Enable mouse clicks

(apply -main *command-line-args*)