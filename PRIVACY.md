# Privacy

RIDI Decryptor for Android is designed as an offline application.

- The Android manifest does not request `INTERNET`.
- No analytics, crash reporting, advertising SDK, or remote API client is included.
- On launch, the app checks whether root access is available before showing the library. If access is unavailable, the user can retry through the explicit root-access button.
- The app reads the official RIDI installation's local device identifier and selected downloaded book files. These values stay on the device.
- Working copies are stored in this app's private cache and removed after the export attempt.
- Successful files are written to `Download/RIDI_Decryptor`.

For EPUB output, the app removes the specifically observed standalone 66-character `U+2060`/`U+2063` sequences and OPF `book-token` metadata from its private working copy, then validates and rescans the rebuilt archive. Other identifiers, watermark forms, transaction data, or forensic differences may still remain. Do not share or redistribute exported files.
