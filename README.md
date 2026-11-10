# tuiCalc

Little experiment in an RPN Babashka calculator — the stack is a plain
Clojure vector and the buttons/commands are Clojure collection ops on it,
with HP-15c ergonomics (levels, ENTER to dup) and a rainbow button flash so
it's easy to follow on a screen share.

This has been a test of different LLM models.

## Run

You need [babashka](https://babashka.org/) (bb) — on macOS:

```
brew install babashka        # or: brew upgrade babashka
bb --version                 # v1.13.x
```

No other setup: the charm dependency is pinned in `bb.edn` and fetched
automatically on first run.

```
git clone https://github.com/xicubed/tuiCalc.git
cd tuiCalc
bb ./src/tuiCalc.clj
```

(or `bb -m tuiCalc`)

The TUI renders a stack box, a `> ` input line, and four rows of clickable
buttons.

## Wolfram Engine (optional)

tuiCalc works fully without it as an RPN calculator (native math, stack ops,
map/reduce, linear regression and its plot). The Wolfram button row then
says the engine isn't installed, and a note on launch says so.

With the free [Wolfram Engine](https://www.wolfram.com/engine/) installed,
the 4th button row has folders `[Math ▸]` `[Stats ▸]` `[Lists ▸]`
`[Plots ▸]`. Typing a Capitalized word searches ~6,500 Wolfram functions
(`Mea` -> Mean, MeanShift, ...), and Enter runs the best match. Capitalized
= Wolfram, lowercase = Clojure. `'x` pushes the symbol x; arithmetic on
symbols is done by Wolfram, so `'x 1 + 2 y^x` -> `(1 + x)^2`.
`expr 'x [xmin xmax] Plot` plots in the browser (same blueline style as the
lr plot).

Setup (macOS):

1. Get the free Wolfram Engine: https://www.wolfram.com/engine/ (needs a
   free Wolfram ID; it installs `wolframscript`).
2. Activate it once: run `wolframscript` and sign in with the Wolfram ID
   when asked.
3. If tuiCalc reports "Wolfram Engine failed to start: no answer", point
   wolframscript at the kernel: `wolframscript -configure` (for example
   if you installed the engine in ~/Applications). If it reports a
   "license error: single machine process limit reached", another
   tuiCalc or Wolfram session is using the engine: the free engine runs
   only a limited number of kernels at once, so close the other one.
4. Check: `wolframscript -code '1+1'` prints 2 (the first run takes a few
   seconds).
5. Plots use the Architects Daughter font; the kernel draws the text, so
   install it for the kernel:
   `~/Library/Fonts/ArchitectsDaughter-Regular.ttf` from
   https://github.com/google/fonts (`ofl/architectsdaughter`). Without it,
   plots fall back to a plain sans font.

How it runs: tuiCalc starts one long-lived `wolframscript` process in the
background at launch (a few seconds; calls before it's ready say "Wolfram
is starting, try again in a moment"); after that calls take milliseconds.

## Using it

Numbers and commands go into the input line; **Enter** submits.

**X is the most recent value** — the one you're working with (bare ENTER
dups it, operators use it as the right operand, `drop` removes it, `peek`
shows it). Everything else in the box is just older values.

The box is drawn HP-15c style: the stack fills from the bottom of the frame
upward, so X is on the bottom row and the older values (Y, Z, … T) stack up
above it. It isn't capped at four — the box is a fixed-height frame and the
oldest values fall off the top once the stack exceeds it.

| Action | How | What happens |
|---|---|---|
| Push a number | type `7` then Enter | `7.0` on the stack, `[ENTER]` flashes |
| **Dup (HP ENTER)** | Enter with the box **empty** | copies the top value onto the stack |
| Add / sub / mul / div | two numbers on stack, then click `[+]` `[-]` `[*]` `[/]` (or type `+` `-` `*` `/`) | result pushed; the operator button flashes |
| Negate | `chs` + Enter, or `^s` | top value changes sign |
| Drop top | `^x`, or `drop` + Enter | top value removed |
| Peek | `^t`, or `peek` + Enter | shows `peek = X` without changing the stack |
| Count | `^n`, or `count` + Enter | shows `count = N` |
| Reverse | `^r`, or `reverse` + Enter | stack order flips |
| Copy from a level | `get` + Enter, type a level, Enter (or `get 0`) | copies that level to X (`0`=X, `1`=Y, `-1`=T) |
| Remove a level | `remove` + Enter, level, Enter | removes that level |
| Swap X and Y | click `[x<>y]`, or `swap` / `x<>y` + Enter | X (newest) and Y trade places |
| Map over stack | `map` + Enter, then op + Enter (or `map + 4`) | op applied to every value (`map + 4`, `map * 2`, `map chs`; a bare `map +` uses X as the operand) |
| Reduce stack | `reduce` + Enter, then op + Enter (or `reduce +`) | stack folds to one value (`reduce +`, `reduce *`) |
| Linear regression | push points as `x1 y1 x2 y2 ...`, then `lr` + Enter or `[L.R.]` | stack becomes slope (Y) and intercept (X) at full precision; info shows e.g. `y = 2x + 1  (3 points)` (numbers displayed at 6 significant digits) — errors `Need x y pairs`, `Need two points`, `Vertical line` |
| Clear | `clear` + Enter, or `^l` | empty stack |
| Quit | `q` + Enter | exit |

Notes:

- **Bare ENTER dups the top value** (no-op on an empty stack) — the HP ENTER
  key. Enter *with text typed* runs that command instead.
- **Mouse:** every button in the help rows is clickable; clicking any button
  flashes it so a screen-share viewer sees what ran.
- **Plot:** on successful `lr`, the app writes a self-contained HTML/SVG
  page to `tuiCalc-plot.html` in the system temp dir and **opens it in the
  browser** (`open` on macOS, `xdg-open` on Linux). Old-style blueline
  drawing: blue ink on off-white, a square equal-scale plot box (both axes
  share one grid step, so slope 1 is 45 degrees) always including the
  origin, dots with "x, y" hover labels, and a title block with equation,
  point count, and date.
- **Two-step prompts:** typing just `get` or `remove` turns the input line
  into `get? `/`remove? ` — the next integer you type is the level. Pressing
  Escape or clicking another button cancels.
- Errors (e.g. `Need two values`) show above the buttons and clear on the
  next action.

## Testing

**Unit** — `bb test` runs the suite in `test/tuiCalc_test.clj`, prints the
`clojure.test` summary, exits non-zero on failure. **TESTING.md** has how to
add tests and the babashka gotchas — read it before changing `src/tuiCalc.clj`.

**Manual** — the unit tests can't see rendering, the flash, or the mouse, so
after a change run the TUI and smoke-test it:

1. `bb ./src/tuiCalc.clj`
2. `4` Enter, `5` Enter, click `[+]` → `9.0`
3. Enter with the box **empty** → `9.0` dups (bare ENTER = HP ENTER)
4. `^s` (CHS) → top flips to `-9.0`; the `[CHS]` button flashes
5. `get` Enter, `0` Enter → copies top again; `remove` Enter, `0` Enter removes it
6. `^t` (peek) / `^n` (count) → info line, stack untouched
7. `0` `1` `1` `3` `2` `5` (Enter after each), `lr` → stack `2.0` over `1.0` (slope over intercept), info `y = 2x + 1  (3 points)`, and a blueline plot opens in the browser (3 dots on a line rising 2 squares per square, from the origin corner)
8. `q` Enter → quit

Watch for: new values appearing on the **bottom row** (the stack-top / X),
the right button flashing on every action, and the help rows not shifting
(a layout miscount breaks click positions). Full checklist: **TESTING.md**.

## TODO

More Clojure stack/vector manipulation functions to get a playground feel for
how they work — not to mention map/reduce :>

Known bugs from code review: **TODO-tuiCalc.md** (item 1 fixed; open:
pending text dropped on operator keypress, `ieee-div` sign for negative-zero
divisor, plus a few minor ones).

## Notes for contributors (and for Claude)

**CLAUDE.md** holds the design decisions and repo conventions; it's loaded
into Claude Code sessions automatically.

## Copyright

Copyright (c) 2026 University of Idaho. All rights reserved.
