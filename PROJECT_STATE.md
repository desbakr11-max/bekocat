# PROJECT_STATE — Bekocat 1.0.0-dev

**Status: feature-complete in code, partially verified.** Every spec area is implemented; what is proven is listed in
`TEST_REPORT.md` and per-feature in `FEATURE_MATRIX.csv` (35 Verified · 20 Implemented-Unverified · 6 Implemented-Limited ·
1 Blocked · acceptance run and store signing left to the owner).

## What exists
- **core/timeline** (pure Kotlin, 73 tests): model v3, editing, keyframes/expressions, render planner, catalogs
  (40 text presets, 20 caption styles, 51 looks, 37 transitions, 41+8 effects, 25 templates), assistant, audio DSP,
  formats, vision algorithms, .bekocat packages.
- **engine** (GLES3 + MediaCodec): one compositor for preview and export; exports MP4 (H.264/HEVC/AV1 per device),
  GIF, PNG/JPEG sequences with alpha, WAV, M4A, stems, SRT/VTT/ASS. Verified on emulator.
- **ai**: model manager (resumable, SHA-256), Whisper captions + VAD + diarization (sherpa-onnx), MODNet matting and
  Spleeter 2-stem (ONNX Runtime), Qwen2.5-0.5B planner (MediaPipe), scene/track/stabilise/reframe/object removal.
- **app**: onboarding, home (templates, import, duplicate), editor with timeline + 15 panels, settings (models, storage, licences).

## Honest limits
1. Caption word timings are approximate inside each speech phrase (Whisper via sherpa exposes no word timestamps).
2. Translation only into English (Whisper translate); no Arabic MT model.
3. Stem separation is 2-stem (vocals/accompaniment); 4-stem is Blocked (no verifiable model).
4. Object removal uses temporal median fill: good for static camera / moving object; not generative inpainting.
5. Optical-flow frame blending is a lightweight shader approximation.
6. Matting model is portrait-trained.
7. APK ≈ 82 MB (arm64) because three native ML runtimes are packaged; models are separate downloads.

## Next steps
1. Install `app-arm64-v8a` on a real phone; run the acceptance scenario; record results in TEST_REPORT.md.
2. Run each AI model once on-device; record speed/RAM; tune defaults.
3. Add Compose UI tests for timeline gestures and panels.
4. Create a release keystore (kept outside the repo) and set the four GitHub Secrets for signed releases.
