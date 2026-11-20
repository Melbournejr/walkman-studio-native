# Mio Player design audit

The Stage 12 interface routed library browsing, settings and Now Playing through the same Compose shell. Settings were a long flat list. The classic player inherited cassette hardware colors and labels, so it lacked a clear modern identity. Queue and signal details competed for space in the same sheet. The library already supported paged tracks and albums, folders, favorites, playlists and track search. The data layer had no separate artist browser or user-selectable sort order.

## Design system

Mio uses graphite surfaces, warm brass actions and cool secondary text in dark mode. The light palette uses warm paper and a darker bronze accent. Material 3 supplies control states, dialogs and sheets. The hierarchy uses Android's system typeface, with 28–32 sp for titles, 16 sp for primary metadata and 14 sp for supporting text. A 4 dp spacing base scales to 12 dp group gaps and 20–24 dp screen gutters. Buttons and list actions retain at least 48 dp targets. Artwork uses 12 dp corners and includes a neutral fallback.

Library browsing retains the paging flows and existing actions. Modern Now Playing uses an artwork-first layout and switches to two columns on wide windows. Queue and Audio Lab open independently. Audio Lab labels the source, decoder, DSP and AudioTrack observations separately. Settings expose six sections and accept direct navigation from update notifications.

## Migration

Mio Player replaces the public app label and artwork. PlaybackService and Media3 continue to own playback. Database names, preference keys, saved display IDs, playback position and file permissions keep their prior formats. An unset presentation preference now opens Modern. Saved cassette and vinyl preferences remain readable. The charcoal-and-brass Mio finish is the default when no retro palette has been saved. The cassette landscape lock continues to apply only while the cassette player is on screen.

Version 110 / 1.5.0 follows production version 108. Version 109 was used by the Stage 12 OTA test probe. The application ID, OTA URL policy and production certificate are unchanged. The name was checked in a preliminary public web search. Mio previously used MioPlayer for a media player on its DigiWalker devices. This is not trademark clearance.


## 1.5.0 changelog

- Revised navigation and library metadata with a branded launcher and splash.
- Added an artwork-first modern player with separate queue and Audio Lab sheets.
- Grouped existing settings under Appearance, Playback, Audio, Library, Updates and About.
- Retained the retro display IDs and persisted colors with original palette names.

## Approved design board previews

These PNGs are captured from the native Compose views with Robolectric API 35 and native Skia. The viewport sizes are 390 × 820 dp for dark and cassette and 360 × 690 dp for light, following the proportions of the two rows on the board. Fixture tracks, signal values and drawn cover artwork live in the test only. The captures omit system status icons and phone bezels.

| Screen | Dark | Light |
| --- | --- | --- |
| Modern Now Playing | [Preview](mio-previews/now-playing-dark.png) | [Preview](mio-previews/now-playing-light.png) |
| Music Library | [Preview](mio-previews/library-dark.png) | [Preview](mio-previews/library-light.png) |
| Audio Lab | [Preview](mio-previews/audio-lab-dark.png) | [Preview](mio-previews/audio-lab-light.png) |
| Settings | [Preview](mio-previews/settings-dark.png) | [Preview](mio-previews/settings-light.png) |
| Retro Cassette | [Preview](mio-previews/retro-cassette.png) | |

### Visual comparison

- Modern Now Playing uses the board’s square cover, left-aligned metadata, slim brass seek track and outlined transport. After reviewing the first capture, the favorite glyph became outlined when unselected and the transport groups gained more vertical separation. The accent and metadata still reflect the active theme and track.
- Library places two album covers per row above the compact mini-player and tab bar. The active underline now follows the label width. Artist search stays in the Tracks view, since the current library has no standalone Artist catalog. The mini-player includes the existing Next action.
- Audio Lab uses one outlined signal panel with an axial connector and four disclosure rows. The connected device, decoder format and processing summary use measured or configured values. Settings uses the same row style with taller targets for its six destinations.
- Retro uses charcoal housing, a brass paper label and seek accent, and a more prominent portrait header. The existing reel motion, mechanical keys, seek behavior, queue tools and landscape layout are retained. Its three-key transport and metadata placement differ from the standardized four-key illustration.
- Vietnamese labels, real art and accessibility-sized touch targets affect type and spacing compared with the board. Physical device rendering and hardware playback are separate from these host captures.

Regenerate all nine captures with `./gradlew :app:testDebugUnitTest --tests '*MioScreensTest'`. PNGs are written to `app/build/reports/mio-previews`.
