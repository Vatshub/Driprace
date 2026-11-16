# DRIP RACE — tested vehicle preparation helpers

For private staged integration. No model bytes are bundled in this module folder. Match the exact source SHA-256 in placements.json. All original vehicle GLBs remain unchanged.

On a fresh GLTFLoader result: preparePlayableWheels(gltf,id) or prepareTrafficWheels(gltf,id) BEFORE prepareVehicle(gltf.scene,placement). Then bindForwardRolling(controller,prepared.root) at the canonical +Z-forward pose, before a driving/showroom parent turns. The returned controller's positive setAngle rolls forward; native setters retain their source-local angle convention. Phase 2 should derive angle from actual distance and wheel radius, not an independent decorative timer.

Prepare once per instance. Starter steering is owned by prepareVehicle; Crimson carriers 140/177 lose only the original -14° front display yaw. Veloce's authored camber remains. Sedan front tire/rim straightening is preserved; its fixed discs/calipers stay in their already-straight source alignment.

BMW copies exact indexed wheel geometry and now uses rotor Y/Z centers for the spindle, keeping hidden source geometry for ownership clarity. Olpen uses 18-point hub-ring fits tied to its exact source, rather than its deformed tire envelope. Olpen is explicitly moving traffic. Bus uses two front groups and one complete rear axle assembly. Old Car animates actual skin joints with rigid one-joint wheel weights, not empty named wheel objects. updateBounds is a diagnostic skeleton/bounds refresh, not a per-frame game requirement.

All fourteen controllers were exercised at 25 angles with fixed transforms checked THROUGHOUT rotation, forward-sign checks and repeated-preparation guards. BMW/Olpen retain shape-dependent tire-envelope variation despite stable disc/hub centers. These checks are not certification of every visual clearance, steering mechanics, shared-cache instances or actual-phone costs.

The owned-instance release helper requires a detached root and explicit exclusiveOwnership. It disposes owned geometry/materials/textures/skeletons, with a sharedResources exclusion set if applicable. The forward wrapper rejects use after disposal. Do not also dispose the same paint materials separately during full asset release. A shared source cache needs proper reference counts; do not mark borrowed geometry/textures as exclusive. The executed fresh-load test covered Starter/BMW/Old Car, two independent instances, six load/release cases, independent paint and stable post-warm-up GPU counters (2 geometries/4 textures; initial renderer had 2/3). CPU-memory leaks and a whole game's lifecycle were not measured.

Naive scene clones cannot be paired with their original parser for node-index helpers: the parser would return source nodes. Skinned clones additionally require skeleton-aware cloning. Use fresh loads with bounded reuse for the initial integration, or implement clone-specific node/mesh dependency bindings and ownership accounting before shared-cache pooling.

Declarations accompany .mjs modules for TypeScript integration and require compatible Three/@types/three types. The JavaScript was exercised with Three.js 0.187.0. A TypeScript compiler was not available in this preparation workspace; run the generated project's real TypeScript/build checks at the Phase 1 gate rather than claiming they ran here.

Preserve Nissan's patterned livery, original Crimson and all roles/prices. Keep Nissan/sedan NoAI notices. Read the current handover and executed JSON evidence for exact scope and remaining later-phase limitations.
