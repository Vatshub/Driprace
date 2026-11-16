# DRIP RACE — Android delivery contract
Prepared 16 November 2026. Read this Android addendum BEFORE the preserved behavioral specification below.

## Authority and delivery
This package updates the previous browser implementation plan at the user's request. Build entirely inside Google AI Studio Android mode, with its cloud build/emulator and browser-to-phone installation. The user will operate AI Studio. Do not require Node.js, Android Studio, a local SDK/ADB, terminal commands, Capacitor, an external editor, paid services or public hosting. Do not switch to web mode silently. A separate browser-playable edition is not established by this Android project.

The Android addendum overrides conflicting platform/tool/API wording in the historical specification below. All gameplay values, identities, prices, formulas, reward rules, branding, assets, source restrictions, visual goals and ten-phase order remain authoritative. Read the complete specification; do not implement only this addendum.

Exact title: DRIP RACE
Exact subtitle: A Property of Sound Of Drip Musictech Private Limited.
Private teacher-demo homework scope, followed by deletion. No publishing or purchases. Five cars remain Starter Sports Car (free), Super Racing Car (500), Muscle Car (1,000), Hyper Sports Car (2,000), Legendary Supercar (5,000). Preserve original Crimson and all nine approved traffic vehicles, including moving Olpen. No new downloads or asset selection are requested. All 23 audio originals are user-created; all eight corrected loops are listening-approved. Do not reopen those approvals.

## Required feasibility gate before full implementation
Google documents Kotlin/Jetpack Compose, single activity/module, cloud emulator and direct phone installation; it also documents no NDK/C++ support. These facts do not prove a 3D engine/library will work. Before adding gameplay, select a rendering approach compatible with those actual restrictions and prove it builds and renders the genuine Starter GLB. Inspect transitive dependencies: a Kotlin-facing library may still require native binaries. Do not quietly introduce a renderer requiring an unsupported native toolchain. If a prebuilt native dependency's support is unclear, report the uncertainty and actual cloud build result; do not infer compatibility from its Kotlin API.

Binary asset import and rendering-library compatibility in the user's actual editor remain unverified. A local Mac path is an inventory reference, not an accessible project file. If AI Studio cannot package the supplied GLB, stop with a precise blocker. A system document-picker recovery may be used for private development if available and implemented, with local reading only, but it is not bundled/offline acceptance. Do not substitute a screenshot, video, box car or generated model to pass the gate. Do not prescribe external tools as a completed solution to the user's AI-Studio-only constraint.

## Android equivalents for historical web instructions
- React/TypeScript/Vite/R3F/Drei: replace with Kotlin/Jetpack Compose and a proven compatible real 3D renderer. Compose draws UI; a flat Compose drawing alone is not the 3D game.
- TypeScript interfaces: preserve their meaning as typed Kotlin models with runtime save validation. Use actual cloud Gradle/Kotlin build checks instead of claiming TypeScript checks ran.
- WebGL Canvas/render loop: one owned rendering surface and one simulation/render scheduling authority. Renderer-specific context loss, lifecycle and GPU resource handling replace browser-only APIs.
- JavaScript vehicle adapters: reference evidence only; port the actual relevant transforms, hierarchy bindings, once-only guards, wheel axes and disposal ownership after inspecting the modules. Do not import .mjs into Kotlin or claim their browser tests certify native ports. Preserve original GLBs.
- localStorage: native private persistent storage, such as a versioned atomic file/DataStore or transactional database. Preserve canonical schema/revisions, validated saves, recovery snapshots, idempotent wallet transactions and confirmed-versus-pending semantics. Serialize writes. Use transactions where multiple values must commit together. Do not promise cheat-proof storage or persistence after uninstall/data clearing.
- BroadcastChannel/multiple tabs: no browser-tab system is required. Prevent duplicate writers/listeners and stale asynchronous writes in the single Android app.
- Web Audio: native compatible managed audio implementation, preserving the five named buses, five car profiles, actual speed/load response and no duplicate loops. Handle audio focus loss, backgrounding and lifecycle. No new engine recordings are claimed.
- visibilitychange/browser suspension: Android activity/app lifecycle. Pause simulation/audio, clear inputs, avoid elapsed-time catch-up; deliberate Resume on return. Process death may return Home while retaining durable progression. Never credit race progress twice.
- Browser asset routes: actual packaged Android assets/resources, including required fonts/decoders/audio. Final installed app must cold-start and play offline. No remote development server dependency.
- Browser resizing: Android configuration/window size changes; preserve state and update viewport/camera/controls. No orientation-triggered reset or duplicate renderer.
- PWA/browser deployment: not required. APK/direct installation is the Android delivery milestone. A downloadable standalone APK is only verified when that action and artifact actually exist; source ZIP export is not an APK.

## Android interaction requirements
Portrait-first with usable landscape and auto-rotation; responsive camera/HUD/menus and safe areas. No forced orientation lock required by default. Large-screen OS behavior may override orientation restrictions. Immersive gameplay fullscreen must account for transient system bars, cutouts and gesture insets; do not trap navigation.
Touch buttons/swipes must use stable pointer identities, support simultaneous steering/brake/Nitro where the contract allows, and release on pointer up/cancel, pause, focus loss and backgrounding. Prevent accidental menu taps from steering. Phase 1 may test neutral pointer feedback; driving controls start in Phase 2, final HUD polish in Phase 8.
Android Back: Running -> Paused; close menu overlays before navigating Home; do not silently discard progression. Exit only from an appropriate Home flow. Resume never occurs automatically after returning from another app.

## Phases and evidence
Feasibility is an entry check within Phase 1, not a replacement for or extra completed gameplay phase. Preserve phases 1–10 below. Phase 1 additionally requires real cloud Android build, genuine GLB rendered, working transitions, fullscreen/rotation/lifecycle and an actual phone-install check if available. Missing phone access is NOT RUN; it does not become emulator performance certification. Do not advance until the gate passes and the user chooses to proceed.
Phase 2 adds touch driving and deterministic simulation checks. Phase 4 adds durable wallet. Phase 7 integrates the approved scenery. Phase 8 integrates prepared audio/profiles. Phase 9 proves Android recovery and sustained performance. Phase 10 performs full regression and installed-app verification. No invented FPS, successful imports, APK exports or test results.

