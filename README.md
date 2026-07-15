# EXIF Remove

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
  - Orientation: keep or remove
  - Other EXIF data: keep or remove
- Ships with editable built-in templates (*Keep orientation*, *Remove
  everything*, *Remove location only*, *Scramble location & date*);
  create your own, set a default, restore the built-ins any time
- XMP, IPTC, comments and embedded thumbnails are always removed;
  ICC color profiles are kept so colors never change
- Supports JPEG, PNG and WebP without re-encoding (pixels are untouched);
  other image formats (e.g. HEIC) can optionally be converted to clean JPEGs
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

## Building

Requires JDK 17+ and the Android SDK.

```
./gradlew assembleRelease
```

## License

[GNU General Public License v3.0](LICENSE)
