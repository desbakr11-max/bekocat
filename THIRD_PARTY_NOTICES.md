# Third-party components

## Libraries packaged in the APK
| Component | Version | Licence |
|---|---|---|
| AndroidX (core, activity, lifecycle, graphics-path) | see `gradle/libs.versions.toml` | Apache-2.0 |
| Jetpack Compose (UI, Foundation, Material 3, Icons Extended) | BOM 2024.12.01 | Apache-2.0 |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization | 2.1.0 / 1.9 / 1.7.3 | Apache-2.0 |
| sherpa-onnx (static-link AAR, from k2-fsa via Hugging Face) | 1.12.21 | Apache-2.0 |
| ONNX Runtime Android | 1.30.0 | MIT |
| MediaPipe Tasks GenAI (LLM Inference) | 0.10.33 | Apache-2.0 |
| JUnit 4, AndroidX Test (tests only, not shipped) | — | EPL-1.0 / Apache-2.0 |

## AI models (NOT bundled — downloaded on user request, see `MODEL_MANIFEST.json`)
| Model | Weights licence | Source |
|---|---|---|
| Whisper base / small (int8 ONNX) | MIT (OpenAI) | csukuangfj/sherpa-onnx-whisper-* |
| Silero VAD | MIT | csukuangfj/vad |
| pyannote segmentation 3.0 | MIT | csukuangfj/sherpa-onnx-pyannote-segmentation-3-0 |
| WeSpeaker CAM++ speaker embedding | Apache-2.0 | csukuangfj/speaker-embedding-models |
| MODNet portrait matting (ONNX) | Apache-2.0 | Xenova/modnet |
| Spleeter 2-stems (ONNX) | MIT (Deezer) | csukuangfj/sherpa-onnx-spleeter-2stems |
| Qwen2.5-0.5B-Instruct (.task) | Apache-2.0 | litert-community/Qwen2.5-0.5B-Instruct |

## Content
No fonts, music, sound effects, stock footage or third-party templates are bundled. All 25 templates,
40 text presets, 20 caption styles, 51 looks and 37 transitions are original to this project.
Text uses the device's system fonts or fonts the user imports. Logo and identity are original.