## Source limitations remain
Nissan/sedan NoAI restrictions: do not upload their models or textures to generative prompts; later runtime integration must respect source restrictions. Standard-license acquisition records remain unresolved for specified assets; BMW is recorded CC BY-NC-SA. This private package is not commercial/public-release clearance. Construction Barier-02 remains excluded. Read supplied credits and manifests. Android packaging does not remove any license restriction.

## Official workflow reference (checked 16 November 2026)
https://aistudio.google.com/docs/aistudio-android
The documented route is Android platform picker -> cloud emulator -> Install on Device over WebUSB (Chrome/Edge, USB cable, phone USB debugging). Source ZIP download is documented. Standalone APK download availability must be checked in the actual UI. Account availability, actual binary imports and this game's renderer have not been tested.

---
# Preserved full behavioral specification
The text below is preserved from GAME-CONTRACT.md. Apply the Android substitutions above to platform-specific clauses; retain all remaining requirements verbatim.

# DRIP RACE — implementation contract

Exact subtitle: **A Property of Sound Of Drip Musictech Private Limited.**

Private teacher-demo homework, then deletion. Current asset availability/status is defined by START-HERE and the manifests. Historical acquisition-status sentences below do not override the current records. All gameplay numbers, Part 8 V2 and phase gates remain authoritative.

# 3. Product vision, user experience and non-negotiable quality bar

- **Product:** cinematic, highly realistic-looking, premium 3D endless three-lane highway racer. Responsive and satisfying arcade gameplay rather than a costly full automotive physics simulator.
- **Platforms:** modern WebGL-capable Android Chrome, iPhone/iPad Safari, desktop Chrome/Edge/Safari/Firefox where supported. Feature-detect rather than promising universal compatibility.
- **Orientation:** portrait-first smartphone; support landscape, tablet and desktop layouts.
- **Visual identity:** dark graphite, near-black, metallic silver, electric-blue accent, subtle gold for currency/rewards; cinematic car and showroom; no cluttered panels covering approaching traffic.
- **Game must be real 3D:** a perspective camera, actual moving 3D vehicle and road/traffic geometry, functional collisions, not a 2D game, static PNG, GIF, video loop or faux-3D landing page.
- **Car quality:** visually distinct sports/muscle/hypercar silhouettes, PBR body paint, glass, rubber, lit headlights/brake lights, wheels, correct scale, realistic road, reflections/lighting, distinct landscapes. If real licensed models unavailable, use honestly labeled detailed procedural fallback, not fake asset claims.
- **Gameplay:** auto forward driving, smooth single-step lane changes, brake hold, Nitro hold, moving traffic, pickups, damage, overtakes/near misses, score, distance, Game Over/Restart.
- **Premium progression:** five purchasable/unlockable vehicles with upgrades and paint; daily missions, permanent achievements, unlockable environments and seven-day reward cycle.
- **Performance:** aim at 60 FPS on capable systems and a usable 30 FPS fallback, measured rather than declared. Do not change physics when changing graphics quality.
- **Accessibility:** legible UI, keyboard support, large touch targets, contrast, reduced motion, optional haptics, playable with sound muted.
- **No fake functionality:** every visible button and metric tied to real state; do not show inert buttons for unfinished features.

## Branding contract

**Primary product title:** `DRIP RACE`  
**Subtitle, verbatim:** `A Property of Sound Of Drip Musictech Private Limited.`

Place subtitle immediately below title in splash/loading and main home/title compositions, smaller and readable. Apply proper name to browser title, metadata, branding-related copy and relevant game screens. UI may also use a visually stylized wordmark, but the displayed game name must read **DRIP RACE**. The name of the company is a branding credit, **not** an instruction to implement music-distribution functionality. Do not modify the subtitle to “SODMT,” remove the final period, or change its capitalization. Any old `HIGHWAY RUSH 3D` branding is legacy only; retain it in this document solely for historical identification.

---

# 4. Technical architecture and game-state authority

## Stack

- React + TypeScript + Vite (or same compatible existing build setup).
- Three.js + React Three Fiber (`@react-three/fiber`) + Drei (`@react-three/drei`). Match compatible stable React/R3F major versions.
- Zustand or equivalent small store for application/UI progression state.
- Web Audio API for sound and spatial audio where available.
- Versioned localStorage for offline progression; no required backend/cloud account, no fake multiplayer or global ranking.
- Vitest for deterministic physics, currency, mission and persistence logic; browser/E2E tests when tools support them.
- True WebGL geometry, textures, material/light/camera systems; standard glTF/GLB assets.

## Primary application state machine

`BOOT → LOADING → HOME → GARAGE → RUNNING → PAUSED → GAME_OVER` (explicit transitions; menu screens such as Missions, Achievements, Levels, Daily Rewards, Settings and Profile may be subviews rather than conflicting top-level states). `RUNNING → PAUSED → RUNNING`, `RUNNING → GAME_OVER`, `GAME_OVER → new RUNNING` or `HOME` must all be defined. UI overlay close from Pause returns to PAUSED, not RUNNING.

## One owner for each source of truth

| Domain | Authority | Consumers |
|---|---|---|
| Player speed/position | VehiclePhysics / fixed-step simulation | Renderer, camera, HUD, engine audio, traffic relative motion |
| Traffic/collisions | TrafficManager + CollisionSystem | DamageSystem, scoring, effects |
| Nitro | NitroController | Physics, HUD, engine audio, camera/effects |
| Health/shield | DamageSystem + PowerUpManager | HUD, audio, Game Over |
| Coin balance/purchases | EconomyService + canonical progression snapshot | Home, Garage, HUD, missions/rewards |
| Overtakes/near misses/score | Authoritative gameplay event + ScoreSystem | HUD, achievements/missions |
| Lifetime/daily progression | ProgressionManager | Menus, rewards, level selection |
| Sound | Single AudioManager | Engine/traffic/SFX/music/haptics |
| UI navigation | Typed state machine | All React screen components |
| Persistence | One versioned PlayerProgression | All permanent user state |

