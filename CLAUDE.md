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
- **A successful `lr` opens a plot in the browser** — an old-style blueline
  drawing (blue ink on off-white graph paper, title block). The look was
  iterated with the user on 2026-10-29 and is deliberate: both axes always
  include 0,0, one shared scale so slopes compare at a glance (slope 1 =
  45°), square cells, square plot box. Extreme slopes give a thin sliver;
  accepted for now (a fallback to independent scales is the known fix).
  Side effects go in the `cmd` half of `[state cmd]` (charm runs it, tests
  never do); numbers display at 6 significant digits (`sig6`) while the
  stack keeps full precision.
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
- **Planned: Wolfram Engine ops** (design agreed with the user 2026-11-03;
  not built yet). tuiCalc stays on babashka, so no JLink: keep ONE
  long-lived `wolframscript` process (started quietly at launch) and pipe
  expressions to it. Seamless, no prefix: **Capitalized tokens go to Wolfram**
  (`Total`, `Mean`, `Sqrt`), lowercase stay Clojure (`map`, `reduce`). How many
  stack values a function takes comes from the engine, cached per function:
  `SyntaxInformation[f]` required-arg count + `Attributes[f]` Listable:
  2+ args -> that many from the top in Y, X order (`Mod`, `Power` like HP y^x);
  1 arg Listable -> **X only** (`Sqrt` = HP sqrt key); 1 arg not Listable ->
  **whole stack** as a list (`Total` = reduce +, `Mean`, `Sort`, `Accumulate`);
  no syntax info -> whole stack. `map Sqrt` / `reduce Plus` = elementwise /
  fold over the whole stack. Explicit form: `#` = whole stack, `#1` = X,
  `#2` = Y (`Total[#^2]`, `Mod[#2, #1]`). Number result replaces what was
  consumed; list result becomes the stack; symbolic result -> info line.
  Info line shows what ran (`Total[{1,2,3,4}] = 10`). A row of Wolfram
  buttons (to be chosen); hidden if no engine installed. **Graphics
  results** (`ListPlot`, `Histogram`, `Plot[Sin[x], {x,0,2Pi}]`) are
  exported to SVG and opened in the browser like the lr plot, stack
  unchanged. Keep Wolfram's own chart look but in the blueline palette
  (paper #f4f1e6, ink #1f4e9a, grid #7fa6d6), set once via SetOptions when
  the kernel starts; the hand-built lr plot stays as is.
  **Built 2026-11-03** (`src/tuiCalc.wl`). Two gotchas: the SVG has text as
  glyph outlines, so the *kernel* needs the Architects Daughter font
  installed (`~/Library/Fonts/ArchitectsDaughter-Regular.ttf`, from
  github.com/google/fonts `ofl/architectsdaughter`; redo on a new
  machine, else plots fall back to a plain sans); and SVG export draws grid
  lines 40% gray whatever `GridLinesStyle` says, so `tcExportSVG` recolors
  them in the file.
- **Phase 2 (the big one): symbolic stack values**, Wolfram doing the math
  (agreed direction 2026-11-03; build after the numeric bridge above).
  Stack items may be Wolfram expressions (kept as their InputForm text;
  plain numbers stay Clojure numbers). `'x` pushes the symbol x (Clojure
  quote; `x` is already the * alias); bracket input pushes an expression.
  Any op touching a symbolic item goes to Wolfram (`+` on x^2 and 2x ->
  Plus[...]), numbers-only ops stay native. Exact by default (1/3, Sqrt[2])
  with an `N` button for decimals. Same arity rules: [x^3 'x] D -> 3x^2;
  Expand/Factor/Simplify/Integrate/Solve; [x^2 'x] Plot -> parabola.
  Build expressions RPN-style, not typed infix: `'x 1 + 2 y^x` -> (x+1)^2
  (ops on a symbol stay symbolic). **3D results** (`Plot3D`,
  `PolyhedronData["RhombicHexecontahedron"]`) should get the blueline 3D
  look of `docs/spikey/` by default: faces hand-shaded ink -> grid blue ->
  paper by angle to a light, ambient-only Lighting, ink edges, transparent
  background (the user's favourite render, 2026-11-03). How to get there:
  (1) the recipe in `docs/spikey/spikey.wl` (`shade`) becomes a
  `tcBlueline3D[g]` in `src/tuiCalc.wl` that `tcCall` applies to any
  Graphics3D result. It shades polygon faces, which fits polyhedra. A
  smooth `Plot3D` surface has no faces to shade, so it needs a
  normal-based `ColorFunction` in the same ink -> paper blend. Check it
  on a real surface. (2) Graphics3D exports as a transparent PNG, the way
  spikey.png did, not as SVG. (3) Today's protocol only sends numbers, so
  `PolyhedronData["Icosahedron"]` needs phase 2's expression input
  (bracket input, string arguments). Still on offer: a rotatable
  version of the drawing (not started). **`fn`** (user's pick: Clojure name, can't
  clash with Capitalized Wolfram) turns the X expression into a function of
  its free variable, `x ↦ (x+1)^2` (= Wolfram Function[x, ...]) kept on the
  stack: apply it to a number, `map` it over the stack, Plot/D/Integrate it.
  **Quote = `'`, Clojure meaning, no mode:** `'` quotes the next thing.
  `'x` = symbol x; `2 3 '+` = `2 + 3` held unevaluated (Wolfram Hold);
  `eval` evaluates X (ReleaseHold). For clicks, a one-shot `[']` button
  quotes the next button pressed (like the HP-15c f-shift key), so you
  can't get stuck in a quote mode. Check the
  **Checked on the real engine (2026-11-03, Wolfram Engine 15.0 in
  ~/Applications, kernel path set via `wolframscript -configure`):** cold
  `wolframscript -code` call ~3-10 s, but one long-lived `wolframscript`
  process answers piped `Print[...]` lines in <1 ms, so keep one running.
  `Attributes[Evaluate[f]]` (it holds its arg!) gives Listable correctly
  (Sqrt/Sin/Abs/Log yes; Total/Mean no). `SyntaxInformation` patterns:
  `{_,_}` = 2 args (Mod, Plot, Integrate), `{{__}}`/`{{___}}` = a list
  (ListPlot, Histogram -> whole stack); `___`/`__`/missing are ambiguous ->
  small override table: Power 2 (HP y^x), BinomialCoefficient 2, D 2,
  Max/Min whole stack. Style plots with SetOptions at kernel start: passing
  options inline fails for HoldAll functions like Plot.
- **No engine / engine won't start (2026-11-05).** No `wolframscript` on
  PATH: `engine-missing` -> init shows `no-engine-notice` on the info
  line, the 4th row is one label "Wolfram functions: not installed (see
  README)", search is off. Installed but won't run: `start-wolfram`
  handshakes (waits up to 20 s for `TC\tREADY`, stderr merged into
  stdout) and keeps the engine's last line, so the user sees e.g.
  "Wolfram Engine failed to start: ... license error: single machine
  process limit reached." **Gotcha:** this Mac's engine license allows
  very few kernels at once: a running tuiCalc plus the Wolfram MCP
  kernel used them all (2026-11-05), so a second tuiCalc (or a test
  run of `start-wolfram` from Monet's shell) can't start one. README
  has the setup steps.
- **Phase 2 status (2026-11-05): round 1 built.** A stack item is a
  number or `(expr "text")` = `{:wl "text"}` (Wolfram InputForm).
  `'x` pushes a symbol (`parse-value`); `+ - * / y^x` CHS INV on an
  expr go to Wolfram via `tcApply[name, args]` (`wolfram-apply`; a
  late-bound `sym-apply` atom because `chs`/`execute-op` load first);
  numbers-only stays native. Symbolic results (SYM) now land on the
  stack (`-4 Sqrt` -> `2*I`). linreg/map/reduce say "needs numbers".
  Stack cells cut at 28 chars with `…`; `peek` shows the full text.
  **Plot:** `expr 'x [xmin xmax] Plot` -> `Plot[expr, {x, xmin, xmax}]`
  (default -10..10), `tcPlotArgs` in `src/tuiCalc.wl`; without a
  variable it says "Plot needs: expr 'x [xmin xmax]". Wolfram's default
  PlotRange clips peaks (Sin[x]/x drawn to ~0.42), left as is.
  **Round 2 to-dos found on the engine:** (1) Expand/Factor/Simplify
  are 1-arg non-Listable, so the arity rule gives them the whole stack
  and the result comes back wrapped in a list (`{1 + 2*x + x^2}`):
  make them take X (overrides). (2) `1/x` shows as `x^(-1)`
  (InputForm); want a nicer display form. (3) Exact constants become
  decimals (`2 'Pi *` -> 6.28319, not `2*Pi`): settle with exact
  numbers. Rounds 3 (fn, map with Wolfram fns, quote/eval, `[N]`) and
  4 (3D blueline) as planned above.
- Bug: the `map?`/`reduce?` placeholders say `4 +` / `10 +`, but the parser
  wants the op first (`map + 4`); typing `4 +` gives `Unknown: 4`. Fix the
  placeholder strings (or accept both orders). Good small crew round.
- More Clojure vector/stack ops are wanted (min/max, abs, …). Next idea: add
  `max min mod quot` to the `ops` table; they then work as binary keys AND in
  `map`/`reduce` for free (`reduce max`, `map max 0` clamps negatives).
- Maybe: make the two-step prompts look like an open form, `(map `, `(get `
  (trailing `?` means predicate in Clojure, and `map?` is a real fn).
- **Hierarchical menus + search** (agreed with the user 2026-11-05).
  Goal: reach all ~6,500 documented Wolfram functions from the TUI and,
  someday, a keypad or phone UI. **Built 2026-11-05:** the 4th button
  row (`menu-row`) is a folder row: `[Math ▸] [Stats ▸] [Lists ▸]
  [Plots ▸]`, one level at a time with `[◂ back]` (HP-48 soft-menu
  style), hand-picked in `wolfram-menu`. The row count stays 4, so
  clicks never move; `find-button-at`/`help-lines` take the state.
  Typing a menu function opens its folder so its flash is visible.
  **Type-to-filter:** a Capitalized word in the input turns the row
  into matches (`Mea` -> `[Mean] [MeanShift] ... +8`; menu functions
  first, then shortest), ENTER runs the exact name or first match,
  clicking a result (`run:<Name>`) runs it. Names come from
  `src/wolfram-names.txt` (6,457 documented functions; the regenerate
  command is in a comment above `wolfram-names`). A nil action in a
  row means a label (`+8`), never clickable or flashable.
  **Still to do:** (1) folders filled by the engine instead of by hand:
  `WolframLanguageData[f, "RelatedGuidePages"]` lists the doc guides
  a function belongs to (Mean -> Descriptive Statistics, ...), guides
  nest into sub-guides; skip the noise guides ("Summary of New
  Features in ...", GPU Computing ...); build the tree once, cache it,
  don't query at launch; ~8 top-level folders (Arithmetic, Stats,
  Lists, Algebra, Calculus, Plots, Number theory, Constants), nested
  via a `:menu-path`. (2) Paging (`[more ▸]`) for folders and results
  that don't fit one row.
- **Docs plan (user, 2026-11-10).** `docs/wolfram-protocol.md` (Docteur
  Murphy) is the developer reference for the Wolfram bridge (`wl-value`,
  `tcCall`/`tcApply`, reply format, `sym-apply`). The user manual is
  `docs/manual.md` (Docteur Murphy, 2026-11-10; moved from the repo
  root): what the calculator can do and how, organised by feature. It
  is a living draft: add to it after every feature round. Crew specs
  live in `.crew/specs/` (gitignored), not in the commit.
- **Future: exact numbers** (user agreed 2026-11-05, not scheduled).
  Use Clojure's exact numbers, not BigDecimal: integers + ratios
  (`(/ 1 3)` = `1/3`), which pair with Wolfram exact numbers as doubles
  pair with machine reals. BigDecimal can't hold 1/3 or Sqrt[2]. Steps:
  typing `10` pushes the integer 10; native `+ - * /` stay exact (decide
  divide-by-zero, today `ieee-div` gives Infinity); the Wolfram bridge
  sends and parses exact values as text; display rule for long
  integers/ratios; an `[N]` button converts X to a decimal. Part of
  phase 2's "exact by default". Stopgap in place: `wl-num` sends whole
  doubles below 1e15 as integers, so `Fibonacci[10.]` float noise
  (55.000000000000014) is gone.

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
- The paren hook **trusts indentation**: a continuation line indented past
  an already-closed `[` gets pulled inside it. `(fn [line] (swap! ...)` with
  the next line aligned under `(swap!` became `(fn [line (swap! ...) "..."])`
  in 6 test stubs (2026-11-03). Put a multi-line `fn` body on its own lines,
  two spaces past `(fn`. To repair a file the hook keeps mangling, edit it
  from Bash (the hook only runs on Write/Edit).
- Verify, don't trust, a crew reply: M. Testeur once replied with a list of
  tests he hadn't written. Ask for `git diff --stat <file>` in the reply, and
  check the diff before passing work on.
- Spec the test for every entry point: `submit` tests missed a broken
  `run-action` entry (button clicks), so specs now ask for a `tc/run-action`
  test whenever a button is added.
- **Look at visual output; tests can't see it.** For the plot, render
  sample HTML with headless Chrome (`--headless=new --screenshot=out.png
  --window-size=1000,1040`, plus `--virtual-time-budget=3000` so the web
  font loads) and read the PNG. Every plot round this way caught bugs the
  passing tests missed (y drawn downward, off-page axis title, empty minor
  grid, float-noise labels, overflowing title block).
- Users may talk to a crew pane directly. Panes can't reach Monet (their
  `SendMessage` only sees their own subagents), so the user relays, or
  Monet reads the pane with `herdr agent read`.
- Round order that worked (linreg, 2026-10-29): spec -> M. Automate + M.
  Testeur in parallel -> M. Coureur runs `bb test` -> M. Critique reviews ->
  one small cleanup round -> Monet reviews the diff -> commit.
- Tests that drive `update-fn` (key messages, the undo wrapper) are too much
  for M. Testeur: the undo round (2026-11-03) came back with tests after the
  runner's `System/exit`, `t/is` inside `let` bindings and a click
  off-by-one. Give M. Testeur pure-function tests; Monet writes the
  update-loop ones. New tests go BEFORE the `;; Run` section at the end.
- Keep crew rounds small: qwen does well on one well-specified change and
  bogs down on tangled multi-bug rounds (the map/reduce fix round took ~55 min).
- `herdr agent read` can't scroll far while an agent is working (alternate
  screen). For long output, have the agent write a file and reply with its path.
- Learned on the crew-up setup (other machine, 2026-10-31), brought back here:
  - **The index is Monet's.** Crew never stage or commit, not even
    `git add -N`. For a new file's stat they use
    `git diff --no-index --stat /dev/null <file>`. Decline `git add` and
    whole-disk searches (`find /`) when a pane asks for permission. Say why
    in the same prompt so it doesn't come back.
  - **Run the test gate unpiped before committing.** `bb test | tail -1 && git commit`
    commits even when tests fail, because the pipe's exit status comes from
    `tail`. Use `bb test > log && git commit`.
  - **Specs number their test cases** with hand-worked expected values;
    M. Testeur names each test after its case. A builder who thinks a test
    contradicts the spec says so in the reply and leaves the test alone.
  - **Note Monet's own mid-round edits** (a hand fix, a CLAUDE.md change)
    when handing the diff to M. Critique, or it flags them as out of scope.

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
