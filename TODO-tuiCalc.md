# tuiCalc.clj — bugs to fix

From code review on 2026-09-15, not yet implemented.

## 1. Negative numbers can't be entered directly
`update-fn` (src/tuiCalc.clj:149-152) intercepts any keypress matching an
`ops` key (`+ - x * /`) before it reaches the text field, so `-` can never
be typed as the leading char of a number literal. Pressing `-` always
commits pending input and subtracts, even if you meant to start typing
`-5`. If a valid pair is already on the stack, this silently pops and
subtracts them instead of starting a new number.
Workaround today: `0 [Enter] 5 -`.

## 2. Invalid pending text silently dropped on operator keypress
`commit-input` (src/tuiCalc.clj:60-67) returns the state unchanged
(input NOT cleared) when `parse-number` returns nil for the typed token.
`execute-op` then applies the operator to the existing stack anyway, so
garbage text (e.g. "abc") is silently discarded with no status message
and stays visible in the input box. The Enter/`submit` path (line
129-134) does clear input and does show "Unknown: X" — inconsistent
behavior between the two entry paths.

## 3. `ieee-div` wrong sign for negative-zero divisor
`ieee-div` (src/tuiCalc.clj:17-28) picks the sign of infinity from
`(neg? a)` only, ignoring the sign of `b`. Per IEEE-754, `x / -0.0`
should flip sign vs `x / 0.0`. Since `(zero? -0.0)` is true in Clojure,
`(ieee-div 5.0 -0.0)` returns `+Infinity` but should return `-Infinity`.
Reachable via e.g. `3 [Enter] 5 -` (-2.0) then `0 [Enter] *` (-0.0) as
a divisor.

## Minor / lower priority
- `parse-number`'s try/catch around `parse-double` (line 40-43) is
  likely dead code — Clojure 1.11 `parse-double` returns nil rather
  than throwing.
- Quit/clear commands are case-sensitive ("Q", "CLEAR" don't work).
