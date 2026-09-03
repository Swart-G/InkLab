# ONNX integration

The formula pipeline is implemented by `Pix2TextMathProvider` with ONNX Runtime Android.

## Formula bitmap pipeline
1. Compute bounds of selected vector strokes.
2. Add 10–15% padding.
3. Render to a clean off-screen white bitmap, never a screen screenshot.
4. Normalize stroke thickness and resize to model input.
5. Run the 384×384 encoder and greedy int8 decoder through ONNX Runtime Android.
6. Decode ByteLevel BPE and return LaTeX.

The model package is downloaded by `ModelPackageStore` from a pinned source commit. File sizes and SHA-256 hashes are part of `ModelCatalog`.

## Russian bitmap HTR slot

`onnx-htr` remains experimental. The available Cyrillic TrOCR checkpoints are much larger than a practical tablet package and do not currently ship as a verified mobile ONNX graph. Russian vector ink is already handled locally by ML Kit, so the app does not pretend that an untested heavyweight checkpoint is ready.

The UI and registry do not need changes when these implementations are added.
