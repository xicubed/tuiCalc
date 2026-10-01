# tuiCalc — testing

Two ways to verify a change: a **unit test** (fast, runs headless) and a
**manual TUI check** (the real thing, in your terminal). Do both for a change
to `src/tuiCalc.clj`.

## 1. Unit tests

```sh
bb test
```

- Runs `test/tuiCalc_test.clj` via the `test` task in `bb.edn`.
- Exits **0 on pass, 1 on fail** (CI-safe) and prints the `clojure.test`
  summary to the terminal.
- You can also run the file directly: `bb test/tuiCalc_test.clj`.
- Task internals (why it looks the way it does): the task spawns the test
  file with `clojure.java.shell/sh "bb" "test/tuiCalc_test.clj"` — `sh`
  captures the child's output instead of streaming it, so the task re-prints
  it — and then `System/exit` with the child's code. In this bb build,
  `exec` inside task bodies mangles its arguments and `babashka.tasks/log`
  hits the same sci "analysis" wall as other unqualified/external refs, so
  plain `sh` + `println` + `System/exit` is the combination that works.

The suite covers the pure engine: `level->index`, `submit` (numbers,
constants, operators, stack commands, `get`/`remove` prompts), bare-ENTER
dup, `ieee-div`, parsers, and `flash-for-action`.

### Adding a test

Add a `(t/deftest ...)` block to `test/tuiCalc_test.clj` using the helpers
already there:

```clojure
(t/deftest my-new-test
  (t/is (= [3 7 7] (submit->stack "" [3 7]))))    ; bare ENTER dups
```

- `submit->stack "txt" [seed...]` — type `txt`, submit, return the stack.
- `submit->state "txt" [seed...]` — same, but returns the whole state so you
  can assert `:status` / `:info` / `:pending`.
- `tc/<fn>` — call any public `tuiCalc` function directly.

Then run `bb test` and confirm the count went up and it's still green.

### ⚠️ Gotchas for THIS babashka build (cost hours to find)

This bb (v1.13.220) evaluates source files through sci's "analysis" pass,
which breaks a few things that work in vanilla Clojure/JVM babashka. If a
test errors with `{:type sci/error ... Unable to resolve symbol: ...}`, you
are hitting one of these:

1. **Use QUALIFIED symbols for anything from a required namespace.**
   Unqualified `deftest` / `is` (i.e. `(:require [clojure.test])` without an
   alias, or a `:refer`) do NOT resolve. Write `(t/deftest ...)`, `(t/is ...)`
   with `(:require [clojure.test :as t])`. Same rule for every other ns:
   `(tc/submit ...)`, not a bare refer.

2. **Never reference a `charm.*` function in the test file.** Calling
   `text-input/new` (or any `charm.../...` symbol) in `test/` trips the same
   sci wall — `Unable to resolve symbol: text-input/new`. Build state via
   `(first (tc/init))` instead; tuiCalc constructs its own text-input
   component internally, so the test never touches charm. This is why `fresh`
   comes from `tc/init`, not from a direct charm call.

3. **`bb -e "..."` is sci too.** Don't try to load the project from `bb -e`.
   Always `bb <file>` or `bb -m <ns>`. (`bb -e '(require ...)'` can't see the
   project's `:paths` and runs in a sandboxed REPL — misleading.)

4. **The auto-start guard is load-bearing.** `src/tuiCalc.clj` ends in
   `(when (= (str *file*) (System/getProperty "babashka.file")) (apply -main ...))`.
   This makes the TUI launch only when the file is the entry script, not when
   a test `require`s it. Without it, loading `test/tuiCalc_test.clj` launches
   the TUI and the run hangs / hijacks your terminal. Keep the guard; keep the
   `(require [tuiCalc ...])` in tests as-is.

5. **Number types.** Typed input always parses to a **double** (`parse-double`),
   so `submit->stack "42"` gives `[42.0]`. Operators follow Clojure's number
   rules on whatever is on the stack, so seed test stacks with doubles
   (`[3.0 6.0]`) to match how the app actually feeds the stack. Don't assert
   `[42]` where the app produces `[42.0]` (or `[9]` where it yields `[9.0]`).

6. **bb's `clojure.test` is stripped.** No `t/add-report`, no `t/all-tests` —
   the "one line per test" output at the bottom of the test file is a small
   hand-rolled runner, and it exists precisely because of this. The pieces it
   relies on, each verified against bb 1.13.220:

   - Discover test vars via
     `(->> (ns-publics 'the-ns) (vals) (filter (comp :test meta)))` —
     `deftest` tags its var's meta with `:test`.
   - `name` **does not work** on a sci `Var` (ClassCastException) — read the
     name from `(get (meta var) :name)`.
   - `t/run-test-var` returns a summary map whose `:fail`/`:error` counts tell
     you whether that test passed; it is silent about the test *body*'s
     output unless you capture it.
   - `clojure.test` writes the `FAIL in (...)` detail to `t/*test-out*`,
     **not** `*out*`. To capture it, bind `t/*test-out*` to a
     `java.io.StringWriter` around the call — binding `*out*` captures
     nothing (this tripped me up; the per-test runner's `run-one` does this).

   Don't replace the hand-rolled runner with `t/run-tests` alone: that only
   prints the final summary, so a passing run shows no per-test lines and a
   screen-share viewer sees nothing until the very end.

## 2. Manual TUI check

The unit tests can't see the rendering, the button flash, or the mouse. For
those, run the real app:

```sh
bb src/tuiCalc.clj        # or: bb -m tuiCalc
```

Drive it from the keyboard (every action flashes its button, which is the
point of the screen-share animation):

| Action | How | Expect |
|---|---|---|
| Push a number | type `7` then **Enter** | `7.0` appears, `[ENTER]` flashes |
| **Bare ENTER dups** | push a value, then press **Enter** with the box empty | the top value is copied; `[ENTER]` flashes |
| Add | `4` Enter, `5` Enter, click `[+]` (or type `+`) | `9.0` |
| Sign | type `chs` Enter, or `^s` | top flips sign, `[CHS]` flashes |
| Drop top | `^x` or `drop` Enter | top removed |
| Peek | `^t` or `peek` Enter | `peek = X` on the info line, stack unchanged |
| Count | `^n` or `count` Enter | `count = N` |
| Reverse | `^r` or `reverse` Enter | stack order flips |
| Get level | `get` Enter → type level → Enter (or `get 0`) | copies that level; `[GET]` flashes |
| Remove level | `remove` Enter → level → Enter | removes that level |
| Quit | `q` Enter | exits |

**Mouse:** click any button in the two help rows (`[+]` … `[q]`). Clicking a
different button while a `get?`/`remove?` prompt is open abandons the prompt.

**What to eyeball for a change:**
- New values appear on the **bottom row** of the box — that's the
  stack-top / X register (HP layout). The box-top value is the *oldest*.
- The right button **flashes** when you trigger it (keyboard *and* mouse).
- The **info line** (`peek`/`count`) shows and the help rows *don't move* —
  a miscounted layout line breaks all click hit-testing (see
  `help-row0` and the render-position note in `src/tuiCalc.clj`).
- Errors land on **row 0** of the help area (e.g. `Need two values`), pushing
  the buttons down; they clear on the next action.

Tip: if you want to check the *rendered* click targets match `help-row0`,
resize the terminal so the help rows sit where the code expects, then click
each button. A button that doesn't respond means a row/column is off.
