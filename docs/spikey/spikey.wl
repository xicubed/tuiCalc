(* The rhombic hexecontahedron ("Spikey") as a blueline drawing.
   Redraw:  wolframscript -file docs/spikey/spikey.wl
   Writes spikey.png next to this file; spikey.html shows it on a sheet.
   Faces are shaded by hand (no Wolfram lighting): each face's colour is
   blended ink -> grid blue -> paper by how directly it faces the light. *)
dir   = DirectoryName[$InputFileName];
paper = RGBColor["#f4f1e6"]; ink = RGBColor["#1f4e9a"]; grid = RGBColor["#7fa6d6"];
light = Normalize[{1., 1.6, 2.2}];

faces = MeshPrimitives[DiscretizeGraphics[PolyhedronData["RhombicHexecontahedron"]], 2];

shade[poly_] := Module[{c = Mean[poly[[1]]], n},
  n = Normalize[Cross[poly[[1, 2]] - poly[[1, 1]], poly[[1, 3]] - poly[[1, 1]]]];
  If[n . c < 0, n = -n];  (* point the normal outward *)
  Blend[{ink, grid, paper}, Clip[0.15 + 0.85 (n . light + 1)/2, {0, 1}]^1.3]];

g = Graphics3D[{EdgeForm[{ink, AbsoluteThickness[1.6]}], {shade[#], #} & /@ faces},
  Lighting -> {{"Ambient", White}}, Boxed -> False, Background -> None,
  ViewPoint -> {2, -2, 2}, SphericalRegion -> True,
  PlotRangePadding -> Scaled[0.06], ImageSize -> 820];

Export[FileNameJoin[{dir, "spikey.png"}], g, ImageResolution -> 144, Background -> None];
