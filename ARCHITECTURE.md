# Bekocat Architecture

```
core/timeline   (pure Kotlin/JVM, 73 unit tests — the single source of truth, no Android)
 ├─ timeline/   Rational time · immutable Project model (schema v3, kotlinx.serialization, migrations 1→2→3)
 │              TimelineEditor (insert/overwrite/split/ripple/trim/slip/slide/roll/move/speed ramps/freeze/
 │              reverse/groups/nest/markers/snap) · History (snapshot undo/redo, transactions)
 │              KeyframeEvaluator + safe Expression language · Easing · TextAnimator (grapheme-safe)
 │              RenderPlanner → FramePlan (layers, transitions with handles, parenting, 2.5D camera/homography,
 │              motion-blur samples, masks, track mattes, nested comps, captions, AI matte, fill patch)
 │              Catalogs (41 video FX, 8 audio FX, 51 looks, 40 text presets, 20 caption styles, 37 transitions)
 │              Templates (25) · Assistant (intent parser + JSON schema validator) · ProjectPackage (.bekocat)
 ├─ audio/      DSP: biquad EQ, compressor, look-ahead limiter, gate, de-esser, spectral denoise, dereverb,
 │              reverb, BS.1770 loudness, WSOLA time-stretch, Lanczos resampler, ducking, silence, beats; AudioMixer
 ├─ export/     WAV, SRT/VTT/ASS read+write, GIF encoder (median-cut + dithering + LZW), loudness meter
 └─ analysis/   scene detection, NCC point tracker, stabiliser, temporal median fill, subject reframe

engine          (Android library, OpenGL ES 3)
 ├─ gl/         EGL core, programs, textures (2D, 3D LUT), FBOs; shaders: layer, 17 blend modes, 37 transitions,
 │              FX uber-shader, separable blur, optical-flow frame blending
 ├─ decode/     MediaCodec → SurfaceTexture frame-accurate decoding (seek + pts matching)
 ├─ text/       StaticLayout text (HarfBuzz/ICU shaping, Arabic joining preserved while animating), text on path,
 │              shapes, mask rasteriser
 ├─ render/     Compositor: executes a FramePlan → premultiplied RGBA FBO (same code for preview and export)
 ├─ audio/      AudioConform (decode → 48 kHz stereo float cache), stem sources
 ├─ preview/    PreviewEngine: audio-clock sync, Choreographer, adaptive resolution, loop ranges, snapshots
 └─ export/     Exporter: MediaCodec + MediaMuxer (H.264/HEVC/AV1 when the device has encoders), GIF,
                PNG/JPEG sequences, WAV, M4A, stems, captions; two-pass loudness; output verification

ai              (Android library)
 ├─ ModelManager   catalog, resumable HTTP-Range download, SHA-256 verify, free-space check, delete (process singleton)
 ├─ CaptionEngine  sherpa-onnx: Silero VAD → Whisper (ar/en, translate→en) → word distribution; optional diarization
 ├─ MattingEngine  ONNX Runtime MODNet, temporal smoothing, PNG matte cache
 ├─ SeparationEngine ONNX Runtime Spleeter 2-stem, STFT/Wiener masks, streaming overlap-add
 ├─ PlannerEngine  MediaPipe LLM Inference (Qwen2.5-0.5B) → JSON → validated intents
 └─ VideoTools     scene cuts, tracking, stabilisation, reframe path, object removal (temporal fill)

app             (Compose UI, Arabic-first RTL + English)
 ├─ onboarding/  language, offline promise, optional model downloads
 ├─ home/        projects, 25 templates, .bekocat import, duplicate, delete
 ├─ editor/      EditorViewModel (all edits go through History), EditorScreen (SurfaceView preview + gizmos),
 │               TimelineView (Canvas: thumbnails, waveforms, keyframes, drag/trim/pinch), 15+ panels
 └─ settings/    language, model manager, storage/cache, licences
```

## Key decisions
1. **Rational time** in the model; conversion to µs only at MediaCodec boundaries.
2. **One render path**: `RenderPlanner` (pure Kotlin, testable) → `Compositor` (GL). Preview and export run the
   identical plan, so what you see is what you export. Media3 is not used — its sequence model cannot composite
   arbitrary overlapping layers, masks or blend modes.
3. **Non-destructive**: AI results (mattes, stems, fill patches, captions) are cached files referenced by the model,
   each recorded as an `AiJob` with model id/version; deleting caches never corrupts the project.
4. **Assistant safety**: rule parser first; the LLM may only emit JSON intents from a fixed schema, validated and
   bounded. No code execution path exists. A whole plan commits as one undo step.
5. **Network**: `INTERNET` is used solely by the model manager after the user taps Download. Media never leaves the device.
6. **Crash safety**: atomic saves with `.bak`, edit journal, rolling backups (5), bounded JSON parsing of untrusted packages,
   zip-slip and zip-bomb protection on `.bekocat` import.
