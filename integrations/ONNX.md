# ONNX integration

The project reserves two adapters: `onnx-htr` and `onnx-math`. Model weights are not redistributed in this archive.

## Bitmap pipeline to add
1. Compute bounds of selected vector strokes.
2. Add 10–15% padding.
3. Render to a clean off-screen white bitmap, never a screen screenshot.
4. Normalize stroke thickness and resize to model input.
5. Run encoder/decoder through ONNX Runtime Android.
6. Decode tokenizer/vocabulary and return candidates.

## Suggested split
- Russian HTR: fine-tuned handwriting transformer exported to ONNX.
- Formula: image-to-LaTeX encoder/decoder exported to ONNX.

The UI and registry do not need changes when these implementations are added.
