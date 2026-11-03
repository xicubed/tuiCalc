#!/usr/bin/env bb

(ns tuiCalc
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as program]
   [charm.render.core :as render]
   [charm.style.core :as style]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;; NOTE (babashka): defn bodies are analyzed at load time, so each
;; function must appear *after* the things it calls. Keep this order.

(def stack-height 8)

;; Normal input-box prompt/placeholder; the two-step level prompt
;; swaps both out.
(def input-prompt "> ")
(def input-placeholder "number, pi, chs, inv, drop, peek, get 0, +, -, *")
(def level-placeholder "level: 0=X, 1=Y, -1=T")

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
   "/" ieee-div
   "^" Math/pow
   "pow" Math/pow})

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

;; Invert the top stack value (1/x). Uses ieee-div so 1/0 behaves
;; like division (Infinity or NaN).
(defn inv [state]
  (if (empty? (:stack state))
    (assoc state :status "Stack empty")
    (let [top   (peek (:stack state))
          stack (pop (:stack state))]
      (assoc state :stack (conj stack (ieee-div 1.0 top))))))

;; Swap X and Y (the last two elements). HP-15c x<>y.
(defn swap-xy [state]
  (let [stack (:stack state)]
    (if (< (count stack) 2)
      (assoc state :status "Need two values")
      (let [x   (peek stack)
            y   (peek (pop stack))
            stack (pop (pop stack))]
        (assoc state :stack (conj (conj stack x) y))))))

;; Maximum undo history size.
(def undo-limit 100)

;; Undo: restore the previous stack.
(defn undo [state]
  (if (empty? (:undo state))
    (assoc state :status "Nothing to undo")
    (let [undo-stack (:undo state)
          prev-stack (peek undo-stack)
          new-undo (pop undo-stack)]
      (assoc state
             :stack prev-stack
             :undo new-undo
             :info "undo"))))

