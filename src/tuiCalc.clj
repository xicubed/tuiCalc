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
    ["peek" "[peek]"] ["count" "[count]"] ["reverse" "[reverse]"]
    ["get" "[GET]"]]
   [["q" "[q]"]]])

;; Faint legend under the buttons.
(def hint-line
  "^x drop ^s chs ^t peek ^n count ^r rev ^l clear ^p pi ^g get")

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

;; -------------------------------------------------------------
;; Button "flash" -- a rainbow sweep over ~2s so a screen-share
;; audience can see what was activated. A cmd sleeps flash-ms and
;; re-injects a :flash-tick message; each tick advances the hue and
;; schedules the next until flash-frames is exhausted.
;; -------------------------------------------------------------

(def flash-frames 40)
(def flash-ms 50)

;; h in degrees -> [r g b] 0-255 (full saturation).
;; x is a 1..0..1 triangle wave across each 60-degree sector.
(defn hue->rgb [h]
  (let [h (mod h 360.0)
        x (- 1.0 (Math/abs (- (mod (/ h 60.0) 2.0) 1.0)))
        [r g b] (cond
                  (< h 60)  [1 x 0]
                  (< h 120) [x 1 0]
                  (< h 180) [0 1 x]
                  (< h 240) [0 x 1]
                  (< h 300) [x 0 1]
                  :else     [1 0 x])]
    [(* 255 r) (* 255 g) (* 255 b)]))

(defn flash-color [frame]
  (let [[r g b] (hue->rgb (* (/ (double frame) flash-frames) 360.0))]
    (style/rgb (int r) (int g) (int b))))

(defn flash-cmd []
  (program/cmd
   (fn []
     (Thread/sleep flash-ms)
     {:type :flash-tick})))

(defn with-flash [state action]
  (assoc state :flash {:action action :frame 0}))

;; Insert a text prefix into the input box (for argument-taking
;; commands like "get " / "remove " that are finished by the user).
(defn insert-prefix [state text]
  (let [[input cmd] (text-input/text-input-update
                     (:input state)
                     (msg/key-press text))]
    [(assoc state :input input) cmd]))

;; Run a button action (shared by mouse clicks and keyboard shortcuts).
;; Returns [new-state command]. The caller adds the flash on top.
(defn run-action [state action]
  (case action
    "CHS"     [(chs state) nil]
    "pi"      [(push-pi state) nil]
    "clear"   [(assoc state :stack []) nil]
    "drop"    [(pop-top state) nil]
    "peek"    [(peek-top state) nil]
    "count"   [(count-stack state) nil]
    "reverse" [(reverse-stack state) nil]
    "get"     (insert-prefix state "get ")
    "remove"  (insert-prefix state "remove ")
    "q"       [state program/quit-cmd]
    "enter"   (submit state)
    [(execute-op state action) nil]))

;; Ctrl-key shortcuts (no ctrl conflicts with the text input's own
;; bindings -- it uses ctrl a b d e f h k u w). Values are actions, so
;; they share run-action with the buttons and flash identically.
(def shortcuts
  {"x" "drop"
   "s" "CHS"
   "t" "peek"
   "n" "count"
   "r" "reverse"
   "l" "clear"
   "p" "pi"
   "g" "get"})

;; Which button a typed command corresponds to (so pressing Enter
;; flashes the button that actually ran, not just [ENTER]). Nil for
;; tokens with no button (numbers, "get 0", unknown, ...).
(defn button-for-token [token]
  (condp = token
    "chs" "CHS"
    "pi" "pi"
    "clear" "clear"
    "q" "q" "quit" "q" "exit" "q"
    "drop" "drop" "pop" "drop"
    "peek" "peek"
    "count" "count"
    "reverse" "reverse"
    (when (and (string? token) (contains? ops token))
      ;; "x" is an ops alias for the "*" button.
      (if (= token "x") "*" token))))

;; The button that should flash for an activation. For "enter" (key
;; or click), the pending input decides: typing "chs" then Enter
;; flashes [CHS], a bare number flashes [ENTER]. Must be called with
;; the PRE-action state, since submit clears the input.
(defn flash-for-action [state action]
  (if (= action "enter")
    (or (let [head (str/lower-case
                    (first (str/split
                            (str/trim (text-input/value (:input state)))
                            #"\s+")))]
          (button-for-token head))
        "enter")
    action))

(defn handle-click [state x y]
  ;; Buttons are only shown when the help line isn't showing an error.
  (let [btn (when (not (:status state))
              (find-button-at x y))]
    (if btn
      (let [[s' c'] (run-action state btn)]
        [(with-flash s' (flash-for-action state btn))
         (or c' (flash-cmd))])
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

      ;; Advance (or end) an in-flight button flash.
      (= :flash-tick (:type message))
      (let [flash (:flash state)]
        (if (and flash (< (inc (:frame flash)) flash-frames))
          [(assoc state :flash (update flash :frame inc)) (flash-cmd)]
          [(dissoc state :flash) nil]))

      ;; Mouse clicks on help-line buttons.
      (and (msg/mouse? message)
           (= :press (:action message))
           (= :left (:button message)))
      (handle-click state (:x message) (:y message))

      ;; Ctrl-key shortcuts (drop, CHS, peek, ...).
      (and (msg/key-press? message)
           (:ctrl message)
           (string? key)
           (contains? shortcuts key))
      (let [action (shortcuts key)
            [s' c'] (run-action state action)]
        [(with-flash s' action) (or c' (flash-cmd))])

      ;; Operators execute immediately (keyboard shortcuts, except - for
      ;; negative numbers and x so words like "exit" can be typed).
      (and (msg/key-press? message)
           (not (:ctrl message))
           (contains? ops key)
           (not (#{"-" "x"} key)))
      (let [s' (execute-op state key)]
        [(with-flash s' key) (flash-cmd)])

      ;; Enter submits whatever is currently typed. Flash the button
      ;; that corresponds to what actually ran ([CHS] for "chs",
      ;; [ENTER] for a bare number).
      (msg/key-match? message :enter)
      (let [[s' c'] (submit state)]
        [(with-flash s' (flash-for-action state "enter"))
         (or c' (flash-cmd))])

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
   :fg (style/ansi256 240)))

(def error-style
  (style/style
   :fg style/red))

(def info-style
  (style/style
   :fg style/green))

(defn render-buttons-row [row flash]
  ;; Each button is rendered separately so the flashing one can get a
  ;; different color; joining the styled strings is safe (they balance).
  (str/join "  "
            (map (fn [[action display]]
                   (style/render
                    (if (= (:action flash) action)
                      (style/style
                       :fg (flash-color (:frame flash))
                       :bold true)
                      help-style)
                    display))
                 row)))

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

        ;; Help rows: the button rows (each button styled on its own so
        ;; the flashing one can change color); when an error is
        ;; showing, the error takes row 0 and the rest go blank.
        rows (if (:status state)
               (cons (style/render error-style (:status state))
                     (repeat (dec (count help-rows)) ""))
               (map #(render-buttons-row % (:flash state)) help-rows))

        help (str/join "\n" rows)

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
     "\n"

     ;; Keyboard-shortcut legend (always one line, never clickable).
     (style/render help-style hint-line)
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