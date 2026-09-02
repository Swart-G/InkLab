# InkLab

Android tablet playground for comparing local handwriting recognition engines on the **same vector ink selection**.

## UX
- Draw naturally with pen/stylus.
- Switch to lasso and circle handwriting.
- A floating action bar appears with **В текст**, **Формула**, and **Compare**.
- Text and math engines are selected independently in Settings.
- Recognition result shows engine ID and latency.
- OCR Lab exposes every registered provider and its setup state.

## Implemented
- Kotlin + Jetpack Compose tablet UI.
- Custom paper canvas, vector strokes, eraser and lasso selection.
- Google ML Kit Digital Ink `ru-RU` provider. The model downloads to device on demand; recognition then runs locally.
- Provider registry and capability model.
- Integration slots for MyScript Text, MyScript Math, Russian ONNX HTR and ONNX Formula→LaTeX.

## Why vector ink
Online handwriting SDKs need stroke order/timing, while image OCR needs raster input. InkLab stores the source as vector strokes, so a future rasterizer can feed ONNX without losing the original ink required by ML Kit/MyScript.

## Build requirements
- Android Studio compatible with API 37 / AGP 9.1.x.
- Android SDK Platform 37.
- JDK 17+.
- Internet once for Gradle dependencies and the ML Kit Russian model.

The archive contains wrapper scripts/properties. If `gradle-wrapper.jar` is absent in your environment, open the project in Android Studio and regenerate the Gradle wrapper (`gradle wrapper --gradle-version 9.3.1`) or use the IDE's Gradle installation.

## Important limitations of this first test build
- MyScript is not redistributed because its SDK/resources require a MyScript developer package/license.
- ONNX weights/tokenizers are not bundled; adapter slots and setup docs are included.
- Formula objects are currently previewed as LaTeX text once a real formula provider is connected; a KaTeX/native renderer is the next UI step.
- Persistence/Room and full undo/redo are intentionally deferred until OCR engine benchmarking is stable.

## Structure
```
core/model/              vector ink data
core/recognition/        provider contracts + registry
recognition/mlkit/       working Russian Digital Ink adapter
recognition/stub/        declared external engines/setup states
ui/screens/              editor, settings, OCR lab
ui/components/           reusable floating panels
integrations/            MyScript and ONNX hookup notes
```
# InkLab
