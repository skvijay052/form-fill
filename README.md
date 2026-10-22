# FormFill Autofill

A lightweight Android Autofill Service for user-controlled form filling, including supported TTD pilgrim forms.

## Safety and privacy
- Profile data is stored locally and encrypted with Android Keystore-backed AES-GCM.
- No internet permission, analytics, or cloud sync.
- The app suggests values for user review; it does not bypass CAPTCHA/OTP or submit bookings.
- Autofill support depends on the app/browser exposing fields through Android Autofill APIs. TTD custom controls may not be fillable in every browser.

## Build
Use the **Build Android APK** workflow in GitHub Actions. Download the generated APK from the workflow run's Artifacts section.

Requires Android 8.0+ (API 26) for Autofill Framework.
