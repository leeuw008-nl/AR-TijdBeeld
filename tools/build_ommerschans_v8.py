from pathlib import Path
import json
import math
import numpy as np
import trimesh

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "historische-bronnen/ommerschans/ommerschans_stalcomplex_dak_v2.glb"
OUTPUT = ROOT / "app/src/main/assets/ommerschans.glb"
META = ROOT / "historische-bronnen/ommerschans/ommerschans_stalcomplex_v8.json"

scene = trimesh.load(SOURCE, force="scene")
outer_long = 72.0
outer_chamfer = outer_long * (1.85 / 25.7)
inner_long = outer_long * (22.5 / 25.7)
inner_chamfer = outer_long * (0.25 / 25.7)

def octagon(long_side, chamfer_side):
    c = chamfer_side / math.sqrt(2)
    h = (long_side + 2 * c) / 2
    return np.array([
        [-h + c, -h], [h - c, -h], [h, -h + c], [h, h - c],
        [h - c, h], [-h + c, h], [-h, h - c], [-h, -h + c]
    ], dtype=float)

outer = octagon(outer_long, outer_chamfer)
inner = octagon(inner_long, inner_chamfer)
ridge = (outer + inner) / 2

# Retain the approved octagonal footprint and open courtyard.
for name, polygon in [("geometry_0", outer), ("geometry_1", inner)]:
    geometry = scene.geometry[name]
    for index, point in enumerate(polygon):
        geometry.vertices[2 * index, :2] = point
        geometry.vertices[2 * index + 1, :2] = point

floor = scene.geometry["geometry_4"]
half_inner = (inner_long + 2 * (inner_chamfer / math.sqrt(2))) / 2
for vertex in floor.vertices:
    vertex[0] = np.sign(vertex[0]) * half_inner
    vertex[1] = np.sign(vertex[1]) * half_inner

section_width = abs(outer[0, 1] - inner[0, 1])
total_height = section_width * 4 / 5.3
wall_height = total_height * 0.4457831325301205
for name in ["geometry_0", "geometry_1"]:
    scene.geometry[name].vertices[:, 2] *= wall_height / 4.4
for name in ["geometry_2", "geometry_3"]:
    scene.geometry.pop(name, None)

wall_material = trimesh.visual.material.SimpleMaterial(color=[194, 181, 155, 255])
roof_material = trimesh.visual.material.SimpleMaterial(color=[105, 78, 58, 255])
for name in ["geometry_0", "geometry_1"]:
    scene.geometry[name].visual = trimesh.visual.TextureVisuals(material=wall_material)

def add_surface(name, points, material):
    mesh = trimesh.Trimesh(
        vertices=np.asarray(points, dtype=float),
        faces=[[0, 1, 2], [0, 2, 3]],
        process=True
    )
    mesh.visual = trimesh.visual.TextureVisuals(material=material)
    scene.add_geometry(mesh, geom_name=name, node_name=name)

# Four long gabled wings; chamfer-edge transition planes join the roof at all corners.
for index in range(8):
    next_index = (index + 1) % 8
    outer_start = np.r_[outer[index], wall_height]
    outer_end = np.r_[outer[next_index], wall_height]
    inner_start = np.r_[inner[index], wall_height]
    inner_end = np.r_[inner[next_index], wall_height]
    ridge_start = np.r_[ridge[index], total_height]
    ridge_end = np.r_[ridge[next_index], total_height]
    edge_type = "wing" if index % 2 == 0 else "corner-transition"
    add_surface(f"{edge_type}-{index + 1}-outer-slope",
                [outer_start, outer_end, ridge_end, ridge_start], roof_material)
    add_surface(f"{edge_type}-{index + 1}-courtyard-slope",
                [ridge_start, ridge_end, inner_end, inner_start], roof_material)

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
scene.export(OUTPUT)
metadata = {
    "version": "v8-doorlopende-dakvlakken-hoekverbindingen",
    "based_on": "v7; approved planform and v6 section proportions retained",
    "planform": "same octagonal coordinates as approved v3/v7",
    "roof": "four long gabled wing roofs; chamfer-edge transition planes connect roof surfaces around all four corners",
    "section_width_model_units": section_width,
    "total_height_model_units": total_height,
    "wall_height_model_units": wall_height,
    "roof_rise_model_units": total_height - wall_height,
    "ratio": "5.3:4",
    "courtyard": "open; no roof over courtyard",
    "change_from_v7": "removed isolated triangular gable end caps and added continuous roof transition planes on all four chamfer edges",
    "limitations": "Conceptual roof junctions only; exact historic corner framing and roof details remain to be verified against original drawings."
}
META.write_text(json.dumps(metadata, indent=2, ensure_ascii=False), encoding="utf-8")
print(f"Generated {OUTPUT} ({OUTPUT.stat().st_size} bytes)")