Never award currency through a particle effect, audio callback, Game Over screen, UI counter or animation. Never implement a parallel nitro controller, economy wallet, score, health counter or simulation clock.

## Fixed simulation time

Recommended 60 Hz fixed-step simulation (1/60 s), bounded accumulator, clamped elapsed time after suspension, interpolation for visual rendering where appropriate. Keep high-frequency state outside React rerenders. Pause freezes simulation speed, traffic, collisions, Nitro, power-up timers, distance and score. Calendar day eligibility is a **separate wall-clock concept** and continues during pause.

## Suggested module boundaries

`GameSimulation`, `RaceStateManager`, `GameplayEventBus`, `PlayerController`, `VehiclePhysics`, `LaneController`, `InputManager`, `TrafficManager`, `TrafficSpawner`, `TrafficAI`, `CollisionSystem`, `DamageSystem`, `ScoreSystem`, `PickupSystem`, `PowerUpManager`, `NitroController`, `DifficultyDirector`, `CameraController`, `VehicleCatalog`, `VehicleStatsCalculator`, `VehiclePurchaseService`, `VehicleUpgradeService`, `GarageShowroom`, `EconomyService`, `ProgressionManager`, `MissionManager`, `AchievementManager`, `DailyRewardManager`, `AudioManager`, `PersistenceAdapter`, `GraphicsQualityManager`, `AssetRegistry` and respective React UI components. These are responsibilities, not mandated file-per-class boilerplate.

---

# 5. World coordinates, driving model and controls

## Geometry and motion

- World coordinates: X lateral, Y vertical, +Z forward; road approximately Y=0.
- Exactly 3 lanes, each initially 3.6 m wide. Centers X=-3.6 (index 0), X=0 (index 1), X=+3.6 (index 2).
- Player begins each race in center lane; nominal rendered Z may remain fixed as the endless road/traffic moves backward relative to the player. **Cumulative forward distance is an independent simulation value.**
- Typical sports-car width 1.8–2.0 m, length 4.2–4.8 m; individual car collision bodies represent actual proportions.
- Smooth lane interpolation initially 0.35–0.50 s depending on vehicle. Never teleport or leave lane boundaries; on repeated input, queue at most one extra valid lane request or apply an explicitly documented policy.
- Car body roll/pitch and camera motion are visual, not distortions of collision geometry.
- Relative traffic movement: `trafficRelativeZ -= (playerSpeed - trafficSpeed) * fixedDeltaTime` under the defined coordinate convention.
- `distanceMeters += speedMetersPerSecond * fixedDeltaTime`; `speedKmh = speedMetersPerSecond * 3.6`.
- Vehicle auto accelerates/cruises without requiring an always-held accelerator. Brake hold applies actual negative acceleration. Nitro hold applies real acceleration and higher speed cap. Brake overrides Nitro.

## Controls

| Input | Action |
|---|---|
| Mobile left/right tap or swipe | One lane change in that direction |
| Mobile Brake hold | Actual braking; releasing ends braking |
| Mobile Nitro hold | Manual boost while available; release ends it |
| Pause | Pauses actual simulation |
| Keyboard Left/A and Right/D | Steering |
| Keyboard Down/S | Brake |
| Keyboard Space | Nitro |
| Keyboard Escape/P | Pause/resume appropriately |

Multi-touch: steering plus Brake or Nitro must work. Handle pointer cancellation, lost pointer capture, focus loss, app background, key repeats and conflict between swipes and buttons. Target ample touch areas, approximately 56–72 CSS px where practical and at least 44–48 px for essential actions. Menus remain scrollable; gameplay prevents unwanted page scroll.

## Nitro

- Normalized resource 0–100; starting 100.
- Baseline manual drain Starter car: 28 units/s; regeneration baseline 12 units/s while inactive and driving (car-specific upgrades alter this).
- Approximately 1.25× normal maximum speed when boosted.
- Manual Nitro activation only when sufficient resource; no negative meter; no drain/regen while paused.
- A separate collectible 3 s automatic Nitro **uses the same NitroController**, not independent speed multipliers or a second meter; does not drain manual resource for automatic portion; Brake cancels.

---

# 6. Traffic AI, collisions, fairness and scoring

**Traffic types:** sedan, hatchback, SUV, sports car, truck, bus. Same-direction traffic with believable varied individual speeds. Each has unique runtime identity, model/category, width/length, lane, relative/world positions, speed and collision state. Pool/recycle traffic with fresh logical IDs.

**Static hazards:** barriers, roadwork barricades, blocks, broken-down vehicles and appropriate obstacles. Physics/collisions must match visible geometry. Assets can be visually detailed but collision shapes may be simplified boxes/oriented boxes/multi-box shapes. Use swept/continuous detection where fast movement could tunnel through an obstacle. Collision detection must use **actual interpolated X and forward positions**, not only equal lane indices.

**Fair spawning:** do not create unavoidable instant three-lane roadblocks, barriers appearing inside the car, insufficient reaction time, impossible alternating lane gaps or overlapping pickups/hazards. Use a seeded encounter planner or short-horizon reachability check considering current player speed, possible Nitro speed, braking, lane-change time, vehicle dimensions, traffic speeds, reaction margin and visible distance. Reject candidate spawns or defer when no reachable safe path exists. Difficulty scales with distance, but fairness never disappears.

**Health:** start 3 hearts. One eligible unshielded impact removes one heart; approximately 1 s collision grace prevents duplicate damage for a continuous contact. Shield (one hit) is evaluated before health damage; its consumption also protects against immediate repeated contact. Three separate valid damaging collisions => health 0 => Game Over once. Collision may include controlled speed penalty and synchronized visual/audio/haptic feedback.

**Scoring:** `score = floor(distanceMeters) + 30 * validUniqueOvertakes + 50 * validUniqueNearMisses`. Unique overtake event when passing eligible traffic; near miss requires genuinely close safe geometry, not a collision. Bonuses awarded once per eligible traffic instance. Best score persists and is updated only when exceeded. Score is not wallet currency.

