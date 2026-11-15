# iOS / iPadOS — not available

**Status: not implemented, and not possible as a native app on current iOS.**

This project talks to the radio over USB: the phone is the USB host and the radio
enumerates as a CDC-ACM serial device (VID `0x36B7`, PID `0xFFFF`). Android exposes
that to apps through `android.hardware.usb.host`. iOS does not.

## Why there is no iPhone version

Apple gives third-party apps no public USB host API. The only supported path is the
**MFi program** through `ExternalAccessory`: the *accessory* must be certified by Apple
and carry Apple's authentication chip. A UV-K1 or UV-K5 cannot be retrofitted, and
Quansheng does not make an MFi variant.

The browser route does not help either: every browser on iOS and iPadOS is required to
use WebKit, which does not implement Web Serial.

So an iOS app could draw the interface, but it could never reach the radio: no live view,
no flashing, no calibration, no app manager.

## What would actually work

| Option | Feasible | Notes |
| --- | --- | --- |
| **Android app** | Yes | Available now in [Releases](../releases) |
| **macOS app** | Yes | IOKit / DriverKit give real USB access; needs a Swift rewrite |
| **Hardware bridge + iOS app** | Yes | ESP32-S3 connected to the radio, exposed over Wi-Fi or BLE |
| **iOS app alone** | No | Blocked by the missing USB host API |

A bridge is the only route to flashing from an iPhone:

```
iPhone  ──Wi-Fi / BLE──►  ESP32-S3  ──USB CDC-ACM──►  UV-K1 / UV-K5 V3
```

That is a separate hardware project with its own cost, latency and failure modes.
See [FEASIBILITY.md](FEASIBILITY.md) for the technical detail and effort estimates.

## What we will not do

We will not publish an "iOS version" placeholder, a TestFlight build that cannot reach
the radio, or an App Store listing that promises flashing and cannot deliver it.

## Can you help?

If you have MFi experience, or you have built a USB-to-BLE/Wi-Fi bridge for a serial
device, open an issue with what you have in mind. Concrete offers are welcome; requests
for an iPhone app without one cannot go anywhere.