;; -------------------------------------------------------------
;; Stack manipulation. The stack is a plain Clojure vector held in
;; push order: the oldest value is first, the newest (the stack-top,
;; HP's X register) is LAST. "top of the stack" below always means X /
;; the newest — NOT the top row of the drawn box (that's the oldest).
;;
;; Indexed ops take a *level* = offset from X (the value you're working
;; with), not a vector index:
;;   0 = X (newest), 1 = Y, 2 = Z, ... going deeper
;;   -1 = T (the first-in / oldest), -2 = one older, ... going up
;; It's a FILO stack: the first value in is at level -1. level->index
;; maps a level to a vector index (nil when out of range).
;; -------------------------------------------------------------

(defn level->index [stack level]
  (when (integer? level)
    (let [n    (count stack)
          vidx (if (neg? level)
                 (- (abs level) 1)   ; -1 = T (oldest), -2 = next, ...
                 (- n 1 level))]     ;  0 = X (newest), 1 = Y, ...
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

;; Push a copy of the value at level i (Clojure get/nth). Levels are
;; offset from X: 0 = X (dup), 1 = Y, -1 = T (first-in / oldest).
(defn get-index [state i]
  (let [stack (:stack state)]
    (if-let [vi (level->index stack i)]
      (update state :stack conj (nth stack vi))
      (assoc state :status (str "No value at level " i)))))

;; Remove the element at index vi from a vector.
(defn remove-at [s vi]
  (vec (concat (subvec s 0 vi) (subvec s (inc vi)))))

;; Remove the value at level i (offset from X, see level->index).
(defn remove-index [state i]
  (let [stack (:stack state)]
    (if-let [vi (level->index stack i)]
      (assoc state :stack (remove-at stack vi))
      (assoc state :status (str "No value at level " i)))))

;; Start a two-step level prompt (get?/remove?): the input box
;; becomes "get? " / "remove? " and the next integer is the level.
(defn start-level-prompt [state pending prompt-str]
  (assoc state
         :pending pending
         :input (-> (:input state)
                    text-input/reset
                    (assoc :prompt prompt-str)
                    (assoc :placeholder level-placeholder))))

;; Cancel a two-step level prompt (get?/remove?) if one is active:
;; drop the pending flag, restore the normal prompt/placeholder, and
;; discard the half-typed level so it can't leak into the next command.
(defn abort-prompt [state]
  (-> state
      (dissoc :pending)
      (update :input (fn [inp]
                       (-> inp
                           text-input/reset
                           (assoc :prompt input-prompt)
                           (assoc :placeholder input-placeholder))))))

;; Unary ops for map (no argument allowed).
(def unary-ops
  {"chs" (fn [x] (double (* -1 x)))
   "inv" (fn [x] (double (ieee-div 1.0 x)))})

;; Build a binary function from an op token and an argument value.
;; Element is the LEFT operand: f elem arg.
(defn make-bin-fn [token arg]
  (case token
    "+" (fn [e] (+ e arg))
    "-" (fn [e] (- e arg))
    "x" (fn [e] (* e arg))
    "*" (fn [e] (* e arg))
    "/" (fn [e] (ieee-div e arg))
    "^" (fn [e] (Math/pow e arg))
    "pow" (fn [e] (Math/pow e arg))))

;; Display name for an op in the :info string.
(defn op-display-name [token]
  (case token
    "x" "*"
    "^" "Math/pow"
    "pow" "Math/pow"
    token))

;; Format a number for display in info strings: show as int if whole.
(defn format-num-for-info [n]
  (if (and (number? n) (integer? n))
    (str n)
    (if (and (number? n) (== n (long n)))
      (str (long n))
      (str n))))

;; Format with 6 significant figures for display.
(defn sig6 [v]
  (format-num-for-info (Double/parseDouble (format "%.6g" (double v)))))

;; Compute a nice grid step for a given span and target tick count.
;; Uses 1-2-5-10 progression.
(defn nice-step [span target-ticks]
  (let [raw (/ span target-ticks)
        mag (Math/pow 10 (Math/floor (Math/log10 raw)))
        norm (/ raw mag)
        step (cond
               (<= norm 1) 1
               (<= norm 2) 2
               (<= norm 5) 5
               :else 10)]
    (* mag step)))

;; Format tick labels cleanly (round to 9 decimals to avoid 0.15000000000000002).
(defn tick-label [v]
  (format-num-for-info (/ (Math/round (* v 1e9)) 1e9)))

;; Compute plot geometry with equal-scale square grid.
;; Returns {:xmin :xmax :ymin :ymax :step :scale :x0 :y0 :w :h}.
(defn plot-geometry [points m b]
  (let [xs (map first points)
        ys (map second points)
        xmin (apply min xs) xmax (apply max xs)
        ymin (apply min ys) ymax (apply max ys)
        ;; Include line endpoints in y range
        y-at-xmin (+ (* m xmin) b)
        y-at-xmax (+ (* m xmax) b)
        ymin (min ymin y-at-xmin y-at-xmax)
        ymax (max ymax y-at-xmin y-at-xmax)
        ;; Widen zero spans first
        xmin (if (zero? (- xmax xmin)) (dec xmin) xmin)
        xmax (if (zero? (- xmax xmin)) (inc xmax) xmax)
        ymin (if (zero? (- ymax ymin)) (dec ymin) ymin)
        ymax (if (zero? (- ymax ymin)) (inc ymax) ymax)
        ;; Always include origin
        xmin (min xmin 0) xmax (max xmax 0)
        ymin (min ymin 0) ymax (max ymax 0)
        ;; Recompute spans after near-origin adjustment
        x-span (- xmax xmin) y-span (- ymax ymin)
        ;; One shared step from the larger span
        shared-step (nice-step (max x-span y-span) 10)
        ;; Integer multiples for snapping
        kx0 (long (Math/floor (/ xmin shared-step)))
        kx1 (long (Math/ceil (/ xmax shared-step)))
        ky0 (long (Math/floor (/ ymin shared-step)))
        ky1 (long (Math/ceil (/ ymax shared-step)))
        ;; Snap to whole multiples
        xmin (double (* kx0 shared-step))
        xmax (double (* kx1 shared-step))
        ymin (double (* ky0 shared-step))
        ymax (double (* ky1 shared-step))]
    ;; Recompute spans after snapping
    (let [x-span (- xmax xmin) y-span (- ymax ymin)
          ;; Square box: 760 x 760 at x 130..890, y 50..810
          box-w 760 box-h 760
          ;; One shared scale (pixels per unit)
          scale (min (/ box-w x-span) (/ box-h y-span))
          w (* scale x-span) h (* scale y-span)
          ;; Center in box
          x0 (+ 130 (/ (- box-w w) 2))
          y0 (+ 50 (/ (- box-h h) 2))]
      {:xmin xmin :xmax xmax :ymin ymin :ymax ymax
       :step shared-step :scale scale :x0 x0 :y0 y0 :w w :h h})))

;; Generate HTML page with SVG blueprint-style plot.
;; Two arities: [points m b date] and [points m b] (uses today's date).
(defn plot-html
  ([points m b]
   (plot-html points m b (str (java.time.LocalDate/now))))
  ([points m b date]
   (let [geom (plot-geometry points m b)
         xmin (:xmin geom) xmax (:xmax geom)
         ymin (:ymin geom) ymax (:ymax geom)
         step (:step geom) scale (:scale geom)
         x0 (:x0 geom) y0 (:y0 geom) w (:w geom) h (:h geom)
         ;; Page dimensions
         width 960 height 1000
         n (count points)
         ;; Integer multiples for majors
         kx0 (long (Math/floor (/ xmin step)))
         kx1 (long (Math/ceil (/ xmax step)))
         ky0 (long (Math/floor (/ ymin step)))
         ky1 (long (Math/ceil (/ ymax step)))
         x-majors (map #(* (double %) step) (range kx0 (inc kx1)))
         y-majors (map #(* (double %) step) (range ky0 (inc ky1)))
         ;; Scale functions using geometry
         px (fn [x] (+ x0 (* (- x xmin) scale)))
         py (fn [y] (- (+ y0 h) (* (- y ymin) scale)))
         ;; Minor grid: for each major k, j=1..4 -> (* (+ k j/5) step)
         x-minors (mapcat (fn [k] (map #(* (+ k (/ % 5.0)) step) (range 1 5))) (range kx0 kx1))
         y-minors (mapcat (fn [k] (map #(* (+ k (/ % 5.0)) step) (range 1 5))) (range ky0 ky1))
         grid-lines (str/join "\n"
                              (concat
                               (map (fn [x] (str "<line x1=\"" (px x) "\" y1=\"" y0 "\" x2=\"" (px x) "\" y2=\"" (+ y0 h) "\" stroke=\"#7fa6d6\" stroke-width=\"1\" opacity=\"0.5\"/>"))
                                    x-minors)
                               (map (fn [y] (str "<line x1=\"" x0 "\" y1=\"" (py y) "\" x2=\"" (+ x0 w) "\" y2=\"" (py y) "\" stroke=\"#7fa6d6\" stroke-width=\"1\" opacity=\"0.5\"/>"))
                                    y-minors)
                               (map (fn [x] (str "<line x1=\"" (px x) "\" y1=\"" y0 "\" x2=\"" (px x) "\" y2=\"" (+ y0 h) "\" stroke=\"#7fa6d6\" stroke-width=\"1.5\"/>"))
                                    x-majors)
                               (map (fn [y] (str "<line x1=\"" x0 "\" y1=\"" (py y) "\" x2=\"" (+ x0 w) "\" y2=\"" (py y) "\" stroke=\"#7fa6d6\" stroke-width=\"1.5\"/>"))
                                    y-majors)))
         ;; Origin axes (if 0 is within range)
         origin-axes (str
                      (if (and (<= xmin 0) (>= xmax 0))
                        (str "<line x1=\"" (px 0) "\" y1=\"" y0 "\" x2=\"" (px 0) "\" y2=\"" (+ y0 h) "\" stroke=\"#1f4e9a\" stroke-width=\"2\"/>\n")
                        "")
                      (if (and (<= ymin 0) (>= ymax 0))
                        (str "<line x1=\"" x0 "\" y1=\"" (py 0) "\" x2=\"" (+ x0 w) "\" y2=\"" (py 0) "\" stroke=\"#1f4e9a\" stroke-width=\"2\"/>\n")
                        ""))
         x-labels (str/join "\n"
                            (map (fn [x] (str "<text x=\"" (px x) "\" y=\"" (+ y0 h 20) "\" fill=\"#1f4e9a\" font-size=\"12\" text-anchor=\"middle\">" (tick-label x) "</text>"))
                                 x-majors))
         y-labels (str/join "\n"
                            (map (fn [y] (str "<text x=\"" (- x0 10) "\" y=\"" (+ (py y) 4) "\" fill=\"#1f4e9a\" font-size=\"12\" text-anchor=\"end\">" (tick-label y) "</text>"))
                                 y-majors))
         circles (str/join "\n"
                           (map (fn [[x y]]
                                  (str "<circle cx=\"" (px x) "\" cy=\"" (py y) "\" r=\"7\" fill=\"#1f4e9a\" stroke=\"#f4f1e6\" stroke-width=\"2\"><title>" (tick-label x) ", " (tick-label y) "</title></circle>"))
                                points))
         line-ymin (py (+ (* m xmin) b))
         line-ymax (py (+ (* m xmax) b))
         m-sig (sig6 m)
         b-sig (sig6 (if (neg? b) (- b) b))
         eq-line1 (str "y = " m-sig "x")
         eq-line2 (if (neg? b)
                    (str "− " b-sig)
                    (str "+ " b-sig))]
     (str
      "<!doctype html>\n"
      "<html>\n"
      "<head>\n"
      "<title>tuiCalc linreg</title>\n"
      "<link href=\"https://fonts.googleapis.com/css2?family=Architects+Daughter&display=swap\" rel=\"stylesheet\">\n"
      "<style>\n"
      "circle:hover { r: 10; stroke: #f4f1e6; stroke-width: 3; }\n"
      "</style>\n"
      "</head>\n"
      "<body style=\"background:#f4f1e6;margin:0;padding:20px;font-family:'Architects Daughter','Courier New',monospace;\">\n"
      "<svg viewBox=\"0 0 960 1000\" style=\"display:block;margin:0 auto;height:calc(100vh - 40px);width:auto;max-width:100%;\">\n"
      "<!-- heavy border -->\n"
      "<rect x=\"15\" y=\"15\" width=\"930\" height=\"970\" fill=\"none\" stroke=\"#1f4e9a\" stroke-width=\"5\"/>\n"
      "<!-- clip path for fit line -->\n"
      "<defs><clipPath id=\"plot-area\"><rect x=\"" x0 "\" y=\"" y0 "\" width=\"" w "\" height=\"" h "\"/></clipPath></defs>\n"
      "<!-- grid -->\n"
      grid-lines "\n"
      "<!-- origin axes -->\n"
      origin-axes "\n"
      "<!-- axis titles -->\n"
      "<text x=\"" (+ x0 (/ w 2)) "\" y=\"" (+ y0 h 40) "\" fill=\"#1f4e9a\" font-size=\"18\" text-anchor=\"middle\">X</text>\n"
      "<text x=\"" (- x0 50) "\" y=\"" (+ y0 (/ h 2)) "\" fill=\"#1f4e9a\" font-size=\"18\" text-anchor=\"middle\" transform=\"rotate(-90 " (- x0 50) " " (+ y0 (/ h 2)) ")\">Y</text>\n"
      "<!-- x labels -->\n"
      x-labels "\n"
      "<!-- y labels -->\n"
      y-labels "\n"
      "<!-- fit line -->\n"
      "<line x1=\"" (px xmin) "\" y1=\"" line-ymin "\" x2=\"" (px xmax) "\" y2=\"" line-ymax "\" stroke=\"#1f4e9a\" stroke-width=\"3\" clip-path=\"url(#plot-area)\"/>\n"
      "<!-- border around plot area -->\n"
      "<rect x=\"" x0 "\" y=\"" y0 "\" width=\"" w "\" height=\"" h "\" fill=\"none\" stroke=\"#7fa6d6\" stroke-width=\"1\"/>\n"
      "<!-- points -->\n"
      circles "\n"
      "<!-- title block below plot -->\n"
      "<rect x=\"100\" y=\"880\" width=\"820\" height=\"90\" fill=\"none\" stroke=\"#1f4e9a\" stroke-width=\"2\"/>\n"
      "<line x1=\"350\" y1=\"880\" x2=\"350\" y2=\"970\" stroke=\"#1f4e9a\" stroke-width=\"1\"/>\n"
      "<line x1=\"520\" y1=\"880\" x2=\"520\" y2=\"970\" stroke=\"#1f4e9a\" stroke-width=\"1\"/>\n"
      "<line x1=\"590\" y1=\"880\" x2=\"590\" y2=\"970\" stroke=\"#1f4e9a\" stroke-width=\"1\"/>\n"
      "<line x1=\"700\" y1=\"880\" x2=\"700\" y2=\"970\" stroke=\"#1f4e9a\" stroke-width=\"1\"/>\n"
      "<line x1=\"800\" y1=\"880\" x2=\"800\" y2=\"970\" stroke=\"#1f4e9a\" stroke-width=\"1\"/>\n"
      "<line x1=\"860\" y1=\"880\" x2=\"860\" y2=\"970\" stroke=\"#1f4e9a\" stroke-width=\"1\"/>\n"
      "<text x=\"110\" y=\"900\" fill=\"#1f4e9a\" font-size=\"10\" opacity=\"0.7\">TITLE</text>\n"
      "<text x=\"110\" y=\"925\" fill=\"#1f4e9a\" font-size=\"20\" font-weight=\"bold\">LINEAR REGRESSION</text>\n"
      "<text x=\"360\" y=\"900\" fill=\"#1f4e9a\" font-size=\"10\" opacity=\"0.7\">EQUATION</text>\n"
      "<text x=\"360\" y=\"925\" fill=\"#1f4e9a\" font-size=\"15\">" eq-line1 "</text>\n"
      "<text x=\"360\" y=\"947\" fill=\"#1f4e9a\" font-size=\"15\">" eq-line2 "</text>\n"
      "<text x=\"530\" y=\"900\" fill=\"#1f4e9a\" font-size=\"10\" opacity=\"0.7\">POINTS</text>\n"
      "<text x=\"530\" y=\"925\" fill=\"#1f4e9a\" font-size=\"16\">" n "</text>\n"
      "<text x=\"600\" y=\"900\" fill=\"#1f4e9a\" font-size=\"10\" opacity=\"0.7\">DATE</text>\n"
      "<text x=\"600\" y=\"925\" fill=\"#1f4e9a\" font-size=\"16\">" date "</text>\n"
      "<text x=\"710\" y=\"900\" fill=\"#1f4e9a\" font-size=\"10\" opacity=\"0.7\">DRAWN BY</text>\n"
      "<text x=\"710\" y=\"925\" fill=\"#1f4e9a\" font-size=\"16\">tuiCalc</text>\n"
      "<text x=\"810\" y=\"900\" fill=\"#1f4e9a\" font-size=\"10\" opacity=\"0.7\">SCALE</text>\n"
      "<text x=\"810\" y=\"925\" fill=\"#1f4e9a\" font-size=\"16\">NTS</text>\n"
      "<text x=\"870\" y=\"900\" fill=\"#1f4e9a\" font-size=\"10\" opacity=\"0.7\">SHEET</text>\n"
      "<text x=\"870\" y=\"925\" fill=\"#1f4e9a\" font-size=\"16\">1 OF 1</text>\n"
      "</svg>\n"
      "</body>\n"
      "</html>"))))

;; Command to write HTML to temp file and open in browser.
(defn plot-cmd [html]
  (program/cmd
   (fn []
     (let [path (str (System/getProperty "java.io.tmpdir") "/tuiCalc-plot.html")
           os-name (System/getProperty "os.name")
           opener (if (re-find #"(?i)mac" os-name) "open" "xdg-open")]
       (try
         (spit path html)
         (process/shell opener path)
         {:type :plot-opened}
         (catch Exception e
           {:type :plot-opened :error (ex-message e)}))))))

;; Linear regression over the whole stack (HP-15c L.R.).
;; Stack holds points as flattened pairs: x1 y1 x2 y2 ... (push order).
;; Returns [m b] with intercept b in X (last), slope m in Y.
(defn linreg [state]
  (let [stack (:stack state)
        n (count stack)
        points (partition 2 stack)
        point-count (count points)
        ;; map each point to [1 x y xy x²], reduce with + to get n Σx Σy Σxy Σx²
        sums (->> points
                  (map (fn [[x y]] [1.0 x y (* x y) (* x x)]))
                  (reduce #(mapv + %1 %2) [0.0 0.0 0.0 0.0 0.0]))
        [n-sum sx sy sxy sx2] sums
        denom (- (* n-sum sx2) (* sx sx))]
    (cond
      (odd? n) (assoc state :status "Need x y pairs")
      (< point-count 2) (assoc state :status "Need two points")
      (zero? denom) (assoc state :status "Vertical line")
      :else (let [m (/ (- (* n-sum sxy) (* sx sy)) denom)
                  b (/ (- sy (* m sx)) n-sum)
                  m-sig (sig6 m)
                  b-sig (sig6 (if (neg? b) (- b) b))
                  info-str (str "y = " m-sig "x "
                                (if (neg? b) "− " "+ ") b-sig
                                "  (" point-count " points)")]
              (assoc state :stack [m b] :info info-str)))))

;; Linear regression action: computes regression and returns [state' cmd].
;; If successful (no :status), cmd opens browser plot; otherwise cmd is nil.
(defn linreg-action [state]
  (let [state' (linreg state)]
    (if (:status state')
      [state' nil]
      (let [[m b] (:stack state')
            points (partition 2 (:stack state))]
        [state' (plot-cmd (plot-html points m b))]))))

;; ------------------------------------------------------------
;; Wolfram Engine
;; ------------------------------------------------------------

(def wl-path (str (fs/parent (fs/absolutize *file*)) "/tuiCalc.wl"))

;; Start one long-lived wolframscript and load tuiCalc.wl into it.
;; Returns a send fn: line -> the reply line (from "TC\t" on), or nil
;; on timeout. The kernel prints its In[n]:= prompt on the same line
;; as our reply, so look for the marker anywhere in the line.
(defn start-wolfram [wl-path]
  (try
    (let [p    (process/process ["wolframscript"] {:err :discard})
          w    (io/writer (:in p))
          r    (io/reader (:out p))
          lock (Object.)
          send-line (fn [line]
                      (locking lock
                        (.write w (str line "\n"))
                        (.flush w)
                        (deref (future
                                 (try
                                   (loop []
                                     (when-let [l (.readLine r)]
                                       (if-let [i (str/index-of l "TC\t")]
                                         (subs l i)
                                         (recur))))
                                   (catch Exception _ nil)))
                              20000 nil)))]
      (send-line (str "Get[\"" wl-path "\"]; Print[\"TC\\tREADY\"]"))
      ;; Warm up graphics now: the first plot otherwise takes ~10 s.
      (send-line "tcReply[tcCall[\"ListPlot\", {1., 2.}]]")
      send-line)
    (catch Exception _ nil)))

;; The fn kept in state as :wolfram. Never blocks while the engine is
;; still starting (engine = a future of start-wolfram's result).
(defn wolfram-sender [engine]
  (fn [line]
    (cond
      (not (realized? engine)) "TC\tERR\tWolfram is starting, try again in a moment"
      (nil? @engine)           "TC\tERR\tWolfram Engine failed to start"
      :else (or (@engine line) "TC\tERR\tWolfram timed out"))))

;; Set by -main when wolframscript is installed; init copies it into state.
(def wolfram-fn (atom nil))

;; Wolfram number text: 2.5 -> "2.5", 1.0E10 -> "1.0*^10", 3 -> "3.0"
(defn wl-num [d]
  (str/replace (str (double d)) "E" "*^"))

;; Build tcReply[tcCall["Name", {v1, v2, ...}]]
(defn wolfram-call-line [name stack]
  (let [nums (map wl-num stack)
        args (if (empty? nums) "{}" (str "{" (str/join ", " nums) "}"))]
    (str "tcReply[tcCall[\"" name "\", " args "]]")))

;; Parse Wolfram reply line into a map
(defn parse-wolfram-reply [line]
  (if (nil? line)
    {:error "Wolfram: bad reply"}
    (if (not (str/starts-with? line "TC\t"))
      {:error "Wolfram: bad reply"}
      (let [parts (str/split line #"\t" -1)
            status (nth parts 1 nil)]
        (if (= status "ERR")
          {:error (nth parts 2 nil)}
          (let [k (some-> (nth parts 2 nil) Double/parseDouble long)
                kind (nth parts 3 nil)
                payload (nth parts 4 nil)
                info (nth parts 5 nil)]
            (cond
              (= kind "NUM") {:k k :kind "NUM" :values [(Double/parseDouble payload)] :info info}
              (= kind "LIST") {:k k :kind "LIST" :values (if (str/blank? payload) [] (mapv Double/parseDouble (str/split payload #"\s+"))) :info info}
              (= kind "SYM") {:k 0 :kind "SYM" :values [] :info info}
              (= kind "GFX") {:k 0 :kind "GFX" :path payload :info info}
              :else {:error "Wolfram: unknown reply"})))))))

;; HTML page for GFX (SVG) display
(defn wolfram-plot-html [path info]
  (str "<!doctype html>\n"
       "<html>\n<head><title>tuiCalc — " info "</title></head>\n"
       "<body style=\"background:#f4f1e6;margin:0;padding:20px;text-align:center;\">\n"
       "<img src=\"file://" path "\" style=\"max-width:100%;max-height:80vh;\">\n"
       "<p style=\"font-family:sans-serif;color:#1f4e9a;\">" info "</p>\n"
       "</body>\n</html>"))

;; Wolfram operation: [state cmd]
(defn wolfram-op [state name]
  (let [state (commit-input state)
        wolfram (:wolfram state)]
    (if (nil? wolfram)
      [(assoc state :status "No Wolfram Engine (install wolframscript)") nil]
      (let [stack (:stack state)]
        ;; Check for NaN/Infinity
        (if (some #(or (Double/isNaN %) (Double/isInfinite %)) stack)
          [(assoc state :status "Wolfram needs finite numbers") nil]
          (let [line (wolfram-call-line name stack)
                reply (wolfram line)
                parsed (parse-wolfram-reply reply)]
            (if (:error parsed)
              [(assoc state :status (:error parsed)) nil]
              (let [k (:k parsed)
                    values (:values parsed)
                    info (:info parsed)
                    stack (into (subvec stack 0 (- (count stack) k)) values)]
                (case (:kind parsed)
                  "GFX"
                  [(assoc state :info info) (plot-cmd (wolfram-plot-html (:path parsed) info))]
                  [(assoc state :stack stack :info info) nil])))))))))

;; Wolfram button row
(def wolfram-row [["Sqrt" "[Sqrt]"] ["Mod" "[Mod]"] ["Total" "[Total]"]
                  ["Mean" "[Mean]"] ["Sort" "[Sort]"] ["Max" "[Max]"] ["Min" "[Min]"]
                  ["ListPlot" "[ListPlot]"]])

;; Start a two-step map/reduce prompt.
(defn start-map-reduce-prompt [state pending prompt-str placeholder]
  (assoc state
         :pending pending
         :input (-> (:input state)
                    text-input/reset
                    (assoc :prompt prompt-str)
                    (assoc :placeholder placeholder))))

;; Process the map?/reduce? second-line input.
;; op-arg-str is the part AFTER "map" or "reduce" (e.g., "+ 4" or "chs").
(defn handle-map-reduce-prompt [state pending op-arg-str]
  (let [stack (:stack state)
        op-arg-str (str/trim op-arg-str)]
    (cond
      ;; Empty input cancels the prompt.
      (str/blank? op-arg-str)
      [(abort-prompt state) nil]

      :else
      (let [[op-token & arg-parts] (str/split (str/lower-case op-arg-str) #"\s+")
            arg-str (when (seq arg-parts) (str/join " " arg-parts))]
        (cond
          ;; Unknown op (check before empty stack).
          (and (not (#{"chs" "inv"} op-token))
               (not (contains? ops op-token)))
          [(assoc state :status (str "Unknown: " op-token)) nil]

          ;; Empty stack.
          (empty? stack)
          [(assoc state :status "Stack empty") nil]

          ;; Unary op (chs or inv).
          (#{"chs" "inv"} op-token)
          (if (some? arg-str)
            [(assoc state :status (str op-token " takes no argument")) nil]
            (let [f (get unary-ops op-token)
                  new-stack (mapv f stack)
                  info-str (case op-token
                             "chs" "(mapv - stack)"
                             "inv" "(mapv #(/ 1 %) stack)")]
              [(assoc state :stack new-stack :info info-str) nil]))

          ;; Binary op.
          (contains? ops op-token)
          (if (nil? arg-str)
            (if (< (count stack) 2)
              [(assoc state :status "Need two values") nil]
              (let [operand (peek stack)
                    stack-without-x (pop stack)
                    f (make-bin-fn op-token operand)
                    new-stack (mapv f stack-without-x)
                    op-disp (op-display-name op-token)
                    info-str (str "(mapv #(" op-disp " % " (format-num-for-info operand) ") stack)")]
                [(assoc state :stack new-stack :info info-str) nil]))
            (if-let [arg (parse-number arg-str)]
              (let [f (make-bin-fn op-token arg)
                    new-stack (mapv f stack)
                    op-disp (op-display-name op-token)
                    info-str (str "(mapv #(" op-disp " % " (format-num-for-info arg) ") stack)")]
                [(assoc state :stack new-stack :info info-str) nil])
              [(assoc state :status (str "Unknown: " arg-str)) nil]))

          :else
          [(assoc state :status (str "Unknown: " op-token)) nil])))))

;; Process reduce command with op and optional init value.
(defn handle-reduce [state op-token arg-str]
  (let [stack (:stack state)]
    (cond
      ;; Unknown op (check before empty stack).
      (not (contains? ops op-token))
      [(assoc state :status (str "Unknown: " op-token)) nil]

      ;; Empty stack.
      (empty? stack)
      (if (nil? arg-str)
        [(assoc state :status "Stack empty") nil]
        (if-let [init (parse-number arg-str)]
          (let [op-disp (op-display-name op-token)]
            [(assoc state
                    :stack [(reduce (get ops op-token) init stack)]
                    :info (str "(reduce " op-disp " " (format-num-for-info init) " stack)"))
             nil])
          [(assoc state :status (str "Unknown: " arg-str)) nil]))

      ;; Binary op with no init: reduce over stack.
      (nil? arg-str)
      (let [result (double (reduce (get ops op-token) stack))]
        [(assoc state
                :stack (if (empty? stack) stack [result])
                :info (str "(reduce " (op-display-name op-token) " stack)"))
         nil])

      ;; Binary op with init value.
      :else
      (if-let [init (parse-number arg-str)]
        (let [result (double (reduce (get ops op-token) init stack))
              op-disp (op-display-name op-token)]
          [(assoc state
                  :stack [result]
                  :info (str "(reduce " op-disp " " (format-num-for-info init) " stack)"))
           nil])
        [(assoc state :status (str "Unknown: " arg-str)) nil]))))

;; ------------------------------------------------------------
;; Process one entered token
;; ------------------------------------------------------------

(defn submit [state]
  (let [raw   (str/trim (text-input/value (:input state)))

        ;; Split into the command word and any argument.
        [head & rest] (str/split raw #"\s+")
        token        (str/lower-case head)
        pending      (:pending state)

        ;; Clear the input (and restore its prompt) after Enter, plus
        ;; any stale status/info/pending.
        state (assoc state
                     :input (-> (text-input/reset (:input state))
                                (assoc :prompt input-prompt)
                                (assoc :placeholder input-placeholder))
                     :status nil
                     :info nil
                     :pending nil)]

    (cond

      ;; Two-step prompt: map/reduce takes op+arg (not just integer level).
      ;; This branch must come BEFORE the get/remove integer check.
      ;; Blank input cancels the prompt (no dup).
      (and pending (#{"map" "reduce"} pending))
      (cond
        (str/blank? raw)    [(abort-prompt state) nil]
        (= pending "reduce") (handle-reduce state token
                                            (when (seq rest) (str/join " " rest)))
        :else               (handle-map-reduce-prompt state pending raw))

      ;; Two-step prompt level: head is the LEVEL for the pending
      ;; get/nth/remove. (Non-integer input cancels the prompt and
      ;; falls through to be handled as a normal command.)
      (and pending (parse-integer head))
      (case pending
        "get"    [(get-index state (parse-integer head)) nil]
        "remove" [(remove-index state (parse-integer head)) nil])

      ;; Empty Enter -- dup the top value (HP ENTER key). With an
      ;; empty stack it's a no-op.
      (str/blank? raw)
      (if (empty? (:stack state))
        [state nil]
        [(get-index state 0) nil])

      ;; Quit commands.
      (#{"q" "quit" "exit"} token)
      [state program/quit-cmd]

      ;; Clear the whole stack.
      (= token "clear")
      [(assoc state :stack []) nil]

      ;; Undo.
      (= token "undo")
      [(undo state) nil]

      ;; Change sign of the top value.
      (= token "chs")
      [(chs state) nil]

      ;; Invert the top value (1/x).
      (= token "inv")
      [(inv state) nil]

      ;; Swap X and Y.
      (#{"swap" "x<>y"} token)
      [(swap-xy state) nil]

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

      ;; Linear regression (HP-15c L.R.).
      (#{"linreg" "lr"} token)
      (linreg-action state)

      ;; Get the value at level <i> (dup/roll). No level yet: start a
      ;; two-step prompt -- the input box itself becomes "get? " and
      ;; you type the level (0=X, 1=Y, -1=T), then Enter. Any other
      ;; input cancels the prompt and runs as a command.
      (#{"get" "nth"} token)
      (if-let [i (parse-integer (first rest))]
        [(get-index state i) nil]
        [(start-level-prompt state "get" (str token "? ")) nil])

      ;; Remove the value at level <i>. Same two-step prompt.
      (= token "remove")
      (if-let [i (parse-integer (first rest))]
        [(remove-index state i) nil]
        [(start-level-prompt state "remove" "remove? ") nil])

      ;; map: start two-step prompt or run with op+arg.
      (= token "map")
      (if (some? (first rest))
        (handle-map-reduce-prompt state "map" (str/join " " rest))
        [(start-map-reduce-prompt state "map" "map? "
                                  "[n] op: 4 +, 2 *, 2 /, + (by X), chs, inv")
         nil])

      ;; reduce: start two-step prompt or run with op+init.
      (= token "reduce")
      (if (some? (first rest))
        (handle-reduce state (str/lower-case (first rest))
                       (when (>= (count rest) 2) (str/join " " (drop 1 rest))))
        [(start-map-reduce-prompt state "reduce" "reduce? "
                                  "[start] op: +, *, /, 10 + (sum+10)")
         nil])

      ;; Arithmetic operator.
      (contains? ops token)
      (let [[stack status]
            (apply-op (:stack state)
                      (ops token))]
        [(assoc state
                :stack stack
                :status status)
         nil])

      ;; Otherwise try to push a number or Wolfram command.
      :else
      (if-let [n (parse-number token)]
        [(update state :stack conj n) nil]
        (if (re-matches #"[A-Z][A-Za-z0-9]*" raw)
          (wolfram-op state raw)
          [(assoc state
                  :status (str "Unknown: " raw))
           nil])))))

;; Help buttons, grouped into rows. Each button is [action display];
;; the rows render as the help lines and drive click hit-testing, so
;; labels and positions can never drift apart.
(def help-rows
  [[["+" "[+]"] ["-" "[-]"] ["*" "[*]"] ["/" "[/]"] ["^" "[y^x]"] ["CHS" "[CHS]"] ["INV" "[INV]"] ["pi" "[pi]"]]
   [["enter" "[ENTER]"] ["clear" "[CLEAR]"] ["drop" "[drop]"]
    ["swap" "[x<>y]"] ["peek" "[peek]"] ["count" "[count]"]
    ["reverse" "[reverse]"] ["get" "[GET]"]]
   [["map" "[map]"] ["reduce" "[reduce]"] ["linreg" "[L.R.]"] ["undo" "[undo]"] ["q" "[q]"]]
   wolfram-row])

;; Faint legend under the buttons.
(def hint-line
  "^x drop ^s chs ^v inv ^t peek ^n count ^r rev ^l clear ^p pi ^g get ^ pow ^z undo")

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

;; Run a button action (shared by mouse clicks and keyboard shortcuts).
;; Returns [new-state command]. The caller adds the flash on top.
;; Any action other than [GET] cancels a pending level prompt.
(defn run-action [state action]
  (if (some #(= action (first %)) wolfram-row)
    (wolfram-op state action)
    (case action
      "CHS"     [(chs state) nil]
      "INV"     [(inv state) nil]
      "pi"      [(push-pi state) nil]
      "clear"   [(assoc state :stack []) nil]
      "drop"    [(pop-top state) nil]
      "swap"    [(swap-xy state) nil]
      "peek"    [(peek-top state) nil]
      "count"   [(count-stack state) nil]
      "reverse" [(reverse-stack state) nil]
      "get"     [(start-level-prompt state "get" "get? ") nil]
      "map"     [(start-map-reduce-prompt state "map" "map? "
                                          "[n] op: 4 +, 2 *, 2 /, + (by X), chs, inv") nil]
      "reduce"  [(start-map-reduce-prompt state "reduce" "reduce? "
                                          "[start] op: +, *, /, 10 + (sum+10)") nil]
      "linreg"  (linreg-action state)
      "undo"    [(undo state) nil]
      "q"       [state program/quit-cmd]
      "enter"   (submit state)
      [(execute-op state action) nil])))

;; Ctrl-key shortcuts (no ctrl conflicts with the text input's own
;; bindings -- it uses ctrl a b d e f h k u w). Values are actions, so
;; they share run-action with the buttons and flash identically.
(def shortcuts
  {"x" "drop"
   "s" "CHS"
   "v" "INV"
   "t" "peek"
   "n" "count"
   "r" "reverse"
   "l" "clear"
   "p" "pi"
   "g" "get"
   "z" "undo"})

;; Which button a typed command corresponds to (so pressing Enter
;; flashes the button that actually ran, not just [ENTER]). Nil for
;; tokens with no button (numbers, unknown, ...).
(defn button-for-token [token]
  (condp = token
    "chs" "CHS"
    "inv" "INV"
    "pi" "pi"
    "clear" "clear"
    "q" "q" "quit" "q" "exit" "q"
    "drop" "drop" "pop" "drop"
    "swap" "swap" "x<>y" "swap"
    "peek" "peek"
    "count" "count"
    "reverse" "reverse"
    "get" "get" "nth" "get" "remove" "get"
    "map" "map"
    "reduce" "reduce"
    "linreg" "linreg" "lr" "linreg"
    "undo" "undo"
    ;; Wolfram buttons (lowercased token -> button name)
    "sqrt" "Sqrt"
    "mod" "Mod"
    "total" "Total"
    "mean" "Mean"
    "sort" "Sort"
    "max" "Max"
    "min" "Min"
    "listplot" "ListPlot"
    "pow" "^"
    (when (and (string? token) (contains? ops token))
      ;; "x" is an ops alias for the "*" button.
      (if (= token "x") "*" token))))

;; The button that should flash for an activation. For "enter" (key
;; or click), the pending input decides: typing "chs" then Enter
;; flashes [CHS], a bare number flashes [ENTER]. A level typed into
;; a two-step get?/remove? prompt flashes [GET] -- but if the prompt
;; is active and the input is NOT a level, the prompt is cancelled
;; and the input runs as a normal command, so THAT button flashes.
;; Must be called with the PRE-action state, since submit clears the
;; input.
(defn flash-for-action [state action]
  (if (= action "enter")
    (let [head (str/lower-case
                (first (str/split
                        (str/trim (text-input/value (:input state)))
                        #"\s+")))]
      (if (and (:pending state) (parse-integer head))
        "get"
        (or (button-for-token head)
            "enter")))
    action))

(defn handle-click [state x y]
  ;; Buttons are only shown when the help line isn't showing an error.
  (let [btn (when (not (:status state))
              (find-button-at x y))]
    (if btn
      ;; A pending level prompt is abandoned when a DIFFERENT button is
      ;; clicked; [ENTER] finishes the prompt; [GET] keeps it going.
      (let [state (if (and (:pending state)
                           (not= btn "enter")
                           (not= btn "get"))
                    (abort-prompt state)
                    state)
            [s' c'] (if (and (:pending state) (= btn "get"))
                      [state nil]
                      (run-action state btn))]
        [(with-flash s' (flash-for-action state btn))
         (if c' (program/batch c' (flash-cmd)) (flash-cmd))])
      [state nil])))

;; ------------------------------------------------------------
;; Charm state
;; ------------------------------------------------------------

(defn init []
  [{:stack  []
    :input  (text-input/text-input
             :prompt input-prompt
             :placeholder input-placeholder
             :placeholder-style (style/style :faint true)
             :focused true
             :width 30)
    :status  nil
    :info    nil
    :pending nil
    :undo    []
    :wolfram @wolfram-fn}
   nil])


;; ------------------------------------------------------------
;; Charm update
;; ------------------------------------------------------------

;; Track undo history: record old stack if stack changed and undo wasn't touched.
(defn track-undo [old new]
  (if (and (not= (:stack old) (:stack new))
           (= (:undo old) (:undo new)))
    (let [old-stack (:stack old)
          undo-list (or (:undo new) [])
          new-undo (vec (take-last undo-limit (conj undo-list old-stack)))]
      (assoc new :undo new-undo))
    new))

;; Handle messages (renamed from update-fn).
(defn handle-message [state message]
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

      ;; Plot opened (or failed).
      (= :plot-opened (:type message))
      [(cond-> state (:error message) (assoc :status (str "Plot: " (:error message)))) nil]

      ;; Mouse clicks on help-line buttons.
      (and (msg/mouse? message)
           (= :press (:action message))
           (= :left (:button message)))
      (handle-click state (:x message) (:y message))

      ;; Esc cancels a two-step level prompt (get?/remove?).
      (and (:pending state)
           (= :escape key))
      [(abort-prompt state) nil]

      ;; Ctrl-key shortcuts (drop, CHS, peek, ...). ^g keeps/starts
      ;; the prompt; everything else abandons it (discarding the
      ;; half-typed level).
      (and (msg/key-press? message)
           (:ctrl message)
           (string? key)
           (contains? shortcuts key))
      (let [action (shortcuts key)
            [s' c'] (run-action state action)]
        [(with-flash
          (if (= action "get") s' (abort-prompt s'))
          action)
         (if c' (program/batch c' (flash-cmd)) (flash-cmd))])

      ;; RPN at a map?/reduce? prompt: an operator key (including -)
      ;; runs it now, with whatever number was typed as the arg/init:
      ;; map? 4 then + is "map + 4"; map? then + is "map +" (X operand).
      ;; It reuses the Enter path by submitting "<op> <number>".
      (and (msg/key-press? message)
           (not (:ctrl message))
           (#{"map" "reduce"} (:pending state))
           (contains? ops key)
           (not= "x" key)
           (let [txt (text-input/value (:input state))]
             (or (str/blank? txt) (parse-number txt))))
      (let [line    (str/trim (str key " " (text-input/value (:input state))))
            [s' c'] (submit (update state :input assoc
                                    :value (vec line) :pos (count line)))]
        [(with-flash s' (:pending state)) (if c' (program/batch c' (flash-cmd)) (flash-cmd))])

      ;; Operators execute immediately (keyboard shortcuts, except - for
      ;; negative numbers and x so words like "exit" can be typed) --
      ;; but only while the input is blank or a number, so a typed
      ;; command like "map + 4" can contain operators. Abandons a
      ;; pending level prompt, discarding the half-typed level so it
      ;; can't be committed as a number.
      (and (msg/key-press? message)
           (not (:ctrl message))
           (contains? ops key)
           (not (#{"-" "x"} key))
           (not (#{"map" "reduce"} (:pending state)))
           (let [txt (text-input/value (:input state))]
             (or (str/blank? txt) (parse-number txt))))
      (let [s' (execute-op (if (:pending state)
                             (abort-prompt state)
                             state)
                           key)]
        [(with-flash s' key) (flash-cmd)])

      ;; Enter submits whatever is currently typed. Flash the button
      ;; that corresponds to what actually ran ([CHS] for "chs",
      ;; [ENTER] for a bare number).
      (msg/key-match? message :enter)
      (let [[s' c'] (submit state)]
        [(with-flash s' (flash-for-action state "enter"))
         (if c' (program/batch c' (flash-cmd)) (flash-cmd))])

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

;; Every message goes through here so any stack change is undoable.
(defn update-fn [state message]
  (let [[new-state cmd] (handle-message state message)]
    [(track-undo state new-state) cmd]))

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

;; charm's resize path only re-diffs against the previous frame, so a
;; resize (e.g. Herdr laying out a pane after launch) leaves a stale
;; copy of the calculator on screen. Clear and force a full repaint.
;;
;; render/repaint! can't do that: charm builds a non-fullscreen JLine
;; Display, whose .clear is a no-op. The Display would keep diffing
;; against the frame we just wiped, and an unchanged frame (the usual
;; case at launch) wrote nothing -- a blank screen until a keypress.
;; Instead render an empty frame first, so the Display forgets the old
;; one and parks its cursor at the top, then wipe the real screen. The
;; program loop renders the view right after this, in full.
(defn clear-on-resize! []
  (alter-var-root #'render/update-size!
                  (fn [orig]
                    (fn [renderer width height]
                      (orig renderer width height)
                      (.update (:display @renderer) (java.util.ArrayList.) -1)
                      (render/clear-screen! renderer)))))

(defn -main [& _]
  (clear-on-resize!)
  (when-let [wl (fs/which "wolframscript")]
    (reset! wolfram-fn (wolfram-sender (future (start-wolfram wl-path)))))
  (program/run
   {:init init
    :update update-fn
    :view view
    :alt-screen true
    :mouse :normal}))  ; Enable mouse clicks

;; Auto-start only when tuiCalc is the ENTRY script, not when a test
;; (or anything else) merely requires the ns. While tuiCalc is being
;; loaded, *file* is tuiCalc.clj; babashka.file is the entry file. They
;; match only when we were launched directly.
(when (= (str *file*) (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