**Five in-race difficulty stages:** 0–500 m introductory; 500–1,500 m warm-up; 1,500–3,000 m challenging; 3,000–5,000 m advanced; 5,000+ m endless. Use bounded increases to traffic frequency/density, speed variance and encounter complexity. These stages are distinct from environment unlock levels.

---

# 7. Five-car catalog: exact prices and base performance

| ID | Display name | Unlock price | Top speed km/h | Acceleration m/s² | Brake deceleration m/s² | Lane-change s | Manual Nitro drain /s |
|---|---|---:|---:|---:|---:|---:|---:|
| `starter_sports` | Starter Sports Car | Free | 180 | 5.5 | 12.0 | 0.48 | 28 |
| `super_racing` | Super Racing Car | 500 coins | 200 | 6.2 | 12.5 | 0.44 | 26 |
| `muscle` | Muscle Car | 1,000 coins | 195 | 7.2 | 11.5 | 0.49 | 29 |
| `hyper_sports` | Hyper Sports Car | 2,000 coins | 220 | 7.0 | 13.0 | 0.38 | 24 |
| `legendary_supercar` | Legendary Supercar | 5,000 coins | 240 | 8.0 | 13.5 | 0.35 | 22 |

These are **initial game-balancing values**, not real manufacturer specifications. Starter is owned and selected initially; others locked. Starter is an elegant modern coupe; Super Racing is a track-inspired agile racer; Muscle is wider/deeper with strong acceleration but slower lateral handling; Hyper Sports is aerodynamic high-performance; Legendary is the most advanced all-round car. All remain three-heart vehicles. No licensed logos/replicas unless rights exist.

**Visual assets:** actual distinct recognizable 3D models, separate body-paint materials, glass, rubber, tires, wheels, headlights, brake lights, consistent scale/orientation and camera alignment. Garage uses higher-quality showroom rendering; gameplay may use a suitable LOD of the same recognizable vehicle. Selected car and chosen paint must match Home, Garage and gameplay.

## Upgrades

Four independently purchasable tracks on **each owned vehicle**: Engine, Handling, Brakes, Nitro. Levels 0 stock through 3 maximum, sequential only, no skipping. Upgrades only between races, not mid-run. Persist levels per vehicle and calculate effective performance from base config + levels.

| Upgrade category | Formula | Notes |
|---|---|---|
| Engine | Top speed km/h = base + 6 × level; acceleration m/s² = base + 0.35 × level | Starter L3: 198 km/h, 6.55 m/s² |
| Handling | Lane change seconds = max(0.30, base − 0.025 × level) | Starter L3: 0.405 s |
| Brakes | Deceleration m/s² = base + 0.8 × level | Starter L3: 14.4 m/s² |
| Nitro | Manual drain units/s = max(15, base − 1.5 × level); regen = 12 + 0.5 × level | Starter L3 drain 23.5/s, regen 13.5/s |

Nitro meter remains 0–100; the boost speed multiplier stays ~1.25. At max Legendary Engine level 3, top speed is 258 km/h normal and approx. 322.5 km/h boosted; HUD scale initially 0–340 km/h.

### Upgrade price **per track**, for each next level

| Car | Level 1 | Level 2 | Level 3 |
|---|---:|---:|---:|
| Starter | 100 | 200 | 350 |
| Super Racing | 175 | 350 | 600 |
| Muscle | 250 | 500 | 850 |
| Hyper Sports | 400 | 800 | 1,400 |
| Legendary | 600 | 1,200 | 2,000 |

Each purchase atomically changes wallet + ownership or track level in a single authoritative progression mutation; validate again at confirmation, prevent double taps/duplicate charges/negative balances, mark saved only after storage success. No refunds/selling system by default.

### Paint customization

Initially **free**: Metallic Red, Electric Blue, Pearl White, Midnight Black, Titanium Silver, Racing Yellow, Deep Emerald Green. Update only body paint, not windows, tires or lights. Color stored per owned car. Previewing locked cars does not grant ownership or persist owned customization. Optional wheel designs, caliper colors, stripes and spoilers only if actually supported with assets; avoid fake controls.

---

# 8. Coins, pickups, power-ups and economy

## Coins

| Type | Base awarded wallet units | Initial spawn weight |
|---|---:|---:|
| Regular | 10 | 70% |
| Bonus | 25 | 25% |
| Rare | 50 | 5% |

Weights configurable for balancing. Genuine 3D coins with valid spatial/swept collection, fair spawn positions, recycled objects with new IDs. `runCoinsEarned` is **currency units**, not physical objects collected. `runCoinPickupsCollected` is the count of objects. Wallet credited on validated pickup, not again at Game Over. A short bounded pending save state can be used if frequent localStorage writes cause stutter, but do not falsely claim unsaved credit is durable.

## Four collectible power-ups

| Power-up | Initial effect | Stacking/interaction rule |
|---|---|---|
| Automatic Nitro Boost | ~3 s automatic boost | Same NitroController; refresh duration, no extra multiplier; Brake cancels; manual resource not drained by automatic portion |
| Coin Magnet | 15 s; ~5.5 m lateral, ~15 m forward initial attraction region | Reserve each coin once; visual fly-to-car is only feedback; applies Double Coins if active |
| Shield | One blocked hit **or** 20 s expiry | One maximum charge; repeat pickup refreshes duration; shield hit grants collision grace |
| Double Coins | 20 s, 2× eligible physical coin reward | Does **not** multiply daily/mission/achievement rewards, purchases, score; refreshed, never 4×/8× |

Different types may coexist. Durations use **active simulation time** and freeze while paused. Examples: regular with Double Coins +20; bonus +50; rare +100. Magnet + Double Coins rare pickup gives exactly 100 wallet units and one pickup count. Shield protects from one valid impact; no simultaneous heart loss.

## One wallet / reward safety

Single EconomyService controls wallet. Accepts validated source events and unique operation IDs; purchase/upgrade or reward + claim-state updated together. Prevent repeat award from UI callbacks, recycled objects, rapid claim taps, multiple audio/particle completions, Game Over, restart or save retry. localStorage is not server-grade tamper resistance; do not describe it as cheat-proof.

---

# 9. Missions, achievements, levels and seven-day login rewards

## Daily missions (five)

