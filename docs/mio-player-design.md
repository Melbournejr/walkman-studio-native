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

## Studio Edition previews

These PNGs are captured from the native Compose views with Robolectric API 35 and native Skia. The viewport sizes are 390 × 844 dp for dark and 360 × 800 dp for light, following the viewport sizes in the implementation request. Fixture tracks, signal values and drawn cover artwork live in the test only. The captures omit system status icons and phone bezels.

| Screen | Dark | Light |
| --- | --- | --- |
| Modern Now Playing | [Preview](mio-previews/now-playing-dark.png) | [Preview](mio-previews/now-playing-light.png) |
| Music Library | [Preview](mio-previews/library-dark.png) | [Preview](mio-previews/library-light.png) |
| Audio Lab | [Preview](mio-previews/audio-lab-dark.png) | [Preview](mio-previews/audio-lab-light.png) |
| Settings | [Preview](mio-previews/settings-dark.png) | [Preview](mio-previews/settings-light.png) |
| Retro Cassette | [Preview](mio-previews/retro-cassette.png) | |
| Playback Queue | [Preview](mio-previews/queue-dark.png) | |
| Equalizer | [Preview](mio-previews/equalizer-dark.png) | |

### Visual comparison

- **Now Playing.** The cover is wider, the source badge comes from reported PCM depth and sample rate, and the transport uses a larger outlined play key. The dark palette uses graphite and champagne gold. After the first capture, the lower panel grew on tall phones to reduce the empty band below the format strip. Short windows can scroll.
- **Library and Settings.** The album grid keeps the board’s square artwork and compact title stack. The mini-player now sits inside a thin rounded outline above the four destinations. Artist search remains in Tracks. Settings retains the existing OTA and About entries and their deep links.
- **Audio Lab and Equalizer.** The signal path shows source, decoder, DSP and observed Android output. The EQ plot draws actual configured gains for all ten bands. Presets update those gain values, while the disclosure opens the existing band and advanced controls. Its header switch controls Source Direct.
- **Cassette.** The embossed keys remain interactive and the reels still rotate in playback. The Studio finish adds a brushed face, brass C-60 label, perforated panel and lit play key. Its segmented display shows elapsed track progress. The board’s stereo level meters have no available per-channel signal source. Stop pauses and returns seekable audio to its start.
- **Queue.** Album art comes from the library and the current row is highlighted. The right-side action removes a song from the queue. Tap a row to play it. The board’s reorder handles are omitted because there is no reorder gesture in the existing queue.
- **Capture differences.** Fixture art is drawn in the test harness, the device bezel and status icons are absent, and copy uses the app’s existing localization. The captures are generated by host Compose/Skia. Device and hardware playback require their own validation.

Regenerate all eleven captures with `./gradlew :app:testDebugUnitTest --tests '*MioScreensTest'`. PNGs are written to `app/build/reports/mio-previews`.
