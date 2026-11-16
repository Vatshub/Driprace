# DRIP RACE — prepared scenery integration

Copy public/ into the same game project when its phase needs these assets. Read CREDITS.md and manifest.json. This is an asset collection, not a game or a passed environment/mobile gate.

The seven prepared GLBs cover a cone, striped barricade (source Object_19), city building cluster, mountain peak, repaired complete valley, Mars-derived sand terrain and one pine with three authored LODs. The other approved props/materials and sunset/night skies are preserved with dependencies. Daylight/asphalt are in the Phase 1 overlay.

Call preparePineLOD once on the fresh combined pine scene BEFORE rendering; otherwise all three levels would overlap. Default 35/90 m transitions have hysteresis. One combined file shares its textures across levels. The tree is normalized to a common 12 m reference height. Other source props retain source units and require deliberate instance scaling/placement.

The source valley lacked its existing atlas on 29 UV-mapped chunks; that assignment is repaired. The incomplete fragment-only extraction is rejected and excluded. Scan cut edges/holes still need concealment by terrain/placement. Desert geometry is Mars-derived, with the display pedestal/vertical sides removed and approved sand maps applied. It is not an Earth dune scan. Its UVs assume a 10x uniform placement scale (about 250 m source width) and 15 m sand texture period; adjust UVs for other scales.

Mountain and valley candidates are reduced but still substantial (see actual counts in evidence). Do not load every environment/LOD/HDR together or promise A55 frame rates from file-size reductions. Prefer lazy loading, one active environment, shared geometry/materials and measured quality tiers during game integration. Actual phone optimization and five completed rendered environments remain later phase gates.

Source Construction Barier-02 remains untouched locally and is excluded while its evidence is unresolved. No downloads or purchases were made.
