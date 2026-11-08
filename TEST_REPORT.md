# Test report — 2026-11-08

## Executed
| Suite | Where | Result |
|---|---|---|
| `core/timeline` JVM unit tests (73): rational time, all edit ops, undo/redo, codec + migrations 1→3, malicious JSON, atomic store/backups, render planner (transform, parenting, camera homography, masks, mattes, transitions with handles, captions), expressions, text animator, catalogs, assistant parser (all spec example commands), templates, .bekocat zip-slip/zip-bomb, audio DSP (EQ, compressor, limiter, denoise, loudness BS.1770, WSOLA, resampler, ducking, beats, silence), GIF/WAV/SRT/VTT/ASS, scene cuts, tracker, stabiliser, temporal fill, reframe | Local JDK 21 | **73 passed, 0 failed** |
| `engine` instrumentation (3): decode → GPU composite → encode → mux → verify | Android 15 x86_64 emulator (software GPU, SwiftShader) | **OK (3 tests)** — see `docs/evidence/` |
| ↳ `exportsMp4WithVideoAndAudio` | 2 clips + dissolve + Arabic text + captions → H.264/AAC 432×768 | 2 tracks, 6.0 s ± 0.15 |
| ↳ `exportsGifWavSrt` | GIF89a header, WAV duration 6.0 s, SRT contains Arabic text | pass |
| ↳ `compositorRendersFrameWithText` | frame at 1 s: decoded video + Arabic/English text + caption | pass; frame saved as `docs/evidence/compositor_frame_t1s.png` |
| Debug + R8 release builds | Gradle | pass (release arm64 ≈ 82 MB) |
| Spleeter tensor conventions | Python prototype | SDR 25 dB on a synthetic mix |

## Bugs found by the emulator run and fixed
1. FX/transition shaders: 34-branch `else if` chain exhausted the GLSL parser stack on SwiftShader → flattened to independent `if`s (branches are mutually exclusive).
2. Unused samplers of different types shared texture unit 0 → `GL_INVALID_OPERATION`, blank frames on strict drivers → every sampler now gets its own unit at link time.
3. Encoder capability check rejected sizes whose *real-time* rate exceeded the encoder's profile → offline export now only requires size support.

## Not executed (honest status)
- No run on a **physical phone**; the emulator has no hardware encoder (HEVC/AV1 untested) and software GL only.
- App UI flows (timeline gestures, panels, onboarding) compiled but not driven by UI tests.
- AI engines (Whisper captions, MODNet, Spleeter, Qwen planner) compiled and models downloadable, but **no on-device inference run** yet (2 GB emulator without acceleration cannot hold them reliably). Accuracy/speed numbers are therefore not claimed.
- Acceptance scenario (spec §24) not executed end-to-end.