| ID/title | Required target | Claimable reward |
|---|---|---:|
| Coin Collector | Earn **100 currency units** from in-race physical coin pickups, doubled earnings included | 50 |
| Highway Traveler | Accumulate **2,000 m** of actual driving during the current calendar day | 75 |
| Endurance Racer | Survive **180 active seconds in one race** in the day's eligibility segment | 100 |
| Traffic Expert | **20 validated unique overtakes** during day | 75 |
| Nitro Master | **5 qualified manual Nitro activations** during day | 75 |

A qualified manual Nitro use must deliver at least **0.35 s uninterrupted real manual boost** and consume manual resource; holding continuously counts once, button tapping with no boost does not count, automatic pickups do not count. Daily missions can accumulate across races except the single-run endurance requirement. States: IN_PROGRESS, COMPLETED_UNCLAIMED, CLAIMED, EXPIRED. Claim manually; a mission reward does not count toward Coin Collector. Completed but unclaimed previous-day mission rewards expire at rollover. Total possible daily mission reward: **375 coins**.

## Permanent achievements (eight)

| Name | Requirement | One-time claim reward |
|---|---|---:|
| First Ride | End one valid race of at least 100 m | 50 |
| Road Explorer | 10,000 m lifetime | 100 |
| Traffic Specialist | 100 unique lifetime overtakes | 125 |
| Close Call Expert | 25 valid lifetime near misses | 100 |
| Treasure Hunter | 100 physical coin pickups lifetime | 125 |
| Nitro Champion | 50 qualified manual Nitro uses lifetime | 100 |
| Garage Collector | Own 3 distinct vehicles (includes Starter) | 150 |
| Marathon Racer | Drive 5,000 m in **one** race | 200 |

Unlocked badges remain unlocked permanently. Reward must be explicitly claimed, once, and never expires. Claiming does not count as a collectible coin event.

## Five unlockable environment levels (independent of in-race difficulty)

| Level | Display / visual character | Lifetime distance needed |
|---|---|---:|
| 1 | Beginner Highway / countryside daylight, green hills, trees | 0 m — default unlocked |
| 2 | City Highway / urban skyline and expressway | 2,000 m |
| 3 | Desert Highway / golden-hour desert landscape | 8,000 m |
| 4 | Mountain Highway / mountainous scenery and atmosphere | 20,000 m |
| 5 | Night Highway / illuminated nighttime highway | 40,000 m |

Environment unlock is permanent and free (no coin payment, no paid car required). Unlock during a race displays brief notice; actual environment changes on next selection/race, not mid-run. Starter Sports Car can play any unlocked environment. All environments retain three playable lanes and fairness rules.

## Seven-day claim calendar

| Consecutive claim day | Reward coins |
|---|---:|
| Day 1 | 50 |
| Day 2 | 75 |
| Day 3 | 100 |
| Day 4 | 150 |
| Day 5 | 200 |
| Day 6 | 300 |
| Day 7 | 500 |
| **Seven-day total** | **1,375** |

One **manual** claim per eligible calendar date. A consecutive calendar day advances streak; skipped full day resets next available claim to Day 1; immediately after Day 7, the following consecutive date cycles to Day 1. Merely opening app doesn't claim. Previously earned coins never deducted for a streak reset.

At profile creation, store an IANA progression timezone if available; fall back explicitly (e.g., UTC) if not. Use YYYY-MM-DD keys in the stored timezone; never equate “a day” with raw 86,400,000 ms across DST. Mission and daily claims use **calendar clock**, unaffected by game Pause. Device clock rollback should not generate duplicate claims, but offline clock security cannot be guaranteed. A race crossing midnight continues; post-midnight mission events apply only to new day, and one-run survival mission's new-day eligible segment starts at the new boundary. Daily claims do not auto-interrupt gameplay.

## Lifetime statistics

Track authoritative total distance, active racing time, race count, best score, longest race and survival, overtakes, near misses, physical coin pickups, collectible currency earned, valid manual nitro uses, power-ups, shield blocks, vehicles owned, upgrades purchased, missions completed/claimed, achievements unlocked/claimed, levels unlocked, daily reward claim count. Use idempotent, bounded race progress checkpoints (e.g., every ~5 s of active play) with per-race high-water marks; Game Over credits only remaining deltas, never the whole race twice.

---

# 10. 3D graphics, visual direction and asset requirements

## Photorealistic arcade-racing targets

- Real 3D player vehicle on a real three-lane road, premium recognizable geometry; avoid the final look of boxes/primitives.
- glTF/GLB PBR models: MeshStandardMaterial / selective MeshPhysicalMaterial clearcoat; realistic automotive paint, metallic body, rubber tires, glass; correct color-space management and restrained tone mapping.
- Dynamic wheels, headlights, brake lights, braking pitch, controlled lane-change lean and Nitro exhaust.
- Road: repeatable seamless asphalt, ~3.6 m lanes, lane markings, shoulders, barriers, signs, roadside props.
- Environments: Beginner countryside day, City, Desert sunset, Mountain, Night highway; distinct lighting/topography/props, no bends that violate fixed lane gameplay.
- Shadows/reflections: optimized contact shadows, environment HDRIs, mobile-friendly reflections. Avoid excessive bloom, expensive real-time reflections or full-screen transparency.
- Third-person chase camera: behind/up, player lower on portrait screen, forward obstacle visibility, smooth lateral follow, subtle speed-dependent FOV, short controlled collision shake, avoids clipping.
- Weather/FX: optional realistic fog, rain/dust and particles after core is playable. Pool particles and lower quality on weaker devices.
- Graphics settings High/Medium/Low/Auto; DPR starting caps High 2.0, Medium 1.5, Low 1.0; Auto can lower further based on sustained measured frame time. Target 60 FPS capable / 30 FPS fallback, not a guarantee.
- Reuse shared meshes/materials/geometry; traffic/environment LOD; instancing and recycling; avoid duplicate WebGL contexts.

## Asset requirements — use the current supplied manifests

Approved local assets have now been inventoried and prepared. START-HERE and the manifests define current availability, restrictions and exclusions. Do not re-source the approved collection. The following are design/verification requirements, not a claim that downloads are still missing.

