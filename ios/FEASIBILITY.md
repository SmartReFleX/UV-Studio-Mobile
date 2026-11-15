# UV Studio Mobile on iPhone / iPad — feasibility study

Short answer: **a native iOS app that talks to the radio over USB cannot be built.**
It is not a question of effort or budget; it is a platform limit.

---

## Android vs iOS

| | Android | iOS / iPadOS |
| --- | --- | --- |
| USB host API for any device | Yes — `android.hardware.usb.host`, `UsbManager`, bulk endpoints | **No public equivalent** |
| Generic CDC-ACM device (VID `0x36B7`, PID `0xFFFF`) | Yes, directly | **No** — MFi-certified accessories only |
| Web Serial in the browser | Yes (Chromium) | **No** — every iOS browser must use WebKit, which lacks it |
| Special hardware inside the radio | Not needed | Required — MFi authentication chip + Apple certification |

The radio presents itself as a plain CDC-ACM device. Android can open it directly.
iOS exposes no generic USB driver to third-party apps: the only route is
`ExternalAccessory` plus the **MFi program**, which requires the accessory itself to be
certified by Apple and to contain Apple's authentication coprocessor. An existing
UV-K1/K5 cannot be retrofitted, and Quansheng produces no such accessory.

## Options, with effort

### 1. macOS application (not a phone)
Technically feasible: macOS has IOKit and DriverKit, including USB serial drivers.
Work: full rewrite of the protocol layer and the interface in Swift, roughly 3–4 weeks.
Limit: it runs on a Mac, so it does not answer the "from my phone" requirement.

### 2. Hardware bridge + iOS app
An ESP32-S3 (or Raspberry Pi Zero 2 W) attached to the radio over USB, exposing an API
over Wi-Fi or BLE. The phone talks to the bridge; the bridge talks to the radio.

```
iPhone  ──Wi-Fi / BLE──►  ESP32-S3  ──USB CDC-ACM──►  UV-K1 / UV-K5 V3
```

- Hardware: about 15–25 EUR (ESP32-S3 with USB host) plus power
- Bridge firmware: the protocol already written in Java ports to C, 2–3 weeks
- iOS app: SwiftUI plus URLSession or CoreBluetooth, 2–3 weeks
- Risk: flashing through a bridge adds one more hop, so one more way to fail, and a
  failure during flashing is expensive

This is the only route that really delivers flashing from an iPhone.

### 3. iOS app that only prepares files
Downloads firmware, verifies SHA-256, prepares an SD card — without writing to the radio.
Marginally useful; it does not replace the app.

### 4. Web app on iPhone
No. Web Serial does not exist in WebKit, and all iOS browsers are required to use WebKit.

## Distribution on iOS, if it existed

- App Store: 99 USD/year plus Apple review
- TestFlight: 90 days per build, still requires a paid account
- Sideloading (AltStore, free account): builds expire after 7 days
- Without a paid account there is no real distribution to a community

## Recommendation

For the project's purpose — people with a UV-K1/K5 who want to flash from a phone —
Android already covers it, with direct USB OTG and no extra hardware. An iOS version only
makes sense once a hardware bridge exists, and that is a separate project with its own
cost and its own risk.

Publishing anything labelled "iOS" before that would create expectations the platform
cannot meet.
