# EXIF Remove

![EXIF Remove](fastlane/metadata/android/en-US/images/featureGraphic.png)

Remove EXIF and other metadata from photos and videos before you share them.

EXIF Remove registers itself as a share target: share one or more photos or
videos to it from any app, pick a cleaning template, and share the cleaned
copies onward. No network access, no tracking, free software.

## Features

- Appears in the Android share sheet for photos and videos, single or multiple
- **Templates** decide what happens to each metadata category:
  - Location (GPS): keep, remove, or randomize
  - Date & time: keep, remove, or randomize
  - Camera, software & author info: keep or remove
  - Other metadata: keep or remove
- Ships with editable built-in templates (*Remove everything*,
  *Remove location only*, *Scramble location & date*);
  create your own, set a default, restore the built-ins any time
- XMP, IPTC, comments and embedded thumbnails are always removed; ICC
  color profiles are kept so colors never change
- Keeping a category keeps it byte-for-byte: cleaning is surgical, not
  a lossy rewrite
- Supports JPEG, PNG and WebP without re-encoding (pixels are untouched);
  HEIC and AVIF are converted to clean JPEGs, since their metadata lives in
  container boxes that cannot be stripped in place
- Drops anything appended after the end of an image — a Pixel motion
  photo's video, an Ultra HDR gain map, a vendor debug trailer — all of
  which carry a full second copy of the original metadata
- Every cleaned image is **verified before you get it**: the output is
  re-parsed from scratch and rejected unless it is provably free of
  metadata, so a leak fails closed instead of being shared silently
- **Cleaning report**: after every clean, each file shows what actually
  came out of it — the tags removed, kept or scrambled, and the hidden
  payloads a before/after table cannot show, because once they are gone
  there is nothing left to list. Collapsed it is one line; expanded it is
  the evidence
- Cleans MP4, MOV and 3GP videos in place: device info, location, creation
  timestamps and other metadata boxes are wiped without changing a single
  stream byte — no re-encoding, works instantly even on large videos
- **Metadata viewer**: inspect any photo or video, grouped by category,
  and preview exactly what a template would remove, keep or randomize —
  powered by the same engine that does the real cleaning
- First-launch tutorial (replayable from the menu)
- Optional random output file names (file names can leak information too)
- Save cleaned copies to the gallery, or skip the picker entirely and
  clean with your default template in one tap
- Material 3 UI with dynamic colors, light/dark theme

## Screenshots

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="18%" alt="Home screen with cleaning templates" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="18%" alt="Choosing a template when sharing files" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="18%" alt="Cleaned files ready to share or save" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="18%" alt="Metadata viewer previewing a full clean" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="18%" alt="Metadata viewer previewing scrambled location and date" />
</p>

## Building

Requires the Android SDK (platform 37) and a JDK. The Gradle daemon JVM is
pinned by `gradle/gradle-daemon-jvm.properties`; Gradle provisions a matching
toolchain automatically via the foojay resolver if you do not have one.

```
./gradlew assembleRelease
```

## Privacy

No data collected, and no network access: the app does not request the internet
permission, so Android blocks it at the system level. See [PRIVACY.md](PRIVACY.md).

## License

Copyright (C) 2026 Jakob Kreft

EXIF Remove is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE) for the full text.

Every source file carries an [SPDX](https://spdx.dev/) identifier
(`GPL-3.0-or-later`).
