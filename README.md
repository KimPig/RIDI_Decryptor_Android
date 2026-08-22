# RIDI Decryptor for Android

RIDI Decryptor for Android is an offline, root-based Android utility that converts RIDI publications already downloaded by the official Android app into EPUB, PDF, or comic archives.

It does not sign in to RIDI, register a device, call a RIDI API, or download books. The Android manifest intentionally omits the `INTERNET` permission. Root access is used only to read local files belonging to the official app and copy the required data into this app's private cache for processing.

> [!IMPORTANT]
> This is an experimental compatibility tool. It supports Android 7.0 (API 24) and later, but has currently been tested only on a rooted Android 10 device. Root implementations, ROM storage layouts, official-app versions, and publication formats can differ.

> [!WARNING]
> Decrypted publications have been reported to retain purchaser-, account-, transaction-, device-, or forensic identifying information. For EPUB only, this application removes the two specifically observed RIDI markers described below, but it cannot detect or anonymize every possible identifier or watermark. Never share, upload, or redistribute decrypted files.

Use this software only for the personal archival of content you have legally purchased or rented, subject to applicable law, service terms, and the rental period.

## Why a local decryptor

RIDI Archive and RIDI Archive for Android were independent download clients. They authenticated with RIDI and reproduced official-client requests, creating account, compatibility, protocol, and service-policy risk.

This project uses a narrower model:

1. The official RIDI Android app handles sign-in and download.
2. RIDI Decryptor reads only the files already present on the device.
3. Required source files are copied into the decryptor's private cache.
4. Decryption and validation run against those private working copies.
5. The result is exported locally, while the official encrypted source remains untouched.

This removes independent authentication and server-request emulation from the application. It does not guarantee compatibility, legality in every jurisdiction, or the absence of identifying metadata in the output.

## Safety boundary

The root reader is deliberately restricted:

- Known `com.initialcoms.ridi` private and app-specific storage locations are opened only for reading.
- Official RIDI files are never renamed, overwritten, deleted, moved, or granted different permissions.
- Files under `/data`, `Android/data`, shared storage, and removable storage follow the same read-or-copy rule.
- Privileged copies may be written only into RIDI Decryptor's private cache.
- Decryption, ZIP reconstruction, hashing, and validation operate only on app-owned working copies.
- Cleanup removes only this application's temporary files and metadata snapshots.
- `Annotations.db` is never read, copied, or modified.
- No analytics, advertising, crash-reporting SDK, updater, or network client is included.

Root is inherently powerful and increases device risk. Review the source and build the app yourself before granting access.

## Supported content

| Local source | Output | Main validation |
| --- | --- | --- |
| EPUB | `<title> (<bookId>).epub` | ZIP/EPUB signature and package structure |
| PDF | `<title> (<bookId>).pdf` | `%PDF` signature |
| Comic or webtoon | `<title> (<bookId>) [Original].zip` or `[Standard].zip` | Image signatures, page order, decode check, and SHA-256 verification |

Plain or free publications are copied only after the same format checks. Existing valid exports are detected locally and marked **Decrypted**.

## RIDI Android protection model

The description below documents formats observed through static analysis of supplied official RIDI Android APKs and locally downloaded packages. It is not an official RIDI specification and must not be assumed to cover every app or publication version.

RIDI uses AES-based client-side protection. AES itself is not broken or brute-forced here. An authorized reader must retain the downloaded ciphertext, a persistent device identifier, the key-envelope logic, and enough publication state to open the content. On a rooted device, software with permission to read all of that local material may be able to reconstruct the same content key used by the official client.

Protect the rooted device, the official app's private data, the device identifier, encrypted packages, and decrypted output. Possession of these materials can weaken the practical protection provided by otherwise strong cryptography.

### Device identifier and metadata

- The persistent device identifier is read from the official app's default preferences using `device_id`, with `uuid` supported as a legacy fallback.
- The value is held locally and is never transmitted by this application.
- Book titles, authors, formats, rental state, saved paths, comic quality, page counts, `seriesId`, and `displayOrder` are read from a private snapshot of the official app's local Realm database.
- Official `seriesId` groups volumes, and `displayOrder` determines their order inside a series. Conservative title parsing is used only when official series metadata is absent.
- Cover previews come only from locally stored official-app cover files copied into private cache.

### EPUB and PDF

EPUB and PDF use an observed device-bound `.dat` key envelope:

1. Take the first 16 UTF-8 bytes of the persistent device identifier as the AES key.
2. Decrypt the complete `.dat` entry with AES-128-ECB/NoPadding.
3. Verify that the decrypted envelope begins with the complete device identifier.
4. Skip the device identifier and the following 32-byte metadata area.
5. Read the next 16 bytes as the publication content key. With a normal 36-byte UUID, the key begins at byte offset 68.

The publication payload is then processed according to its format:

- **EPUB:** AES-128-ECB/NoPadding over the complete payload; the result must be a valid ZIP-based EPUB.
- **PDF:** a 16-byte IV followed by AES-128-CBC ciphertext; PKCS#7 padding is checked and removed, and the result must begin with `%PDF`.
- **Plain/free content:** copied only after its expected file signature is confirmed.

