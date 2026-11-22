#!/usr/bin/env python3
"""
Reconstructie Ommerschans stalcomplex – dakstudie v2.1
Bron: uitsluitend Hoofdgebouw-Ommerschans.pdf en de eerder goedgekeurde
ommerschans_stalcomplex_dak_v2.glb.

Wijzigt uitsluitend de triangulatie van het dakvlak aan de binnenplaatszijde:
- de vier lange dakvlakken behouden hun oorspronkelijke diagonaal;
- de vier afgeschuinde hoekovergangen krijgen de omgekeerde hip-/aansluitdiagonaal.
Buitenmuren, binnenplaatsmuren, buitenste dakvlak, vloer, maatvoering en
scene-transformaties worden ongewijzigd overgenomen uit het v2-GLB.

Gebruik:
  python build_roof_v2_1.py ommerschans_stalcomplex_dak_v2.glb output.glb
"""
import sys
import numpy as np
import trimesh

if len(sys.argv) != 3:
    raise SystemExit("Gebruik: python build_roof_v2_1.py input_v2.glb output_v2_1.glb")

source, destination = sys.argv[1], sys.argv[2]
scene = trimesh.load(source, force="scene")

if "geometry_3" not in scene.geometry:
    raise SystemExit("Verwacht dakvlak_binnenplaats (geometry_3) ontbreekt.")

old = scene.geometry["geometry_3"]
vertices = np.asarray(old.vertices).copy()
if vertices.shape != (16, 3):
    raise SystemExit(f"Onverwachte binnen-dakgeometrie: {vertices.shape}; model niet gewijzigd.")

faces = []
for i in range(8):
    j = (i + 1) % 8
    a, b, c, d = i, j, 8 + j, 8 + i
    if i % 2 == 0:
        faces.extend(((a, b, c), (a, c, d)))
    else:
        faces.extend(((a, b, d), (b, c, d)))

replacement = trimesh.Trimesh(vertices=vertices, faces=np.asarray(faces), process=False)
try:
    replacement.visual.vertex_colors = np.asarray(old.visual.vertex_colors).copy()
except Exception:
    pass

# Alleen het dakvlak aan de binnenplaatszijde wordt vervangen.
scene.geometry["geometry_3"] = replacement
scene.export(destination)
print(f"Geschreven: {destination}")
