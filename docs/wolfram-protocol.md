# Wolfram bridge protocol

How tuiCalc talks to the Wolfram Engine. Reference for anyone changing
that code; not a user manual. Source of truth: `src/tuiCalc.clj` (the
Clojure side) and `src/tuiCalc.wl` (the Wolfram side, protocol comment at
`src/tuiCalc.wl:1`).

## 1. Overview

- One long-lived `wolframscript` process is started at launch and loads
  `src/tuiCalc.wl` once with `Get`.
- One line out, one line back per call.
- Why: a cold `wolframscript -code` takes 3-10 s, but a line piped to a
  running process answers in under 1 ms.
- tuiCalc runs on babashka, so no JLink.

## 2. Startup and failure

`start-wolfram` (`src/tuiCalc.clj:574`):

- launches `wolframscript` with stderr merged into stdout (the reason an
  engine won't start - license, activation - is printed on stderr);
- sends `Get["<wl-path>"]; Print["TC\tREADY"]` and waits up to 20 s for a
  line containing `TC\tREADY`;
- on failure kills the process and returns `{:error reason}`, where reason
  is the last non-blank line the engine printed (e.g. the license-limit
  message), or `no answer (try: wolframscript -configure)`;
- on success it warms up graphics with a `ListPlot` call right away - the
  first plot otherwise takes ~10 s.

The fn it returns writes one line and reads lines until one contains
`TC\t`, returning the line from the marker on, or nil after 20 s.

`wolfram-sender` (`src/tuiCalc.clj:628`) wraps the startup in a future and
never blocks; before/without a working engine it answers with canned ERR
lines:

- still starting: `Wolfram is starting, try again in a moment`
- refused to start: `Wolfram Engine failed to start: <reason>`
- launch threw (e.g. binary lost after the PATH check):
  `Wolfram Engine failed to start (try: wolframscript -configure)`
- 20 s timeout: `Wolfram timed out`

No `wolframscript` on PATH at all (`fs/which`, `src/tuiCalc.clj:1653`):
`-main` sets `engine-missing` (`src/tuiCalc.clj:642`); init then hides the
Wolfram row and search and shows the launch notice `No Wolfram Engine:
native math only (see README)`.

## 3. Values: Clojure -> Wolfram

A stack item is a Clojure **number** or an **expression**, `{:wl "text"}` -
Wolfram InputForm kept as text (`expr`/`expr?`, `src/tuiCalc.clj:66`).
`'x` on the input line pushes the symbol x (`parse-value`,
`src/tuiCalc.clj:103`).

- `wl-num` (`src/tuiCalc.clj:650`): a whole double below 1e15 goes as an
  integer (`10.0` -> `10`) so Wolfram computes it exactly -
  `Fibonacci[10.]` is a float formula and gave `55.000000000000014`,
  `Fibonacci[10]` is 55. Other numbers go as Java's `Double.toString`
  text with `E` rewritten to Wolfram's `*^` (`1.0E10` -> `1.0*^10`,
  `2.5` -> `2.5`).
- `wl-value` (`src/tuiCalc.clj:658`): an expression goes as `"(" text
  ")"` - the parens keep `1 + x` grouped inside a bigger expression.

## 4. The two requests

Both are built by wrapping the call in `tcReply[...]`; `tcReply`
(`src/tuiCalc.wl:158`) prints the reply fields as one tab-separated line
(tabs/newlines inside fields become spaces).

### `tcCall` - a named function, via `wolfram-call-line` (`src/tuiCalc.clj:664`)

Any Capitalized name - typed, clicked, or picked from search. The WHOLE
stack goes in the request; the engine decides how many values the function
takes. Example, stack `[1.0 2.0 3.0 4.0]`, name `Total`:

```
tcReply[tcCall["Total", {1, 2, 3, 4}]]
```