#### Observed EPUB marker layout

Local comparisons of decrypted Windows and Android EPUBs found the same two account-linked forms on both platforms:

- Major XHTML documents repeatedly contained a standalone 66-character sequence made only from `U+2060` (Word Joiner) and `U+2063` (Invisible Separator). It occupies 198 bytes in UTF-8 but is invisible during normal reading. In the compared Windows account sets, the sequence stayed the same across different books from one account and changed between accounts.
- `OEBPS/content.opf` contained `<meta name="book-token">...</meta>`. The observed 44-character Base64 value decodes to 32 bytes and changed with both the account and the publication.

For the same account and publication, the Android EPUB's internal files, the invisible sequence, and the `book-token` matched the Windows result. Cover/body images, CSS, fonts, and navigation resources showed no account-specific byte differences in the compared samples. No additional marker was found in ZIP archive comments, entry comments, extra fields, or trailing ZIP data.

After EPUB decryption, known-marker removal is enabled by default and can be disabled in Settings. When enabled, the private working copy is inspected and rebuilt before export:

1. Detect standalone 66-character sequences made from `U+2060` and `U+2063` in XHTML/XML text.
2. Detect and remove the observed OPF `book-token` metadata element.
3. Preserve every unrelated entry byte-for-byte after decompression and reject unexpected content changes.
4. Preserve source ZIP entry timestamps by default; optional normalization uses the configurable timestamp initially set to `2010-07-28 12:48:18`.
5. Write `mimetype` first and without compression.
6. Validate `container.xml`, the OPF manifest and spine, entry readability, entry order, and unchanged-entry hashes.
7. Scan the rebuilt EPUB again and require the two known marker forms to be absent.

If this privacy-processing stage cannot be completed safely, the decrypted EPUB is retained without destructive rewriting and the operation is reported as **Completed with warning**. Removing these known forms does not prove that an EPUB is anonymous.

### Comics and webtoons

Comic images are processed independently of the EPUB/PDF envelope path:

- The primary observed Android key is derived from the local device identifier.
- The legacy stream key derived from the book ID and a recovered publication key remain compatibility fallbacks for older local layouts.
- Only JPEG, PNG, GIF, or WebP results are accepted.
- The local cover becomes page `000`; content pages follow in strict natural reading order with zero-padded names.
- Pages are streamed one at a time to avoid loading an entire comic into memory.
- The completed archive is reopened, every image is decoded again, page continuity is checked, and per-page plus final-output SHA-256 values are calculated.

The official app's locally stored comic-quality value is displayed as **Original** or **Standard**. For the best available source, select original comic quality in the official RIDI app before downloading.

#### Observed comic privacy differences

In two-account Windows Standard samples, only `001.jpg`, `021.jpg`, `041.jpg`, and every twentieth page thereafter differed. The account difference was embedded in the JPEG image data itself: both compressed bytes and decoded pixels changed slightly, while filenames, dimensions, and visible artwork remained the same. No account string, EXIF/XMP token, JPEG comment, ZIP comment, or ZIP extra field was found.

The analyzed Android Standard and Original files did not show that Windows-specific twenty-page pattern. One Original sample matched a publisher-common copy from another storefront byte-for-byte through every JPEG end marker. However, the Android samples did not include a complete same-book comparison from two known accounts. The Android application therefore reports this limited result but does not claim to remove or rule out every possible comic marker; it provides EPUB marker removal only.

## Difference from the Windows decryptor

Both decryptors work only with local files and do not contact RIDI, but they support different client-specific storage and encryption layouts.

| Area | Android decryptor | Windows decryptor |
| --- | --- | --- |
| Local identity | `device_id` or legacy `uuid` from the official app's preferences | Device state reconstructed from the PC Settings datastore and Windows Credential Manager |
| Library metadata | Root-readable snapshot of the official Android `Library.realm` | Encrypted desktop datastore records under the current Windows account |
| `.dat` envelope | Complete envelope decrypted with AES-128-ECB/NoPadding; full device ID prefix verified; content key read after the following 32-byte metadata area | First 16 bytes used as IV; remaining envelope decrypted with AES-128-CBC and PKCS#7 removal; content key read from the observed fixed position |
| EPUB payload | Complete payload decrypted with AES-128-ECB/NoPadding | Legacy prefixed-IV AES-128-CBC payload, with entry-by-entry handling for the observed structured ZIP layout |
| EPUB post-processing | Known 66-character invisible markers and `book-token` metadata are removed from a private copy by default; source timestamps are preserved unless optional normalization is enabled | The same known-marker removal and source-time-preserving behavior are available, with optional timestamp normalization |
| PDF payload | 16-byte IV followed by AES-128-CBC/NoPadding ciphertext and validated PKCS#7 tail removal | Legacy prefixed-IV AES-128-CBC payload path |
| Primary comic key | Android device-derived `deviceId[2..18]`; legacy stream and publication keys are compatibility fallbacks | Book-ID-derived stream SHA-1 key; device-derived variants are compatibility fallbacks |
| Privileged access | Root is required to copy official private app data into this app's cache | Runs under the current Windows account without Android-style root |
| Official originals | Read directly only where necessary or copied to private cache; never modified | Read from the local PC library and datastores; originals retained unless a separate explicitly enabled cleanup feature is used |

