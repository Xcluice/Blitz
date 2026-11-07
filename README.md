# Xcluice Blitz

Fast phone-to-phone file sharing for Android. The receiver opens a 5 GHz Wi-Fi Direct group and shows a QR code; the sender scans it, joins the group and pushes files over several parallel TCP streams. No internet needed.

- Kotlin + Jetpack Compose, liquid-glass UI
- Wi-Fi Direct group on 5 GHz (auto-band fallback), standard Wi-Fi join as fallback
- 4 parallel streams, 1 MiB chunks, zero-copy sends in turbo mode, optional AES-GCM
- Files saved to Downloads/Xcluice Blitz
