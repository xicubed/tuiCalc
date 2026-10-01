# tuiCalc

Little experiment in an RPN Babashka calculator — the stack is a plain
Clojure vector and the buttons/commands are Clojure collection ops on it,
with HP-15c ergonomics (levels, ENTER to dup) and a rainbow button flash so
it's easy to follow on a screen share.

This has been a test of different LLM models.

## Run

```
bb ./src/tuiCalc.clj
```

(or `bb -m tuiCalc`)

The TUI renders a stack box, a `> ` input line, and two rows of clickable
buttons.

## Using it

Numbers and commands go into the input line; **Enter** submits. The stack
grows **upward** — the bottom row is the top of the stack (HP's X register).

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
| Copy from a level | `get` + Enter, type a level, Enter (or `get 0`) | copies that level to the top (`0`=top, `1`=below, `-1`=bottom) |
| Remove a level | `remove` + Enter, level, Enter | removes that level |
| Clear | `clear` + Enter, or `^l` | empty stack |
| Quit | `q` + Enter | exit |

Notes:

- **Bare ENTER dups the top value** (no-op on an empty stack) — the HP ENTER
  key. Enter *with text typed* runs that command instead.
- **Mouse:** every button in the help rows is clickable; clicking any button
  flashes it so a screen-share viewer sees what ran.
- **Two-step prompts:** typing just `get` or `remove` turns the input line
  into `get? `/`remove? ` — the next integer you type is the level. Pressing
  Escape or clicking another button cancels.
- Errors (e.g. `Need two values`) show above the buttons and clear on the
  next action.

## Tests

```
bb test
```

Runs the unit suite (`test/tuiCalc_test.clj`), prints the `clojure.test`
summary, exits non-zero on failure. See **TESTING.md** for how to add tests
and the babashka gotchas — read it before changing `src/tuiCalc.clj`.

## TODO

More Clojure stack/vector manipulation functions to get a playground feel for
how they work — not to mention map/reduce :>

Open bugs from code review: **TODO-tuiCalc.md**.

## Notes for contributors (and for Claude)

**CLAUDE.md** holds the design decisions and repo conventions; it's loaded
into Claude Code sessions automatically.
