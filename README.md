# FormFill Autofill

A lightweight Android Autofill Service for user-controlled form filling, including supported TTD pilgrim forms.

## TTD custom dropdown helper

Some TTD app controls may not expose standard Android Autofill fields. FormFill includes an optional accessibility fallback for Gender and Photo ID Proof controls.

1. Install the latest FormFill APK and open FormFill.
2. Tap **Enable TTD dropdown helper**.
3. In Android Accessibility settings, select **FormFill TTD Dropdown Helper** and enable it.
4. Return to FormFill, ensure your pilgrim profiles are saved, then open the official **TTDevasthanams** app.

The helper is restricted to the official TTD Android package com.ttdapp. It only tries to select Gender and Photo ID Proof when the visible control appears unselected. Existing selections are left alone. If the TTD screen does not expose those controls through Accessibility, select them manually.

Accessibility access is optional and powerful: Android requires it so the helper can identify and click custom controls. The helper does not log screen contents, use the network, bypass CAPTCHA/OTP, or submit bookings.

## Safety and privacy

- Profile data is stored locally and encrypted with Android Keystore-backed AES-GCM.
- No internet permission, analytics, or cloud sync.
- The app suggests values for user review; it does not submit bookings.
- Autofill support depends on the app exposing fields through Android Autofill or Accessibility APIs.

## Build

Use the **Build Android APK** workflow in GitHub Actions. Download the generated APK from the workflow run's Artifacts section.

Requires Android 8.0+ (API 26) for Autofill Framework.
