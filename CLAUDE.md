# CLAUDE.md — fizz / tuiCalc

Loaded into every Claude session in this repo. Keep it accurate — it's the
restorable source of project context (a fresh `git clone` on a new machine
brings this along, so put the things that matter here rather than relying on
local notes).

## What it is

`src/tuiCalc.clj` is a babashka + [charm](https://github.com/TimoKramer/charm.clj)
TUI calculator being built to feel like "being inside Clojure's mind" — a
REPL on steroids where the calculator stack **is** a Clojure vector and the
commands are real Clojure collection ops on it, with **HP-15c** ergonomics.
It's also designed to be screen-share-friendly (big buttons, visible feedback).

## Confirmed design decisions

- The stack is a plain Clojure **vector** in push order (oldest first,
  newest last). **X = the newest value** is the last element and is "the
  top of the stack" — the box draws it at the base and grows *up* (so the
  box-top value is the *oldest*, the T register).
- Indexed ops (`get`/`nth`/`remove <i>`) take a **level = offset from X**,
  not a vector index: `0` = X, `1` = Y, `2` = Z, … deeper; `-1` = T
  (first-in / oldest), `-2` = next, … `level->index` maps a level to a
  vector index. (These are *not* real HP-15c levels — the machine has no
  numbered levels; ours are offset-from-X. Don't call them "HP levels".)
- Commands carry **real Clojure names**: `pop`/`drop`, `peek`, `count`,
  `reverse`, `get`/`nth`, `remove`, `chs`.
- **Bare ENTER (empty input) dups the top of the stack** (the HP ENTER key);
  no-op on an empty stack. ENTER with a typed command runs that command.
- A **rainbow button flash** on activation so someone on a screen share can see
  what fired. Keep animations visible and screen-shareable.
- **REPL-style two-step prompts** over inline errors: typing `get` turns the
  input box into `get? ` + a level placeholder, rather than erroring.

## Run & test

- Run the TUI: `bb src/tuiCalc.clj` (or `bb -m tuiCalc`).
- Tests: `bb test` — runs `test/tuiCalc_test.clj`, prints the `clojure.test`
  summary, exits 0 on pass / 1 on fail. **TESTING.md** has the manual-TUI check
  table and the babashka gotchas; read it before changing `src/tuiCalc.clj`.
- New machines: install babashka (`bb`), then `git clone`. The charm dep is
  pinned in `bb.edn` by git-sha and fetched automatically on first run.

## Open work

- **TODO-tuiCalc.md** lists known bugs from code review.
- More Clojure vector/stack ops are wanted (swap, min/max, abs, …).

## Rat Pack demo (Herdr)

Claude (Opus, the expensive one) is **dean**, head of the Rat Pack: dean manages
and delegates, and hands real work to **sammy** (MindRouter qwen3.5-122b) and
**jerry** (MindRouter gpt-oss-120b). All three are Claude Code panes in Herdr,
named via `herdr agent rename` (names clear when an agent restarts). Whenever dean prompts sammy or
jerry, start a background `herdr agent wait <name>` so their completion
notifies dean, and report their reply to the user immediately — don't wait to
be asked.

## Gotchas (detail in TESTING.md)

- **babashka 1.13 analyzes `defn` bodies at load time** → no forward
  references. Function *order* in `src/tuiCalc.clj` is load-bearing.
- The **entry guard** at the bottom of `src/tuiCalc.clj`
  `(when (= (str *file*) (System/getProperty "babashka.file")) (apply -main ...))`
  stops a test's `(require [tuiCalc])` from launching the TUI. Keep it.
- **This bb build evaluates source through sci's "analysis" pass**: use
  *qualified* symbols in tests (`t/is`, `tc/submit` — unqualified `deftest`/`is`
  don't resolve), never reference a `charm.*` function in a test (build state via
  `(first (tc/init))` instead), and don't load project code from `bb -e`.
