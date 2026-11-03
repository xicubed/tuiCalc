(* tuiCalc's Wolfram side. Loaded once (Get) into the one long-lived
   wolframscript process that tuiCalc starts at launch. tuiCalc sends one
   line per call:

     tcReply[tcCall["Total", {1., 2., 3., 4.}]]

   and reads back ONE line that starts with "TC\t" (the kernel puts its
   In[n]:= prompt in front of it on the same line, so look for the marker
   anywhere in the line). Tab-separated fields:

     TC  OK   <k>  NUM   <number>              <info>
     TC  OK   <k>  LIST  <num> <num> ...       <info>   (zero or more)
     TC  OK   0    SYM   <InputForm text>      <info>
     TC  OK   0    GFX   <path to .svg>        <info>
     TC  ERR  <message>

   k = how many values were taken from the top of the stack; tuiCalc
   replaces them with the NUM/LIST values. SYM and GFX leave the stack
   alone. Numbers are in CForm, which Clojure's parse-double reads
   ("3.", "1.4142135623730951", "1.e10", "-2.5e-7"). *)

(* One reply = one line: never wrap long output. *)
SetOptions[$Output, PageWidth -> Infinity];

(* Blueline look for every plot, like the lr plot: blue ink on off-white
   graph paper. Set once here; inline options fail for HoldAll functions
   like Plot. The font name is resolved by the browser page that shows
   the SVG (it loads Architects Daughter), so the SVG is inlined there. *)
$tcPaper = RGBColor["#f4f1e6"]; $tcInk = RGBColor["#1f4e9a"];
$tcGrid = RGBColor["#7fa6d6"];
$tcPlotOptions = {
  Background -> $tcPaper, ImageSize -> 720, AspectRatio -> 1/GoldenRatio,
  Frame -> True, Axes -> False,
  FrameStyle -> Directive[$tcInk, AbsoluteThickness[1.5]],
  FrameTicksStyle -> Directive[$tcInk, 13],
  GridLines -> Automatic,
  BaseStyle -> {FontFamily -> "Architects Daughter", FontColor -> $tcInk, 14},
  LabelStyle -> {FontFamily -> "Architects Daughter", FontColor -> $tcInk, 14},
  PlotRangePadding -> Scaled[0.05], ImagePadding -> {{60, 20}, {45, 15}}};
Scan[SetOptions[#, Sequence @@ $tcPlotOptions, PlotStyle -> $tcInk] &,
  {ListPlot, ListLinePlot, Plot, ParametricPlot, DateListPlot}];
SetOptions[ListPlot, PlotMarkers -> {Graphics[{$tcInk, EdgeForm[{$tcPaper, AbsoluteThickness[2]}], Disk[]}], 0.035}];
Scan[SetOptions[#, Sequence @@ $tcPlotOptions,
    ChartStyle -> Directive[Opacity[0.35], $tcInk, EdgeForm[{$tcInk, AbsoluteThickness[1.5]}]]] &,
  {Histogram, BarChart}];

$tcOverrides = <|
  "Power" -> 2, "BinomialCoefficient" -> 2, "D" -> 2,
  "Max" -> All, "Min" -> All|>;

(* How many stack values a function takes: 0 (a constant like Pi),
   n >= 1 (from the top, in Y, X order), or All (the whole stack as a
   list). Cached per name. *)
tcArity[name_String] := tcArity[name] = Module[{f = Symbol[name], pat, req},
  Which[
    KeyExistsQ[$tcOverrides, name], $tcOverrides[name],
    MemberQ[Attributes[Evaluate[f]], Constant], 0,
    True,
    pat = Lookup[SyntaxInformation[f], "ArgumentsPattern", None];
    req = If[ListQ[pat],
      LengthWhile[pat, MatchQ[#, Verbatim[_]] &], 0];
    Which[
      req >= 2, req,
      req == 1 && MemberQ[Attributes[Evaluate[f]], Listable], 1,
      True, All]]]

tcKnownQ[name_String] :=
  StringMatchQ[name, RegularExpression["[A-Z][A-Za-z0-9]*"]] &&
  Names["System`" <> name] =!= {}

tcRealQ[x_] := NumericQ[x] && Im[x] == 0

tcNum[x_] := ToString[CForm[N[Re[x]]]]

(* Short human form for the info line: whole numbers without ".",
   otherwise 6 significant digits like the stack display; CForm keeps it on one
   line ("2.33333", "1.e20"). *)
tcShow[x_?tcRealQ] := With[{r = N[x]},
  Which[
    Abs[r] < 10^15 && r == Round[r], ToString[Round[r]],
    True, ToString[CForm[N[Round[r, 10^(Floor[Log10[Abs[r]]] - 5)]]]]]]
tcShow[l_List] := "{" <> StringRiffle[tcShow /@ l, ", "] <> "}"
tcShow[e_] := ToString[e, InputForm]

tcGraphicsQ[g_] := MatchQ[g, _Graphics | _Graphics3D | _Legended]

(* SVG export draws grid lines 40% gray whatever GridLinesStyle says, so
   recolor them to the blueline grid blue (#7fa6d6) afterwards. *)
tcExportSVG[path_String, g_] := (
  Export[path, g, "SVG"];
  Export[path, StringReplace[Import[path, "Text"],
    "stroke:rgb(50%,50%,50%);stroke-opacity:0.4" ->
      "stroke:rgb(49.8%,65.1%,83.9%);stroke-opacity:0.8"], "Text"])

tcCall[name_String, stack_List] := Catch[Module[
  {k, f, args, res, n = Length[stack], shown},
  If[! tcKnownQ[name], Throw[{"ERR", "Unknown: " <> name}]];
  If[! VectorQ[stack, tcRealQ], Throw[{"ERR", "Stack must be numbers"}]];
  k = tcArity[name];
  f = Symbol[name];
  Which[
    k === 0, args = {}; k = 0,
    k === All,
      If[n == 0, Throw[{"ERR", "Stack empty"}]];
      args = {stack}; k = n,
    n < k, Throw[{"ERR", "Need " <> ToString[k] <> " values"}],
    True, args = Take[stack, -k]];
  shown = If[k === 0 && args === {}, name,
    name <> "[" <> StringRiffle[tcShow /@ args, ", "] <> "]"];
  res = Quiet@Check[TimeConstrained[
      If[k === 0, f, f @@ args], 10, $Aborted], $Failed];
  Which[
    res === $Failed || res === $Aborted,
      {"ERR", shown <> " failed"},
    tcGraphicsQ[res],
      With[{path = FileNameJoin[{$TemporaryDirectory, "tuiCalc-wolfram.svg"}]},
        tcExportSVG[path, res];
        {"OK", 0, "GFX", path, shown}],
    tcRealQ[res],
      {"OK", k, "NUM", tcNum[res], shown <> " = " <> tcShow[res]},
    VectorQ[res, tcRealQ],
      {"OK", k, "LIST", StringRiffle[tcNum /@ res, " "],
        shown <> " = " <> tcShow[res]},
    True,
      {"OK", 0, "SYM", tcShow[res], shown <> " = " <> tcShow[res]}]]]

(* Print the reply as one line. Tabs/newlines inside fields become
   spaces so the line can't break. *)
tcReply[fields_List] := Print[StringRiffle[
  Prepend[StringReplace[ToString[#], {"\t" -> " ", "\n" -> " "}] & /@ fields, "TC"],
  "\t"]]
