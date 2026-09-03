# Third-party notices

## Google ML Kit Digital Ink Recognition

- Android SDK dependency: `com.google.mlkit:digital-ink-recognition`
- Russian model is obtained through ML Kit's official `RemoteModelManager`.
- Use is subject to the Google ML Kit / Google APIs terms applicable to the developer and user.

## ONNX Runtime

- Android SDK dependency: `com.microsoft.onnxruntime:onnxruntime-android`
- Copyright Microsoft Corporation.
- License: MIT.
- Source: https://github.com/microsoft/onnxruntime

## Pix2Text MFR model package

- Quantized ONNX package and tokenizer are sourced from Mathorium.
- Copyright (c) 2026 Samir Uddin Ahmed.
- License declared by the source repository: MIT.
- Source: https://github.com/samirahmed007/mathorium
- Pinned source commit: `f2411279317da9cf3a12ceb453c9a96aca5b4743`.

The model files are bundled in the APK. On first use, the application copies them to private storage and verifies their expected byte size and SHA-256.

## JLatexMath Android

- Android dependency: `ru.noties:jlatexmath-android:0.2.0`.
- Used for offline rendering of converted LaTeX formula objects.
- License: GNU GPL v2.
- Source: https://github.com/noties/jlatexmath-android

## MyScript iink

MyScript binaries and recognition resources are not redistributed or shown as available engines. A developer must obtain and accept the applicable MyScript license before adding a private SDK package.
