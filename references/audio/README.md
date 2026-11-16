# DRIP RACE — prepared audio candidates

23 actual user-created source files, preserved unchanged. This folder contains 8 WAV loop candidates and 15 MP3 one-shots with stable runtime names. All 23 were decoded again locally and in the browser; output frame counts matched expected values. Sample headroom is recorded in decode-checks.json; MP3 reconstruction can exceed the nominal PCM -6 dB peak target, so use the measured exported-file values.

The current conversion decodes source audio as float32 so decoder peaks above full scale survive until gain attenuation; PCM is quantized only after balancing. The earlier integer conversion is superseded. The preparation trims measured quiet edges with attack/tail margins, uses equal-power tail-to-head overlap for loops and balances copies with conservative peak/RMS targets. WAV loops avoid lossy-codec padding. The musical loop transitions and effect character are not approved merely because decoding passed. Use the audio-review page's “Hear loop transition” button and compare originals if needed. On 16 November 2026 the user explicitly reported: “All eight loops sound fine.” The eight-loop listening review is complete; see listening-approval.json. Integrated game mixing remains Phase 8.

Loop timing comes from decoded PCM, not browser timers. In the eventual AudioManager, use one managed AudioContext and separate ENGINE / GAMEPLAY_SFX / AMBIENCE / MUSIC / MASTER buses. Playback unlock requires a user gesture where the browser enforces it. Pause/background/restart must stop or suspend the appropriate nodes without duplicate engine loops. This folder is not an implemented AudioManager or a final in-race mix.

There is one original idle/steady/high engine set. Five distinct car profiles remain Phase 8 tuning work, using this set or additional original material; do not claim five separate recordings exist.

Private teacher-demo homework use. Provenance is the project owner's explicit statement that all 23 originals are self-created. No public license is asserted.
