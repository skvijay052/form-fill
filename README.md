# FormFill Autofill

A lightweight Android Autofill Service for user-controlled form filling, including supported TTD pilgrim forms.

## TTD custom dropdown helper

Some TTD controls do not expose standard Android Autofill. FormFill includes an optional Accessibility helper that attempts to select saved Gender and Photo ID Proof values when those visible dropdowns are unselected.

1. Open FormFill and save each pilgrim's Gender and Photo ID Proof values.
2. Tap **Enable TTD dropdown helper**.
3. In Android Accessibility settings, select **FormFill TTD Dropdown Helper** and enable it only if you are comfortable granting this access.
4. Return to FormFill, then open the official TTD app. If a custom control is not exposed through Accessibility, select it manually.

Android Accessibility is a powerful permission and may display a security warning. The helper is restricted to the official TTD package (`com.ttdapp`), attempts only the two dropdown fields, does not log or upload screen contents, and does not submit bookings. Selection is not guaranteed because TTD controls may change between app versions.

## Safety and privacy

- Profile data is stored locally and encrypted with Android Keystore-backed AES-GCM.
- No internet permission, analytics, or cloud sync.
- The app suggests values for user review; it does not submit bookings.
- Autofill support depends on the app exposing fields through Android Autofill or Accessibility APIs.

## Build

Use the **Build Android APK** workflow in GitHub Actions. Download the generated APK from the workflow run's Artifacts section.

Requires Android 8.0+ (API 26) for Autofill Framework.
