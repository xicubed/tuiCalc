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

;; ------------------------------------------------------------
;; Process one entered token
;; ------------------------------------------------------------

(defn submit [state]
  (let [token (str/trim
               (text-input/value (:input state)))

        ;; Clear the input after Enter.
        state (assoc state
                     :input (text-input/reset (:input state))
                     :status nil)]

    (cond

      ;; Empty Enter -- do nothing.
      (str/blank? token)
      [state nil]

      ;; Quit commands.
      (#{"q" "quit" "exit"} token)
      [state program/quit-cmd]

      ;; Clear the stack.
      (= token "clear")
      [(assoc state :stack []) nil]

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
                :status (str "Unknown: " token))
         nil]))))

;; Help-line buttons: [action display] pairs.
;; Rendered into the help line and used for click hit-testing,
;; so labels and positions can never drift apart.
(def help-buttons
  [["+" "[+]"]
   ["-" "[-]"]
   ["*" "[*]"]
   ["/" "[/]"]
   ["CHS" "[CHS]"]
   ["pi" "[pi]"]
   ["clear" "[clear]"]
   ["q" "[q]"]
   ["enter" "[ENTER]"]])

(defn help-line []
  (str/join "  " (map second help-buttons)))

;; Click hit-test: is x inside a button's range on the help line?
(defn find-button-at [x]
  (let [line (help-line)]
    (some
     (fn [[action display]]
       (let [start (.indexOf line display)]
         (when (and (>= x start)
                    (< x (+ start (count display))))
           action)))
     help-buttons)))

;; Change sign of top stack value
(defn chs [state]
  (if (empty? (:stack state))
    (assoc state :status "Stack empty")
    (let [top    (peek (:stack state))
          stack  (pop (:stack state))]
      (assoc state :stack (conj stack (* -1 top))))))

(defn handle-click [state x y]
  ;; Help text position: title(1) + stack-lines(stack-height) + border(1) + blank(1) + input(2) + blank(2) = ~12 + stack-height
  ;; Use a range that covers the help line area
  (let [help-y (+ 1 stack-height 5)]  ;; Dynamic calculation based on stack height
    (if (<= (- help-y 1) y (+ help-y 2))
      (if-let [btn (find-button-at x)]
        (case btn
          "CHS"   [(chs state) nil]
          "pi"    [(push-pi state) nil]
          "clear" [(assoc state :stack []) nil]
          "q"     [state program/quit-cmd]
          "enter" (submit state)
          [(execute-op state btn) nil])
        [state nil])
      [state nil])))  ;; Click outside button area, ignore

;; ------------------------------------------------------------
;; Charm state
;; ------------------------------------------------------------

(defn init []
  [{:stack  []
    :input  (text-input/text-input
             :prompt "> "
             :placeholder "number, pi, +, -, *, /"
             :placeholder-style (style/style :faint true)
             :focused true
             :width 30)
    :status nil}
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
  (let [stack (:stack state)]

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

     "\n\n"

     (if-let [status (:status state)]
       (style/render error-style status)
       (style/render
        help-style
        (help-line)))

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