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
- More Clojure vector/stack ops are wanted (min/max, abs, …). Next idea: add
  `max min mod quot` to the `ops` table; they then work as binary keys AND in
  `map`/`reduce` for free (`reduce max`, `map max 0` clamps negatives).
- Maybe: make the two-step prompts look like an open form, `(map `, `(get `
  (trailing `?` means predicate in Clojure, and `map?` is a real fn).

## Rat Pack demo (Herdr)

Claude (Opus, the expensive one, stock Anthropic) is **dean**, head of the Rat
Pack: dean manages and delegates, writes specs, and reviews every diff before
anything is committed. The crew are Claude Code sessions on MindRouter, each in
its own Herdr pane in the same tab, all with cwd `~/exp/fizz`:

| Name | Model (`--model`) | Usual job |
|---|---|---|
| **sammy** | `default-llm-large` (qwen3.5-122b) | implements features in `src/` |
| **peter** | `default-llm-large` (qwen3.5-122b) | writes + runs tests in `test/` |
| **jerry** | `google/gemma-4-31b` | read-only review (on trial) |

Workflow: one spec file in dean's scratchpad, sent to the builder and the tester
at the same time; each owns one file (shared checkout, no worktrees for now).
The tester writes from the spec, not the code, and only ADDS tests. On
gpt-oss-120b jerry edited unrelated lines, called a nonexistent `Find` tool,
and missed real bugs in review, so he was switched to gemma on 2026-10-15. Give
new or unproven agents read-only or tightly scoped jobs, and review their
diffs.

Whenever dean prompts a crew member, start a background `herdr agent wait
<name>` so their completion notifies dean, and report their reply to the user
immediately — don't wait to be asked.

### Reconstituting the Rat Pack from scratch

If Herdr was closed (not detached), the crew panes are gone. Names don't
survive either: Herdr clears a name when its agent exits. dean rebuilds them:

1. Check `test "$HERDR_ENV" = 1` (dean must itself be in a Herdr pane) and
   `herdr agent list`. Skip anyone still alive under their name.
2. Layout: a 2x2 grid in dean's tab (splits only go right or down). Split
   dean's pane right for jerry, split dean's pane down for sammy, split jerry's
   pane down for peter. Use
   `herdr pane split --pane <id> --direction <dir> --cwd ~/exp/fizz --no-focus`
   and read each new pane ID from `.result.pane.pane_id` (IDs change every time).
3. Start each one, always passing `--model` (any `/model` switch rewrites the
   saved default for new sessions):
   `herdr agent start <name> --kind claude --pane <id> --timeout 60000 --
   --settings /Users/mikel/exp/fizz/.claude/settings.mindrouter.json --model <model>`
4. Have each say hi in one line so the user can see which window is whose.
5. dean itself is started by the user: plain `claude` (no `--settings`) in a
   Herdr pane, then `herdr agent rename <its pane id> dean`.

Quirks seen so far:
- A prompt sometimes lands in the input box without submitting. A few seconds
  after `herdr agent prompt`, check `herdr agent get <name>`; if still `idle`,
  `herdr agent send-keys <name> enter`.
- Claude Code may show a one-off notice dialog (e.g. auto-mode billing on
  MindRouter) that swallows the next prompt. Ask the user before dismissing it,
  then resend the prompt.
- Unbalanced parens: don't count by hand. Locate them with
  `bb -e "(require '[edamame.core :as e]) (e/parse-string-all (slurp \"src/tuiCalc.clj\") {:all true}) (println :ok)"`
  (parses only, doesn't load; needs `{:all true}` for the regex literals).
  clj-kondo / clojure-mcp-light paren-repair hook are candidates to install.
- Keep crew rounds small: qwen does well on one well-specified change and
  bogs down on tangled multi-bug rounds (the map/reduce fix round took ~55 min).
- `herdr agent read` can't scroll far while an agent is working (alternate
  screen). For long output, have the agent write a file and reply with its path.

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