Target asset manifest:

| Asset group | Target deliverable | Validation requirement |
|---|---|---|
| Hero cars | **Five distinct unbranded premium GLB/glTF car models** | Actual files; recognizable different silhouettes, PBR materials, isolated paint material, wheel geometry; compatible scale/orientation; no unlicensed logos |
| Traffic | sedan, hatchback, SUV, sports car, truck, bus models | Actual files; efficient LOD / pooled entities; valid license |
| Road | road segments / asphalt PBR / markings / barriers | Seamless and correctly scaled; genuine 3D geometry and/or textures |
| Environment | trees, streetlights, buildings, rocks, desert/mountain scenery, barriers | Efficient repeatable 3D props; verified sources and rights |
| HDRI | day, sunset, night environment lighting maps | Source & license documented; mobile optimized |
| Materials | asphalt, concrete, metals, glass, rubber and paint-related textures | Correct map types, tiling, commercial rights |
| Audio (later) | engine/SFX/music if available | Genuine files; commercial-use license and attribution recorded |

Asset-source leads previously discussed: **Poly Haven** (CC0 assets/HDRI/PBR), **Khronos glTF sample assets** (check individual licenses), **Sketchfab** (check model-specific license/attribution; not automatically free for commercial use). Web links: https://polyhaven.com/ ; https://github.khronos.org/glTF-Assets/ ; https://sketchfab.com/ . These are **candidate sources, not evidence any files were downloaded**.

**Asset manifest columns**: `asset_id`, category, display_name, local_path, source_url, author, license_name, license_url, attribution_required, commercial_use_allowed, modification_allowed, model_format, texture_dependencies, approximate_triangle_count, approximate_texture_size, orientation/scale normalization, preview_validated, included_in_bundle, known_limitations."

When asset rights are uncertain, do not distribute it in the bundle. Do not manufacture remote asset URLs or describe a placeholder/procedural car as a licensed high-resolution automotive model. Retain sources/credits and document any unresolved missing premium models. Optimize actual assets where possible before delivering a ZIP and manifest.

---

# 11. Home, Garage, menus, HUD, audio and UX

## Home and navigation

Home: **DRIP RACE** brand; exact subtitle below it; cinematic real-time 3D selected car; Play Now; Garage; Missions; Achievements access; Levels; Daily Rewards; Settings; Player profile; wallet; best score. The central car is an actual scene model, not a static image/video. Keep only functional features visible in the build stage. On Play Now, validate selected owned vehicle, initialize race, show genuine asset loading, enter RUNNING only when ready.

Garage: drag/orbit 3D preview, pinch/scroll zoom, reset view, vehicle carousel with five distinctly shaped cars, current ownership/price, exact stats, EQUIPPED/SELECT/UNLOCK states, paint options, upgrade tracks and purchase confirmation. Locked preview does not equip or spend. All stats calculated from shared vehicle definitions. GUI state must not be mistaken for purchased permanent state.

Screens: Missions, Achievements, seven-day Rewards, Environment selection, Profile, Settings, Pause, Game Over, loading, retryable errors. Correct return destination: Settings opened during PAUSED returns to PAUSED. Game Over displays actual result and Play Again / Home. No fictitious advertising or paid currency purchase system.

## Racing HUD (Part 8 **V2** authoritative)

- Top: score, distance, permanent wallet, current-run coin earnings, 3 hearts, Pause.
- Center: unobscured 3D road and traffic.
- Bottom: large actual speedometer (approx. 0–340 km/h scale), Nitro 0–100, left/right steer, Brake, Nitro.
- Power-ups: icon, duration/charge for automatic boost, Magnet, Shield, Double Coins. Countdown from simulation time, not browser timer.
- Score from actual distance/overtakes/near misses; speed from `speedMetersPerSecond * 3.6` not decorative animation.
- Notifications for accepted coin pickup, near miss, overtake, collision, shield, mission/achievement/level/daily claim. UI and FX never independently award currency.
- Responsive layout sizes representative: 320x568, 360x800, 390x844, 430x932 CSS px, plus tablet/desktop. Safe areas and reduced-motion accessibility.

## Audio

One managed Web Audio AudioContext; buses **ENGINE**, **GAMEPLAY_SFX**, **AMBIENCE**, **MUSIC**, **MASTER**. Independent SFX/music settings and volume. Distinct sounds/profiles for Starter (refined sports coupe), Super Racing (high revs), Muscle (deeper low-frequency), Hyper (responsive exotic) and Legendary (flagship exotic). Engine pitch, harmonic/virtual RPM and load respond to actual speed/acceleration/braking/nitro. Audio-only virtual shifts do not modify physics. Wind/road and nearby traffic spatial audio; impacts, coin sounds, power-ups, reward cues, nitro and shield sounds. Music menu/race/pause/Game Over appropriately. Use properly licensed or original audio, synth fallback honestly labeled. Browser audio must be user-interaction unlocked when required. Haptics optional with capability detection; do not promise iPhone vibration support. Suspend/clean up sound on backgrounding, pause, restart; no duplicate engine loops.

---

# 12. Versioned saving, lifecycle and reliability

## Canonical save envelope

```ts
interface SaveEnvelope {
  format: "DRIP_RACE_SAVE";       // brand rename / safe migration from old game identifiers
  schemaVersion: number;
  revision: number;
  payload: PlayerProgression;
  integrity?: string;            // non-cryptographic integrity marker is not anti-cheat
}
```

`PlayerProgression` includes profile/timezone, wallet, vehicles owned/selected/colors/upgrade levels, lifetime stats, current date's mission state and claims, achievement unlocks/claims, environments/selection, daily reward streak/last claim, settings, save metadata. Avoid saving whole WebGL world. Validate runtime JSON data, not just TypeScript types. Reject malformed numeric/IDs/date fields; preserve valid progress via supported migration and a recovery snapshot when possible.

**Rename safety:** Original early specification used sample storage names such as `highwayRush3D:progression:primary`/`backup`, but game is now **DRIP RACE**. If actual code already stores progress under old keys, implement **one-time migration** from old names into the new canonical `dripRace:progression:primary` (or another consistent namespaced key) after validating old data, and do not destroy old valid data until new save is verified. If app never used old keys, do not invent nonexistent migration steps. Similarly `HIGHWAY_RUSH_3D_SAVE` format ID may need versioned migration if existing saves use it.

