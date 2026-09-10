#!/usr/bin/env bb

(ns tuiCalc
  (:require
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as program]
   [charm.style.core :as style]
   [clojure.string :as str]))

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


;; ------------------------------------------------------------
;; Charm update
;; ------------------------------------------------------------

(defn update-fn [state message]
  (cond

    ;; Always provide a direct escape hatch.
    (msg/key-match? message "ctrl+c")
    [state program/quit-cmd]

    ;; Enter submits whatever is in the input field.
    (msg/key-match? message "enter")
    (submit state)

    ;; Everything else goes to Charm's text editor.
    :else
    (let [[input cmd]
          (text-input/text-input-update
           (:input state)
           message)]

      [(assoc state
              :input input
              :status nil)
       cmd])))


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
        "+  -  *  /    pi    clear    q"))

     "\n")))


;; ------------------------------------------------------------
;; Go
;; ------------------------------------------------------------

(defn -main [& _]
  (program/run
   {:init init
    :update update-fn
    :view view
    :alt-screen true}))

(apply -main *command-line-args*)