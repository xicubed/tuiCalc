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
  `reverse`, `get`/`nth`, `remove`, `chs`, `map`, `reduce`. Where Clojure
  has no name, use the HP-15c one: `swap` (x<>y), `linreg`/`lr` (L.R.).
- **Whole-stack ops are real map/reduce.** `linreg` treats the stack as
  points `x1 y1 x2 y2 ...`, maps each to `[1 x y xy x²]`, reduces with `+`
  (the HP's Σ registers), and leaves slope in Y, intercept in X.
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
- **Paren repair hook** (installed 2026-10-29; lives in `~/.claude`, so it does
  NOT come with a clone — redo it on a new machine). LLMs mismatch Clojure
  delimiters and then count parens by hand ("paren edit death loop");
  [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light)'s hook
  fixes them on every Write/Edit before they hit disk, zero tokens:
  1. `brew install babashka/brew/bbin`
  2. `bbin install https://github.com/bhauman/clojure-mcp-light.git --tag v0.2.2`
     (puts `clj-paren-repair-claude-hook` in `~/.local/bin`)
  3. In `~/.claude/settings.json` (user settings, so MindRouter `--settings`
     sessions get it too) add `/Users/mikel/.local/bin/clj-paren-repair-claude-hook`
     as a command hook on PreToolUse and PostToolUse (matcher `Write|Edit`)
     and on SessionEnd. **No `--cljfmt`** — that reformats whole files and
     puts unrelated lines in every diff. Merge with the existing herdr hooks.
  4. Test: write a `.clj` file missing a `)` — it lands on disk closed.
     Restart panes started before the change.
  Side effect: the hook auto-approves Clojure Write/Edit permission prompts.

## Open work

- **TODO-tuiCalc.md** lists known bugs from code review.
- Bug: the `map?`/`reduce?` placeholders say `4 +` / `10 +`, but the parser
  wants the op first (`map + 4`); typing `4 +` gives `Unknown: 4`. Fix the
  placeholder strings (or accept both orders). Good small crew round.
- More Clojure vector/stack ops are wanted (min/max, abs, …). Next idea: add
  `max min mod quot` to the `ops` table; they then work as binary keys AND in
  `map`/`reduce` for free (`reduce max`, `map max 0` clamps negatives).
- Maybe: make the two-step prompts look like an open form, `(map `, `(get `
  (trailing `?` means predicate in Clojure, and `map?` is a real fn).

## Crew demo (Herdr)

Claude (stock Anthropic, the expensive one) is **Monet** (Herdr name `monet`):
Monet manages and delegates, writes specs, and reviews every diff before
anything is committed. The crew are Claude Code sessions on MindRouter, each in
its own Herdr pane in the same tab, all with cwd `~/exp/fizz`. Naming: French,
after Claude Monet. `M.` (Monsieur) doubles as **M**indRouter, so every `m-`
pane is on MindRouter; Docteur is for docs (doc, M.D. = markdown). Docteur
Murphy / `docteur-m` nods to Doctor Murphy ("Doctor M") of *Blade Runner:
Black Lotus*, who works on replicants' memory programming — fitting, since
the docs (this file especially) are the memory every session boots with.

| Name | Herdr name | Model (`--model`) | Usual job |
|---|---|---|---|
| **Monet** | `monet` | `default` (Anthropic) | manage, spec, review correctness, commit |
| **Docteur Murphy** | `docteur-m` | `moonshotai/kimi-k3` (on trial) | writes docs (`*.md`) |
| **M. Automate** | `m-automate` | `qwen/qwen3.5-122b` | implements features in `src/` |
| **M. Testeur** | `m-testeur` | `qwen/qwen3.5-122b` | writes tests in `test/` from the spec |
| **M. Coureur** | `m-coureur` | `google/gemma-4-31b` | runs `bb test`, reports results |
| **M. Critique** | `m-critique` | `google/gemma-4-31b` | read-only review of style + idiom |

Herdr names must start with a lowercase letter and use only `a-z 0-9 - _`
(max 32), so no dots or spaces.

Workflow: one spec file in Monet's scratchpad, sent to M. Automate and
M. Testeur at the same time; each owns one file (shared checkout, no worktrees
for now). M. Testeur writes from the spec, not the code, and only ADDS tests.
M. Critique keeps findings short and ranked, and must respect project
constraints (e.g. function order in `src/tuiCalc.clj` is load-bearing). History:
the old reviewer on gpt-oss-120b edited unrelated lines, called a nonexistent
`Find` tool and missed real bugs, so give new or unproven agents read-only or
tightly scoped jobs, and review their diffs.

Whenever Monet prompts a crew member, start a background `herdr agent wait
<name>` so their completion notifies Monet, and report their reply to the user
immediately — don't wait to be asked.

**Models: launch with `--model`, never `/model`.** `/model` saves its pick as
the default in `~/.claude/settings.json`, which plain and MindRouter sessions
share, so a MindRouter pick breaks plain `claude` and vice versa. `--model`
only applies to that launch. `--model default` = Anthropic's current
recommended model.
Use full MindRouter model names (`qwen/qwen3.5-122b`), not its aliases
(`default-agent`, `default-llm-large`, …), which are easy to confuse with
Claude Code's own `default`.

### Reconstituting the crew from scratch

If Herdr was closed (not detached), the crew panes are gone. Names don't
survive either: Herdr clears a name when its agent exits. Monet rebuilds them:

1. Check `test "$HERDR_ENV" = 1` (Monet must itself be in a Herdr pane) and
   `herdr agent list`. Skip anyone still alive under their name.
2. Layout: three tabs, two panes side by side in each (more per tab gets too
   crowded on a screen share):
   `crew-1` = monet | m-automate, `crew-2` = m-testeur | m-coureur,
   `crew-3` = m-critique | docteur-m.
   `herdr tab create --workspace <ws> --label crew-2 --cwd ~/exp/fizz --no-focus`
   gives the left pane as `.result.root_pane.pane_id`; split it right with
   `herdr pane split --pane <id> --direction right --cwd ~/exp/fizz --no-focus`
   and read the new pane ID from `.result.pane.pane_id` (IDs change every time).
   Name Monet's own tab with `herdr tab rename <tab id> crew-1`.
3. Start each one with its `--model` from the table:
   `herdr agent start <name> --kind claude --pane <id> --timeout 60000 --
   --settings /Users/mikel/exp/fizz/.claude/settings.mindrouter.json --model <model>`
4. Have each say hi in one line so the user can see which window is whose.
5. Monet itself is started by the user: `claude --model default` (no
   `--settings`) in a Herdr pane, then `herdr agent rename <its pane id> monet`.

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
  The paren-repair hook (see Run & test) should prevent most of these; if a
  pane still hand-counts parens, check the hook is installed and restart it.
- Verify, don't trust, a crew reply: M. Testeur once replied with a list of
  tests he hadn't written. Ask for `git diff --stat <file>` in the reply, and
  check the diff before passing work on.
- Spec the test for every entry point: `submit` tests missed a broken
  `run-action` entry (button clicks), so specs now ask for a `tc/run-action`
  test whenever a button is added.
- Round order that worked (linreg, 2026-10-29): spec -> M. Automate + M.
  Testeur in parallel -> M. Coureur runs `bb test` -> M. Critique reviews ->
  one small cleanup round -> Monet reviews the diff -> commit.
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
