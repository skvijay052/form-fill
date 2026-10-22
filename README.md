# FormFill Autofill

A lightweight Android Autofill Service for user-controlled form filling, including supported TTD pilgrim forms.

## TTD form support

FormFill uses Android's standard Autofill service and does not request Accessibility permission. Text fields can be filled when the official TTD app exposes them to Android Autofill. Some custom controls, including Gender and Photo ID Proof dropdowns, may need to be selected manually if TTD does not expose them as Autofill list fields.

## Safety and privacy

- Profile data is stored locally and encrypted with Android Keystore-backed AES-GCM.
- No internet permission, analytics, or cloud sync.
- The app suggests values for user review; it does not submit bookings.
- Autofill support depends on the app exposing fields through Android Autofill or Accessibility APIs.

## Build

Use the **Build Android APK** workflow in GitHub Actions. Download the generated APK from the workflow run's Artifacts section.

Requires Android 8.0+ (API 26) for Autofill Framework.
