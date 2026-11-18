# Hoofdgebouw Ommerschans (1895): 3D-model voor de app

Reconstructie van het hoofdgebouw van de Ommerschans zoals het er in 1895 bij stond: het ringgebouw,
de gebouwen op het binnenplein en het interieur. Hoe het model tot stand kwam, met alle bronnen en
aannames, staat in de `README.md` in de hoofdmap.

## Welk bestand?

Alle drie de bestanden bevatten precies hetzelfde model: 88.898 driehoeken, 142 onderdelen
(draw calls) en 8 texturen van maximaal 1024 px. Ze verschillen alleen in compressie.

| Bestand | Grootte | Extensies | Wanneer |
|---|---|---|---|
| `hoofdgebouw_ommerschans_1895_draco.glb` | 1,2 MB | `KHR_draco_mesh_compression` | **Aanbevolen** als de engine Draco kan decoderen |
| `hoofdgebouw_ommerschans_1895_meshopt.glb` | 2,8 MB | `EXT_meshopt_compression`, `KHR_mesh_quantization` | Snelste decodering; three.js/Babylon.js/glTFast met meshopt-decoder |
| `hoofdgebouw_ommerschans_1895.glb` | 7,7 MB | geen | Als de engine geen compressie ondersteunt |

Ondersteuning per engine. Controleer dit voor jullie eigen versie.
- **three.js:** `GLTFLoader`, met `DRACOLoader` voor Draco en `setMeshoptDecoder(MeshoptDecoder)` voor meshopt.
- **Babylon.js:** beide compressievormen ingebouwd.
- **Unity met glTFast:** Draco via het pakket `com.unity.cloud.draco`, meshopt via `com.unity.meshopt.decompress`.
- **model-viewer:** Draco werkt standaard. Voor meshopt moet je `ModelViewerElement.meshoptDecoderLocation` zetten; zonder decoder laadt de meshopt-variant niet (getest).
- **Godot 4 en andere lezers zonder decompressie:** gebruik het standaardbestand.
- **iOS met RealityKit/Quick Look:** die werkt met USDZ, niet met GLB. Een USDZ-versie kan op verzoek gemaakt worden.

## Plaatsing op locatie (georeferentie)

| | |
|---|---|
| Eenheden | meters, ware schaal |
| Assen (glTF) | **+X = oost**, **+Y = omhoog**, **−Z = ware noord** (dus +Z = zuid) |
| Oorsprong (0,0,0) | zwaartepunt van het binnenplein: **lon 6.39172731, lat 52.58737560** (WGS84) |
| Hoogte 0 | maaiveld in 1895 = +6,56 m AP (≈ NAP) |

Deze gegevens staan ook in het bestand zelf, onder `asset.extras`.

Praktisch:
- Plaats het model met de oorsprong op het ankerpunt en lijn −Z uit op het ware noorden. Het model
  staat dan al goed gedraaid: het gebouw zelf staat ±4,4° gedraaid ten opzichte van het noorden, en
  dat zit al in de geometrie.
- Lokale x/y in meters zet je om naar lengte- en breedtegraad met een lokale benadering rond het anker.
  In `ruimtes.json` staat per ruimte het middelpunt al in beide stelsels.
- **Hoogte:** het huidige maaiveld kan afwijken van dat van 1895. Ankeren op het terrein (bijvoorbeeld
  een *terrain anchor* bij ARCore Geospatial) met hoogte 0 = maaiveld is meestal robuuster dan
  ankeren op een absolute hoogte.
- **ARCore Geospatial:** de lokale assen van een anker zijn, voor zover ik weet, oost/omhoog/zuid. Dat
  komt overeen met de assen van dit model, zodat er geen extra rotatie nodig is. Test dit even op
  locatie.

## Structuur van het model

```
Ommerschans_1895
├── Hoofdgebouw     buitengevels, binnenplaatsgevels, binnenzijde, plint, dak (+ _onderzijde),
│                   kozijnen, glas, deuren, dakkapel, goten, schoorstenen, luchtkokers, dakramen
├── Binnenplein     binnenstal, touragemagazijn, 4 paljasloodsen, privaat, mestbak
└── Interieur       binnenmuren, zoldervloeren, trappen, stalinrichting, inrichting,
                    67 vloeren "Ruimte_*" (één per ruimte)
```

- **Lagen:** zet het interieur uit door de node `Interieur` te verbergen. Het hoofdgebouw is daarna
  nog steeds gesloten.
- **Ruimtes:** elke vloer `Ruimte_*` heeft `extras` met naam, functie, oppervlak, vloerhoogte en
  middelpunt (lokaal en WGS84). Hetzelfde staat in `ruimtes.json`. Daarmee kan de app herkennen in
  welke ruimte de gebruiker staat, of er informatie bij tonen.
- **Glas:** half doorzichtig (`alphaMode: BLEND`). Wil je liever ondoorzichtig glas voor betere
  prestaties, zet dan de alpha van materiaal `glas` op 1.

## Prestaties

- **Geometrie:** 89k driehoeken, ~193k hoekpunten, 142 draw calls.
- **Texturen:** 8 JPEG-texturen; ~30 MB videogeheugen zonder GPU-compressie (inclusief mipmaps).
- **Normal maps:** het bestand bevat expliciete tangents, zodat het reliëf in elke engine hetzelfde
  wordt weergegeven.
- **Mogelijke verdere besparingen** (op verzoek):
  - KTX2/Basis-texturen: minder videogeheugen;
  - interieur als apart bestand dat pas geladen wordt bij binnenkomst;
  - samenvoegen van onderdelen per materiaal (minder draw calls, maar dan vervallen de losse nodes per ruimte).

## Opnieuw genereren

Zie de README in de hoofdmap. Kort samengevat:

```bash
.venv/bin/python scripts/build_model.py   # -> output/hoofdgebouw_ommerschans_ruw.glb
scripts/optimaliseer.sh                    # -> output/oplevering/*
```
