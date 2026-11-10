# tuiCalc manual

For the person sitting at the calculator: what it does and how to drive
it. Setup (babashka, Wolfram Engine) is in **README.md**; the Wolfram
bridge internals are in **docs/wolfram-protocol.md**.

This is a living draft, organised by feature. New sections get added as
features land.

## Quick start

```
bb ./src/tuiCalc.clj     # launch
4 Enter, 5 Enter, +      # -> 9.0
q Enter                  # quit
```

## The stack

- The stack grows **up** out of the box: **X is the newest value** and
  sits on the **bottom row**; older values (Y, Z, … T) stack up above it.
  When the box is full, the oldest values fall off the top — the stack
  itself keeps everything.
- **Bare ENTER** (Enter with an empty input box) dups X. It's the HP
  ENTER key.
- `get`/`nth`/`remove` take a **level** = offset from X: `0` = X, `1` = Y,
  `2` = Z … and `-1` = the oldest value, `-2` the next. Example:
  `get 1` on `[5 9]` copies Y, giving `[5 9 5]`; `get -1` copies the
  oldest.
- Numbers display at 6 significant digits; the stack keeps full precision.

## Typing vs clicking

Everything works from both the keyboard and the mouse:

- Type in the `> ` box, **Enter** submits. Click a button in the help
  rows for the same effect.
- Every action **flashes its button** in rainbow — so on a screen share
  you can see what fired, whether it came from a key or a click.
- **Two-step prompts:** typing `get` alone turns the box into `get? ` —
  the next number you type is the level (or write it in one go: `get 0`).
  Same for `remove`, `map`, `reduce`. Escape, clicking another button,
  or typing a different command cancels the prompt.
- **Undo:** `ctrl+z`, `[undo]`, or type `undo`, restores the previous
  stack (keeps the last 100).
- ctrl-key shortcuts: `^x` drop, `^s` CHS, `^v` INV, `^t` peek,
  `^n` count, `^r` reverse, `^l` clear, `^p` pi, `^g` get, `^z` undo.
- Errors (`Need two values`, `Stack empty`, `Unknown: foo`) show above
  the buttons and clear on the next action.

## Command reference

| Command | Aliases / key / button | Stack effect | Example |
|---|---|---|---|
| `7` (a number) | — | pushes the number | `7` -> `[7.0]` |
| `pi` | `[pi]`, `^p` | pushes π | `pi` -> `[3.14159]` |
| *(empty input)* | `[ENTER]`, Enter | dups X | `[3]`, Enter -> `[3 3]` |
| `+` `-` `*` `/` | `x` = `*`; `[+]` `[-]` `[*]` `[/]` | Y op X, result pushed | `+` on `[3 6]` -> `[9.0]` |
| `^` | `pow`, `[y^x]` | Y^X (HP y^x) | on `[2 10]` -> `[1024.0]` |
| `chs` | `[CHS]`, `^s` | X changes sign | on `[5]` -> `[-5]` |
| `inv` | `[INV]`, `^v` | 1/X (`1/0` -> Infinity) | on `[5]` -> `[0.2]` |
| `drop` | `pop`, `[drop]`, `^x` | removes X | on `[3 7]` -> `[3]` |
| `peek` | `[peek]`, `^t` | none; info shows `peek = 7` | — |
| `count` | `[count]`, `^n` | none; info shows `count = 2` | — |
| `reverse` | `[reverse]`, `^r` | order flips | `[3 7]` -> `[7 3]` |
| `swap` | `x<>y`, `[x<>y]` | X and Y trade places | `[3 7]` -> `[7 3]` |
| `get n` / `get` + prompt | `nth`, `[GET]`, `^g` | copies level n to X | `get 1` on `[5 9]` -> `[5 9 5]` |
| `remove n` / `remove` + prompt | — | removes level n | `remove 1` on `[5 9]` -> `[9]` |
| `clear` | `[CLEAR]`, `^l` | empty stack | `[1 2 3]` -> `[]` |
| `undo` | `[undo]`, `^z` | restores the previous stack | — |
| `q` | `quit`, `exit`, `[q]` | quits | — |

Underflow (`+` with fewer than two values) leaves the stack alone and
says `Need two values`.

### map — apply an op to every value

Numbers only. `map` alone starts a `map? ` prompt; or write it in one go.

| Form | Meaning | Example |
|---|---|---|
| `map + 4` | add 4 to every value (same for `-` `*` `x` `/` `^`) | on `[1 2 3]` -> `[5.0 6.0 7.0]` |
| `map +` | X is the operand, mapped over the rest | on `[1 2 10]` -> `[11.0 12.0]` |
| `map + pi` | constants work as the argument | on `[1]` -> `[4.14159]` |
| `map chs` / `map inv` | flip every sign / invert every value | on `[1 -2]` -> `[-1.0 2.0]` |

Info shows the Clojure form, e.g. `(mapv #(+ % 4) stack)`.

### reduce — fold the stack to one value

| Form | Meaning | Example |
|---|---|---|
| `reduce +` | fold with the op (`+` `-` `*` `/` `^` `pow`) | on `[1 2 3]` -> `[6.0]` |
| `reduce + 10` | fold starting from 10 (works on an empty stack: -> `[10.0]`) | on `[1 2 3]` -> `[16.0]` |

### linreg / lr — linear regression (HP-15c L.R.)