**Primary/backup localStorage:** save compact validated snapshot with monotonically increasing revision; preserve previous known-good backup when practical; detect read/write failures, quota errors, disabled storage, corrupt snapshots, conflicts. Distinguish confirmed saved state from in-memory pending changes; do not allow spending unconfirmed wallet amounts. localStorage isn't tamper-proof, transactional across tabs, a cloud sync service, or guaranteed safe from browser data clearing. Optional manual export/import JSON backup should validate versions and require confirmation before replacing existing save.

**Multi-tab:** one active writer where practical; BroadcastChannel/storage revision coordination; acknowledge no true compare-and-swap guarantee from localStorage.

**Reload/interruption:** Permanent progress survives normal reloads where saved. Exact current race persistence is *not* required. On full reload return Home and start a new race. In-session tab hide pauses race, releases held inputs, suspends audio and avoids elapsed-time catch-up; restoring tab leaves game paused until deliberate Resume. Calendar date remains independent of gameplay pause. During sudden crash, a small unsaved amount of high-frequency progress may be lost; do not promise zero loss.

**WebGL context loss:** stop rendering-dependent work and pause game, release inputs/audio, show recoverable status, rebuild GPU resources/scene on restoration, preserve authoritative in-memory state, resume only deliberately; if unrecoverable offer safe Home/retry. Never award coins during context-loss recovery.

**Asset loading/errors:** detect WebGL support, verify asset paths and licenses, show measured or honest indeterminate progress, use fallback for noncritical assets, provide Retry for failed critical assets; no endless spinner or fake renderer.

---

# 13. Mobile performance and operating targets

- Graphics presets: High (starting DPR cap 2.0), Medium (1.5), Low (1.0), Auto (initial choice via capability + measured sustained frame time). Other rendering controls include geometry LOD, instanced scenery, shadows, reflection, texture resolution, particles, draw distance and post-processing.
- Initial performance aspirations: ~16.7 ms/frame for 60 FPS on capable hardware; ~33.3 ms/frame for 30 FPS fallback. Must measure actual results and 95th-percentile frame times; no invented hardware metrics.
- Use 60 Hz fixed simulation independent of render cadence; never slow simulation physics to make rendering smoother.
- Prioritize player car, road legibility and traffic, then shadows/reflections, then decorative distant scenery. Avoid overloading mobile GPUs with multiple large HDRs, dynamic shadows, transparent effects and many uncached meshes.
- Optimize GLB assets via compression/LOD/texture sizing if supported; share immutable geometry/textures but isolate mutable paint materials per car. Dispose only when no active owner uses resources.
- Bounded world pools: road segments, traffic, coins, power-ups, particles, audio one-shots. Repeated Home↔Garage↔Race and long runs should not grow objects and loops indefinitely.
- Error/recovery: WebGL capability, asset load failures, localStorage, AudioContext user-gesture restrictions, input release on visibilitychange, layout on rotation/resize. Optional PWA/offline start only if real app-shell + all essential assets are cached; localStorage alone does not guarantee offline loading.
- Test on actual representative Android Chrome and iPhone Safari, tablet and desktop when available. A desktop phone viewport emulation is not a hardware performance test. Mark all untested platforms accordingly.

---

# 14. Ten-stage implementation roadmap for Google AI Studio / Work

The conversation culminated in a **single master contract plus ten sequential prompts**, explicitly instructing AI Studio to implement **one phase at a time** and stop at each working acceptance gate. It is unsafe to request all systems in one huge unverified code generation. ChatGPT Work should follow the same principle, even if its tools and mode differ from AI Studio.

## Phase 1 — Real 3D foundation

Create React+TS app, compatible Three/R3F/Drei, typed states, real perspective 3D three-lane road, detailed starter car model/procedural fallback, chase camera, Home with DRIP RACE + exact subtitle, functional Play/Pause/Resume/Home, genuine loading errors. **Gate:** actually running 3D scene, camera behind car, no second render loop and functional state transitions. Hide unfinished menu buttons.

## Phase 2 — Driving physics and controls

Implement fixed-step simulation, units/lanes, speed/distance, cruise acceleration, smooth steering, braking, 0–100 Nitro resource, real boost, camera/wheels/brake lights, touch/swipe/keyboard. **Gate:** no teleportation, accurate speed/distance, pause freezes mechanics, deterministic 30-vs-60 render schedule tests.

## Phase 3 — Traffic, collision and Game Over

Six traffic categories, hazard types, pooled vehicles, relative speed, fair spawning/reachability, collision geometry/swept detection, 3 hearts/shield hook/grace, overtakes, near misses, scoring, difficulty, Game Over/Play Again. **Gate:** collision and bonuses accurate, no unfair instant blockages, no repeated impact damages, clean restart.

## Phase 4 — Coins, four power-ups and wallet

Build canonical wallet/persistence enough for collection; three coin types + weights, spatial pickup, exactly-once award, Magnet, Shield, Double Coins and automatic Nitro via same controller. **Gate:** real pickups + correct wallet; repeated collisions/magnet/Double Coins cannot duplicate; saves reload and Game Over never recredits.

## Phase 5 — Five-car Garage, purchase and upgrades

Five distinct real car models or verified detailed fallbacks, ownership states/prices, interactive showroom, free body paint, four upgrade categories, exact formulas/prices, same chosen vehicle in race and Home. **Gate:** purchases and upgrades correct/persisted, no unowned equips, performance reflects Garage numbers.

## Phase 6 — Five missions, eight achievements, five levels, daily rewards

Event-driven lifetime and daily tracking; explicit idempotent claims; calendar/timezone; level selection and basic distinct environment configs; profile screens. **Gate:** correct missions and achievements, day rollover, 7-day cycle, permanent unlocks, no double settlement or mission claim.

## Phase 7 — High-fidelity art and five environments