`tcCall` (`src/tuiCalc.wl:126`) rejects unknown names (`tcKnownQ`,
`src/tuiCalc.wl:69`: Capitalized and in `System``) with `ERR Unknown:
<name>`, then asks `tcArity` (`src/tuiCalc.wl:56`) how many stack values
`k` to pass:

| Case | k | Args | Examples |
|---|---|---|---|
| In `$tcOverrides` (`src/tuiCalc.wl:49`) | given | that many from the top | Power, BinomialCoefficient, D -> 2; Max, Min -> All |
| `Constant` attribute (Pi) | 0 | none, k = 0 | Pi |
| 2+ required args in `SyntaxInformation` | that many | top k, in Y, X order | Mod, Integrate |
| 1 required arg, `Listable` | 1 | X only (HP-style key) | Sqrt, Sin, Abs |
| anything else | All | the whole stack as one list | Total, Mean, Sort |

Special case: for `Plot`, `tcPlotArgs` (`src/tuiCalc.wl:119`) reads the
range off the stack: `expr 'x xmin xmax` -> `Plot[expr, {x, xmin, xmax}]`
(k 4); `expr 'x` -> range -10..10 (k 2); otherwise
`ERR Plot needs: expr 'x [xmin xmax]`. k = 0 needs no values; k = All on
an empty stack -> `ERR Stack empty`; n < k -> `ERR Need <k> values`.

### `tcApply` - arithmetic on an expression, via `wolfram-apply-line` (`src/tuiCalc.clj:670`)

Used when a calculator op touches an expression. Sends exactly the args;
no arity lookup. Example, `+` on `'x` and `1`:

```
tcReply[tcApply["Plus", {(x), 1}]]
```

Op mapping (`op->wl`, `src/tuiCalc.clj:118`, plus CHS/INV):

| Calculator op | Wolfram call |
|---|---|
| `+` | Plus [Y, X] |
| `-` | Subtract [Y, X] |
| `*`, `x` | Times [Y, X] |
| `/` | Divide [Y, X] |
| `^`, `pow` | Power [Y, X] |
| `chs` | Minus [X] |
| `inv` | Divide [1, X] |

`chs` (`src/tuiCalc.clj:154`) and `inv` (`src/tuiCalc.clj:166`) call
`tcApply` only when X is an expression; `execute-op`
(`src/tuiCalc.clj:132`) only when Y or X is. Numbers-only arithmetic
never goes to Wolfram.

## 5. The reply

One line, tab-separated (`src/tuiCalc.wl:12`). The kernel prints its
`In[n]:=` prompt in front of the reply on the same line, so the reader
looks for the `TC\t` marker anywhere in the line. Meaning of `k`: how
many values were taken from the top of the stack.

| Format | Meaning | Example |
|---|---|---|
| `TC OK <k> NUM <number> <info>` | one real number | `TC	OK	4	NUM	10.	Total[{1, 2, 3, 4}] = 10` |
| `TC OK <k> LIST <num> ... <info>` | zero or more numbers, space-separated | `TC	OK	3	LIST	1. 2. 3.	Sort[{3, 1, 2}] = {1, 2, 3}` |
| `TC OK <k> SYM <InputForm text> <info>` | one symbolic result; k = args consumed | `TC	OK	2	SYM	1 + x	Plus[x, 1] = 1 + x` |
| `TC OK 0 GFX <path to .svg> <info>` | graphics: path to an exported SVG | `TC	OK	0	GFX	/var/tuiCalc-wolfram.svg	ListPlot[{1, 4, 9}]` |
| `TC ERR <message>` | failure | `TC	ERR	Need 2 values` |

Numbers inside are CForm (`3.`, `1.e10`, `-2.5e-7`), which Clojure's
`parse-double` reads. `info` is the human form for the info line, built
by `tcShow` (`src/tuiCalc.wl:80`): `Total[{1, 2, 3, 4}] = 10`.

`parse-wolfram-reply` (`src/tuiCalc.clj:676`) maps a line to
`{:k :kind :values :info}` - or `{:path :info}` with `:k 0` for GFX - and
anything malformed or missing to `{:error "Wolfram: bad reply"}`.

## 6. What the calculator does with it

`wolfram-op` (`src/tuiCalc.clj:706`), for `tcCall`:

- no engine -> status `No Wolfram Engine (install wolframscript)`;
- NaN or Infinity anywhere in the stack -> status `Wolfram needs finite
  numbers`, no request sent;
- NUM/LIST/SYM -> replaces the top `k` stack values with `:values`, sets
  the info line to `:info`;
- GFX -> stack untouched; an HTML page showing the SVG
  (`wolfram-plot-html`, `src/tuiCalc.clj:697`) opens in the browser via
  the `cmd` half of `[state cmd]`;
- errors go to the status line, stack unchanged.

`wolfram-apply` (`src/tuiCalc.clj:731`), for `tcApply`: replaces exactly
the `k` args it sent (it ignores the reply's `k`), sets the info line;
errors go to the status line, stack unchanged.

## 7. The `sym-apply` hook

Babashka 1.13 analyzes `defn` bodies at load time, so there are no
forward references - but `execute-op`, `chs` and `inv`
(`src/tuiCalc.clj:132`, `:154`, `:166`) are defined before
`wolfram-apply` (`src/tuiCalc.clj:731`). They call through an atom
instead: `sym-apply` (`src/tuiCalc.clj:128`), filled in right after
`wolfram-apply` with `(reset! sym-apply wolfram-apply)`
(`src/tuiCalc.clj:748`). Signature: `(fn [state name args k])`, same as
`wolfram-apply`.

## 8. Worked example: `'x` ENTER, `1`, `+`

1. `'x` pushes `(expr "x")`; `1` pushes `1.0`.
   Stack: `[{:wl "x"} 1.0]` (X = 1.0).
2. `+`: `execute-op` sees an expression, calls `(wolfram-apply state
   "Plus" [{:wl "x"} 1.0] 2)`. The line sent:

   ```
   tcReply[tcApply["Plus", {(x), 1}]]
   ```

3. The engine replies:

   ```
   TC	OK	2	SYM	1 + x	Plus[x, 1] = 1 + x
   ```

4. The top 2 values are replaced by the SYM value; info line set.
   Stack: `[{:wl "1 + x"}]`, info: `Plus[x, 1] = 1 + x`.

(Same data as `sym-08-execute-op-expr`, `test/tuiCalc_test.clj:1277`.)

## 9. Testing without the engine

Never start a real kernel in tests: the free license allows very few
kernels at once (a second tuiCalc holding one can already block yours).
Instead put a stub fn in `:wolfram` that records the line and returns a
canned reply - see the `sym-` tests, e.g.
`(wolfram-stub "TC\tOK\t4\tNUM\t10.\tTotal[{1, 2, 3, 4}] = 10")`
(`test/tuiCalc_test.clj:885`, `:927`), or the inline stubs in
`sym-08-execute-op-expr` and `sym-17-plot-expression`
(`test/tuiCalc_test.clj:1277`, `:1372`).

## 10. Known gaps (phase 2 round 2)

- `Expand`/`Factor`/`Simplify` are 1-arg non-Listable, so the arity rule
  gives them the whole stack and the result comes back list-wrapped
  (`{1 + 2*x + x^2}`); they should take X only (overrides).
- `1/x` displays as `x^(-1)` (InputForm); a nicer display form is wanted.
- Exact constants come back as decimals: `2 'Pi *` -> `6.28319`, not
  `2*Pi`, because replies are numbers in CForm; to be settled with exact
  numbers (see CLAUDE.md "Future: exact numbers").