Push points as `x1 y1 x2 y2 …`, then `linreg` (or `lr`, or `[L.R.]`).
The stack becomes **slope in Y, intercept in X** and the info line shows
the fit: `y = 2x + 1  (3 points)` (with a minus sign when the intercept
is negative: `y = 2x − 1  (3 points)`). Errors: `Need x y pairs` (odd
count), `Need two points`, `Vertical line`.

On success a **blueline plot** opens in the browser: blue ink on
off-white graph paper, a square equal-scale grid (slope 1 draws at 45°)
that always includes the origin, one dot per point (hover shows `x, y`),
and a title block with the equation, point count, and date.

## Wolfram functions

With the free Wolfram Engine installed (setup in **README.md**), any
**Capitalized** name goes to Wolfram — typed, clicked, or searched.
Lowercase stays Clojure.

- **How many values a function takes** depends on the function: `Sqrt`,
  `Sin`, `Abs` take X only (like an HP key); `Mod`, `Power`, `D` take
  Y and X; `Total`, `Mean`, `Sort`, `Max`, `Min` take the **whole stack**
  as a list; constants like `Pi` take none.
- **The fourth button row** is a folder menu: `[Math ▸]` (Sqrt, Abs,
  Round, Floor, Mod, Factorial), `[Stats ▸]` (Total, Mean, Median,
  StandardDeviation, Variance), `[Lists ▸]` (Sort, Max, Min, Accumulate,
  Differences), `[Plots ▸]` (Plot, ListPlot, ListLinePlot, Histogram).
  Inside a folder, `[◂ back]` returns. Typing a menu function opens its
  folder so you see its button flash.
- **Type-to-filter:** typing a Capitalized word turns the row into
  matches (`Mea` -> `[Mean] [MeanShift] … +8`; menu functions first,
  then shortest). Enter runs the exact name or the first match; clicking
  a match runs it. About 6,500 documented functions are searchable.
- **Results:** a number replaces the values consumed; a list result
  replaces them too (so `Sort` gives a sorted stack); graphics open in
  the browser as a blueline-styled page (Wolfram's own chart, blue ink
  on off-white) and the stack is untouched. The info line shows what
  ran: `Total[{1, 2, 3, 4}] = 10`.
- **Starting and missing:** calls while the engine starts answer
  `Wolfram is starting, try again in a moment` (it takes a few seconds
  after launch). With no engine installed, the row says
  `Wolfram functions: not installed (see README)` and a launch notice
  says `No Wolfram Engine: native math only (see README)`. A failing
  engine reports `Wolfram Engine failed to start: <reason>` — see the
  README's setup steps (`wolframscript -configure` fixes most cases).

## Symbols and expressions

An expression is a symbolic value kept as text (`x`, `1 + x`, `x^2`).

- `'x` pushes the **symbol** x.
- **Arithmetic with a symbol on the stack goes to Wolfram** and stays
  symbolic: `'x` Enter, `1` `+`, `2` `^` -> `(1 + x)^2`. Numbers-only arithmetic never
  leaves the calculator.
- `chs` and `inv` work on expressions too (`1/x` becomes `x^(-1)`).
- Whole numbers are sent exactly (`10` Enter, `Fibonacci` Enter -> `55`,
  not `55.000000000000014`).
- **Plot of an expression:** `expr 'x [xmin xmax] Plot` ->
  `Plot[expr, {x, xmin, xmax}]`; without the range, -10 to 10. The plot
  opens in the browser, stack untouched. Example: build `(1 + x)^2` as
  above, then `'x` Enter, `-5` Enter, `3` Enter, `Plot` Enter. Forget the
  `'x` and it says `Plot needs: expr 'x [xmin xmax]`.
- Expressions display at most 28 characters in the box (with `…`);
  `peek` shows the full text.
- **"needs numbers":** `map`, `reduce` and `linreg` are native Clojure
  and can't touch expressions — with a symbol on the stack they say
  `map needs numbers` / `reduce needs numbers` / `linreg needs numbers`.
  (Mapping a Wolfram function over the stack is planned, see below.)

## Known limits

- The `map?`/`reduce?` placeholder shows the argument first (`4 +`,
  `10 +`), but the command wants the **op first**: `map + 4`,
  `reduce + 10`. Follow the command form, not the placeholder.
- `Expand`, `Factor`, `Simplify` take the **whole stack** instead of X
  and return one list containing the result.
- `1/x` displays as `x^(-1)` (Wolfram InputForm), not `1/x`.
- Exact constants come back as decimals: `2 'Pi *` -> `6.28319`, not
  `2*Pi`.
- `Plot` uses Wolfram's default PlotRange, which clips tall peaks
  (`Sin[x]/x` draws only to about 0.42).
- The `lr` plot always shares one scale between both axes, so an extreme
  slope draws as a thin sliver.

## What's coming (planned, not built)

- `fn`: turn the expression in X into a function of its free variable —
  apply it to a number, `map` it, Plot/D/Integrate it.
- Quote and eval: `'`-quoting an op keeps it unevaluated (`2 3 '+`),
  `eval` evaluates it; a one-shot `[']` button for clicks.
- `N`: convert an exact result to a decimal.
- `map`/`reduce` with Wolfram functions.
- 3D results (`Plot3D`, `PolyhedronData`) with the blueline 3D look.
- Exact numbers on the stack (ratios, `Sqrt[2]`) instead of doubles.
- Menu folders filled from the Wolfram documentation guides, with
  paging, instead of the hand-picked four.