Confirm licensed 3D files, upgrade PBR cars/road/traffic, five actually different rendered locations, lighting/HDRI, LOD/instancing/particles/camera polish. **Gate:** visual difference and no broken model paths, night visibility, low graphics usability, no gameplay regression.

## Phase 8 — Part 8 V2 HUD and sound

Premium HUD and controls, dynamic actual-speed-based engine audio with virtual RPM/five profiles, road/traffic/spatial audio, coin/power-up/impact SFX, proper music and volume, haptics optional, refined menus. **Gate:** values honest, all visible controls work, audio unlocked gracefully, no duplicate loops, accessibility and restart clean.

## Phase 9 — Production reliability and mobile optimization

Graphics presets/Auto DPR, memory/asset lifecycle, save versions/recovery/migrations, multi-tab conflicts, context loss, loading failures, backgrounding and audio recovery; export/import optional, PWA optional. **Gate:** real measured improvements, physics unchanged by quality, recoverable errors, no unbounded object growth.

## Phase 10 — Final full regression and release readiness

Inspect source and dependencies, run type/build/tests, exercise complete gameplay/economy/menus/progression, test actual browsers/devices when possible, verify licenses/assets, fix defects without deleting features. Create a final report with VERIFIED WORKING / IMPLEMENTED BUT NOT VERIFIED / INCOMPLETE / BLOCKED and PASS / FAIL / NOT RUN / BLOCKED. No false deployment or production-readiness claim.

### Detailed instructions for running any phase

- Continue within the **same actual project/repo**; do not rebuild from scratch between phases.
- Identify existing files and current tests before changes.
- Implement one coherent functional subset; preserve working features.
- Run actual build, TypeScript, unit and browser checks.
- Inspect real 3D preview and note asset limitations.
- Report files touched, working features, tests run/results, blockers and next dependent step.
- Do not silently mark future systems complete. A reliable playable prototype beats a menu full of fake functions.

---

# 15. Consolidated acceptance criteria and high-risk regression tests

**Fundamental demo**
- [ ] Real WebGL 3D highway with three lane centers and actual player car.
- [ ] Play, Pause, Resume, Home, Game Over, Restart all work; pause freezes simulation.
- [ ] Speed meter = actual m/s × 3.6; distance = speed × simulation dt; camera/road/traffic agree.
- [ ] Lane changes are continuous, bounded and collision-accurate; touch/keyboard multi-input safe.
- [ ] Traffic relative speed correct; seeded encounter test shows safe reaction route; swept collision works.
- [ ] Three valid unprotected contacts lose three hearts (with grace), shield blocks exactly one.
- [ ] Score equals floor(distance)+30 overtakes+50 near misses without duplicates.

**Coins/economy**
- [ ] Regular=10, bonus=25, rare=50; pickup collected once, in actual 3D.
- [ ] With Double Coins, rare=100; Magnet+Double Coins still one collection.
- [ ] Wallet is shared across HUD/Home/Garage and credited at pickup, never again at Game Over.
- [ ] Buying vehicle or upgrade deducts correct amount exactly once, no negative wallet.
- [ ] Saved purchases/upgrades/paint survive reload; localStorage errors identified honestly.

**Garage and actual physics**
- [ ] Five distinct vehicle identities/models and exact prices.
- [ ] All performance values and upgrade formulas govern the same driving simulation.
- [ ] Unowned cars cannot be equipped or upgraded; no paid color requirement.
- [ ] Fully upgraded Legendary car reaches configured normal 258 km/h and possible 322.5 km/h Nitro cap, subject to actual acceleration/time.
- [ ] Collision dimensions and traffic fairness consider vehicle and upgrade changes.

**Progression**
- [ ] All five daily missions track correct validated units; Nitro Master excludes automatic pickup boosts.
- [ ] Eight achievements unlock permanently and claim at most once.
- [ ] Levels unlock at 0/2,000/8,000/20,000/40,000 lifetime meters.
- [ ] Seven-day reward sequence 50,75,100,150,200,300,500; skips reset; no same-day double claim.
- [ ] Midnight during running changes mission attribution, not the race itself; gameplay timers freeze when paused.
- [ ] Race high-water-mark checkpoint prevents lifetime distance/time double counting at Game Over.

**Visual / audio / mobile**
- [ ] Actual 3D selected car and correct paint in Home, Garage and running race.
- [ ] Five truly different environments with proper lighting and visible traffic.
- [ ] Part 8 V2 audio obeys actual speed/load, selected car, Nitro and collision state.
- [ ] Only one player-engine loop and one music state active; mute/volume settings work.
- [ ] Browser audio block and haptics unavailability do not stop gameplay.
- [ ] Low graphics preserves collision/scoring; Auto quality does not oscillate wildly.
- [ ] Long/repeated runs show bounded traffic, pickups, GPU resources and event listeners.
- [ ] Corrupted save/context-loss/reload/backgrounding handled with no fake success or reward duplication.
- [ ] Real-phone verification listed separately from desktop viewport emulation.

**Evidence requirement:** Never mark a test PASS unless it was actually executed. Report platform, browser, build hash/version and observed results where available. An unchecked acceptance box remains **NOT VERIFIED**.

---

# 16. Reusable prompts and troubleshooting policies

The prior conversation produced four specialized prompts. Retain these policies in Work:

**A. Bug repair:** inspect actual error + source, reproduce, identify root cause, implement smallest maintainable change, avoid deleting functionality, add regression test, run checks, report verified fix and remaining risks.

**B. Graphics improvement:** inspect actual car assets, material/lighting/reflection, asphalt, camera and performance; improve highest-impact shortcomings, preserve physics/collision/economy, never invent high-quality licensed assets or fake before/after FPS.

**C. Low FPS:** measure draw calls, triangles, textures, shadows, overdraw, DPR, React renders, object pooling and loop duplication; optimize bottlenecks. Do not reduce simulation speed or remove collision to claim higher FPS. Report measured before/after where available.

**D. Wallet/progression bug:** trace event ID → validation → transaction → canonical progression mutation → persistence → UI; check stale snapshots, duplicate settlement, claim identity, repeated pickup IDs, concurrent tabs; preserve valid progress, test idempotency; do not reset ownership/coins to hide defects.

---