The algorithms are not interchangeable merely because both applications ultimately produce EPUB, PDF, or ZIP output. Each implementation follows the locally observed format of its corresponding official client.

## Library and storage behavior

- The main library appears only after the root check succeeds.
- **Scan library** discovers locally downloaded books without changing official files.
- A rental ending in year 9999 is displayed as **Owned**.
- An expired rental with complete local files may be marked **Still decryptable**; this describes file availability, not usage rights.
- Realm `pageCount` is used for comics when available. A local index-based count is used only as a fallback.
- EPUB and PDF display their format rather than counting extracted reader-cache files.
- Reader extraction folders and unrelated `zzzz` files are not treated as authoritative DRM inputs.
- Internal/default storage is the recommended and best-tested official download location. Saved-path metadata is also used to discover compatible removable-storage layouts when available.

Outputs are written to `Download/RIDI_Decryptor` by default:

- Android 10 and later use scoped `MediaStore.Downloads` storage.
- Android 7 through 9 use the public download directory and request `WRITE_EXTERNAL_STORAGE` only through API 28.
- A different output folder can be selected through Android's Storage Access Framework.
- Existing files can be retained with `(1)`, `(2)`, and later suffixes, or replaced only after the new result has passed validation.

## Settings

- **Privacy processing**: removal of the two supported EPUB marker forms is enabled by default and can be disabled independently of timestamp handling. Comic marker removal is not provided on Android because the tested Android comic outputs did not exhibit the Windows-specific account differences this option targets.
- **Archive timestamps**: source EPUB and comic ZIP entry times are preserved by default. Optional normalization is disabled by default and replaces rebuilt archive entry times with a configurable value initially set to `2010-07-28 12:48:18`, using ZIP's two-second timestamp precision.
- **Official app access**: optionally stop the official RIDI app, after confirmation, before Scan or Decrypt copies required local files.
- **File handling**: choose Auto rename, Skip, or Replace for an existing output.

## Local tools

- **Import local package** copies a user-selected package into private app storage for offline inspection or processing.
- **Remove imported packages** deletes only those app-owned imported copies.
- **Clear temporary files** removes only RIDI Decryptor workspaces and Realm snapshots; imports and exported books are preserved.
- **Realm inspector** copies `Library.realm` into private cache and opens the snapshot read-only. Records are paged in groups of 50, and the snapshot is removed when the screen closes.
- **Book details** displays normalized metadata and can open the matching raw Realm record on a separate screen.
- **Device ID** is a masked, read-only value. Reveal/Hide changes only its local presentation.
- **Root environment** reports the raw local `su -v` result without guessing the installed root provider.

## Privacy

The application manifest does not request `INTERNET`. It therefore cannot contact RIDI or another remote service through Android's normal networking APIs. It does locally process:

- the official app's device identifier;
- downloaded publication packages and key envelopes;
- local Realm library metadata;
- locally cached cover images;
- user-selected imported packages; and
- decrypted output written to the chosen folder.

These values remain on the device. See [PRIVACY.md](PRIVACY.md) for the concise privacy inventory.

## Requirements

- Android 7.0 (API 24) or later
- A rooted device with a working `su` implementation
- The official RIDI Android app installed and initialized
- Publications downloaded through the official app
- Sufficient free space for a private working copy and the final output

Tested environment: rooted Android 10. Other supported Android versions and root/storage implementations remain unverified.

## Build

Use Android Studio's bundled JDK 17 and an installed Android SDK:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest lintDebug packageDebugRelease --offline
```

The version is calculated from the build date in the `Asia/Seoul` time zone as `YYYY.MM.DD`, with `YYYYMMDD` used as `versionCode`. For a reproducible date, pass `-PreleaseDate=YYYY.MM.DD` or set `RIDI_RELEASE_DATE`.

The packaged debug APK is written to:

```text
artifacts/apk/RIDI_Decryptor_Android-vYYYY.MM.DD-debug.apk
```

This APK is debug-signed and intended for testing and source inspection, not production distribution.

## Source layout

```text
app/src/main/java/com/kimpig/rididecryptor/
├── core/       Discovery, models, package preparation, and cryptography
├── root/       Narrow root reads, local metadata, and Realm snapshots
├── storage/    Output destinations, collision handling, and private cleanup
├── ui/         Library and series presentation
└── *.kt        Main, Settings, Details, and Realm inspector screens
```

The decryption core works on ordinary app-owned copies. Shizuku and ADB-assisted source adapters are possible future additions but are not currently implemented.

## Responsible use

RIDI Decryptor for Android is intended only for personal processing of locally downloaded content that the user is authorized to access. Do not use it to distribute copyrighted works, bypass access you do not possess, or share decrypted output. Keep the official encrypted download, verify every exported file, and comply with applicable law, service terms, and the rights of authors and publishers.
