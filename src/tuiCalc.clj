(ns tuiCalc)

;; Function to add two numbers
(defn add [a b] (+ a b))

;; Function to subtract two numbers
(defn subtract [a b] (- a b))

;; Function to multiply two numbers
(defn multiply [a b] (* a b))

;; Function to divide two numbers
(defn divide [a b]
  (if (zero? b) "Error: Division by zero" (/ a b)))

;; Main function to run the calculator
(defn run [stack]
  (println "TUI Calculator")
  (println "Enter 'q' to quit")

  (loop [stack stack]
    (show-stack stack)
    (print "> ")
    (flush)

    (when-let [input (read-line)]

      (let [input (str/trim input)]
        (cond

          ;; Quit
          (#{"q" "quit" "exit"} input)
          (do (println "Exiting calculator.")
              (exit))

          ;; Add
          (= input "+")
          (recur (conj stack (add (peek stack) (second stack))))

          ;; Subtract
          (= input "-")
          (recur (conj stack (subtract (peek stack) (second stack))))

          ;; Multiply
          (= input "*")
          (recur (conj stack (multiply (peek stack) (second stack))))

          ;; Divide
          (= input "/")
          (recur (conj stack (divide (peek stack) (second stack))))

          :else
          (println "Invalid input"))))))

;; Show stack function
(defn show-stack [stack]
  (println "┌──────────── icalc ───────────┐")

  (let [visible (vec (take-last 5 stack))]
    (dotimes [_ (- 5 (count visible))]
      (println "│                              │"))

    (doseq [n visible]
      (println (str "│ " n " │")))))

;; Main loop
(run [])
