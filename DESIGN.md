# InkLab visual direction

The UI avoids a stock settings-app look. The editor is the hero surface.

- warm neutral background (`#F5F2EC`) and elevated paper (`#FBF9F5`)
- near-black ink, low-contrast rules and a single violet interaction accent
- large 28–30dp rounding on primary surfaces
- compact floating tool dock rather than a permanent app toolbar
- context actions only appear after selection
- Text and Formula use distinct soft accents so the two OCR intents are recognizable at a glance
- OCR Lab looks like an instrumentation surface but stays visually consistent with the notebook
- tapping the active tool reveals compact contextual controls instead of opening a generic settings page
- selection uses a dashed violet boundary and stays directly draggable on the paper
- boards and settings reuse the same warm paper surfaces; no competing navigation style is introduced

For production: add true blurred glass on API levels where it is reliable, spring transitions, pen hover state, page zoom/pan, and formula typesetting.
