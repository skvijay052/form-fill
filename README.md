# FormFill Autofill

A lightweight Android Autofill Service for user-controlled form filling, including supported TTD pilgrim forms.

## TTD form support

FormFill provides Android Autofill for supported fields. Some custom controls in the official TTD app, including Gender and Photo ID Proof dropdowns, may not expose standard Autofill fields and may need to be selected manually.

## Safety and privacy

- Profile data is stored locally and encrypted with Android Keystore-backed AES-GCM.
- No internet permission, analytics, or cloud sync.
- The app suggests values for user review; it does not submit bookings.
- Autofill support depends on the app exposing fields through Android Autofill or Accessibility APIs.

## Build

Use the **Build Android APK** workflow in GitHub Actions. Download the generated APK from the workflow run's Artifacts section.

Requires Android 8.0+ (API 26) for Autofill Framework.
