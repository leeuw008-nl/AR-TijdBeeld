#!/usr/bin/env python3
"""Render the complete uncompressed GLB mesh as a georeferenced top-down PNG overlay."""
import json, math, struct, sys
from pathlib import Path
from PIL import Image, ImageDraw

glb_path, out_path, meta_path = map(Path, sys.argv[1:4])
anchor_lat, anchor_lon, size = 52.58737559898257, 6.391727308884152, 1400
raw = glb_path.read_bytes()
if raw[:4] != b"glTF" or struct.unpack_from("<I", raw, 4)[0] != 2:
    raise SystemExit("Expected GLB 2.0")
total = struct.unpack_from("<I", raw, 8)[0]
if total > len(raw): raise SystemExit("Truncated GLB")
offset, doc, binary = 12, None, None
while offset + 8 <= total:
    length, kind = struct.unpack_from("<II", raw, offset); offset += 8
    chunk = raw[offset:offset + length]; offset += length
    if kind == 0x4E4F534A: doc = json.loads(chunk.decode("utf-8").rstrip(" \0"))
    elif kind == 0x004E4942: binary = chunk
if doc is None or binary is None: raise SystemExit("Missing GLB JSON/BIN chunk")
views, accessors, meshes, nodes = doc["bufferViews"], doc["accessors"], doc["meshes"], doc.get("nodes", [])

def identity():
    return [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]

def multiply(a, b):
    return [sum(a[k*4+r] * b[c*4+k] for k in range(4)) for c in range(4) for r in range(4)]

def node_matrix(n):
    if "matrix" in n: return [float(v) for v in n["matrix"]]
    t=n.get("translation",[0,0,0]); q=n.get("rotation",[0,0,0,1]); s=n.get("scale",[1,1,1])
    x,y,z,w=q
    return [
      (1-2*y*y-2*z*z)*s[0], (2*x*y+2*z*w)*s[0], (2*x*z-2*y*w)*s[0], 0,
      (2*x*y-2*z*w)*s[1], (1-2*x*x-2*z*z)*s[1], (2*y*z+2*x*w)*s[1], 0,
      (2*x*z+2*y*w)*s[2], (2*y*z-2*x*w)*s[2], (1-2*x*x-2*y*y)*s[2], 0,
      t[0],t[1],t[2],1]

def read_accessor(index):
    a=accessors[index]; view=views[a["bufferView"]]
    components={"SCALAR":1,"VEC2":2,"VEC3":3,"VEC4":4}[a["type"]]
    fmt={5126:"f",5125:"I",5123:"H",5122:"h",5121:"B",5120:"b"}[a["componentType"]]
    component_size=struct.calcsize("<"+fmt)
    stride=view.get("byteStride",component_size*components)
    start=view.get("byteOffset",0)+a.get("byteOffset",0)
    values=[]
    for i in range(a["count"]):
        row=struct.unpack_from("<"+fmt*components,binary,start+i*stride)
        if a.get("normalized") and a["componentType"] != 5126:
            maximum={5125:4294967295,5123:65535,5122:32767,5121:255,5120:127}[a["componentType"]]
            row=tuple(max(-1.0,v/maximum) if a["componentType"] in (5122,5120) else v/maximum for v in row)
        values.append(row)
    return values

triangles=[]; xs=[]; zs=[]
def add_mesh(mesh_index, world):
    for primitive in meshes[mesh_index].get("primitives",[]):
        if primitive.get("mode",4) != 4: continue
        source=read_accessor(primitive["attributes"]["POSITION"])
        vertices=[]
        for p in source:
            x,y,z=p[:3]
            vertices.append((world[0]*x+world[4]*y+world[8]*z+world[12],
                             world[1]*x+world[5]*y+world[9]*z+world[13],
                             world[2]*x+world[6]*y+world[10]*z+world[14]))
        indices=[int(v[0]) for v in read_accessor(primitive["indices"])] if "indices" in primitive else list(range(len(vertices)))
        for i in range(0,len(indices)-2,3):
            a,b,c=vertices[indices[i]],vertices[indices[i+1]],vertices[indices[i+2]]
            area=abs((b[0]-a[0])*(c[2]-a[2])-(c[0]-a[0])*(b[2]-a[2]))
            if area < 1e-5: continue
            triangles.append(((a[1]+b[1]+c[1])/3.0,a,b,c))
            xs.extend((a[0],b[0],c[0])); zs.extend((a[2],b[2],c[2]))

def visit(node_index, parent):
    node=nodes[node_index]; world=multiply(parent,node_matrix(node))
    if "mesh" in node: add_mesh(node["mesh"],world)
    for child in node.get("children",[]): visit(child,world)

if nodes:
    scenes=doc.get("scenes",[{}]); scene=scenes[doc.get("scene",0)]
    for root in scene.get("nodes",[]): visit(root,identity())
else:
    for i in range(len(meshes)): add_mesh(i,identity())
if not triangles: raise SystemExit("No top-facing mesh triangles found")
min_x,max_x=min(xs)-2.0,max(xs)+2.0
min_z,max_z=min(zs)-2.0,max(zs)+2.0
north_min,north_max=-max_z,-min_z
width,height=max_x-min_x,north_max-north_min
scale=min((size-24)/width,(size-24)/height)
offset_x=(size-width*scale)/2; offset_y=(size-height*scale)/2
def pixel(point):
    x,_,z=point; north=-z
    return (round(offset_x+(x-min_x)*scale),round(offset_y+(north_max-north)*scale))
image=Image.new("RGBA",(size,size),(0,0,0,0)); draw=ImageDraw.Draw(image,"RGBA")
triangles.sort(key=lambda item:item[0])
low=min(item[0] for item in triangles); span=max(0.01,max(item[0] for item in triangles)-low)
for y,a,b,c in triangles:
    shade=(y-low)/span
    draw.polygon([pixel(a),pixel(b),pixel(c)],fill=(177+int(22*shade),166+int(20*shade),143+int(16*shade),238))
for _,a,b,c in triangles:
    draw.line([pixel(a),pixel(b),pixel(c),pixel(a)],fill=(75,68,57,105),width=1)
image.save(out_path,optimize=True)
lat_per_m=1.0/111320.0
lon_per_m=1.0/(111320.0*math.cos(math.radians(anchor_lat)))
metadata={
 "west":anchor_lon+min_x*lon_per_m,"east":anchor_lon+max_x*lon_per_m,
 "south":anchor_lat+north_min*lat_per_m,"north":anchor_lat+north_max*lat_per_m,
 "anchorLat":anchor_lat,"anchorLon":anchor_lon,"triangles":len(triangles),
 "widthMeters":width,"heightMeters":height
}
meta_path.write_text(json.dumps(metadata,separators=(",",":"))+"\n")
print(f"Rendered {len(triangles)} GLB triangles ({width:.2f} x {height:.2f} m)")
print(f"WGS84 bounds: {metadata['south']:.8f},{metadata['west']:.8f} .. {metadata['north']:.8f},{metadata['east']:.8f}")
