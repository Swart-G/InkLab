# MyScript integration

MyScript iink is intentionally **not bundled**. It is a commercial SDK and requires your own developer package/license.

## Text provider
Implement `RecognitionProvider` in `recognition/myscript/MyScriptTextProvider.kt`:
1. Convert `InkStroke` points to iink pointer events, preserving stroke order and timestamps.
2. Use Russian recognition resources.
3. Return plain text and alternatives as `RecognitionResult`.

## Math provider
Implement a second provider using the iink Math content type and math recognition resources.
Return LaTeX (preferred for this test app) in `RecognitionResult.primary`.

Keep Text and Math as separate provider IDs so they can be benchmarked independently.
