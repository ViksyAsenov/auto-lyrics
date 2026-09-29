# AutoLyrics

AutoLyrics is an Android Auto application that displays synchronized lyrics directly on your car's screen for the music you are currently playing.

## Features

- **Live Synchronized Lyrics:** Displays karaoke-style synchronized lyrics on your car's display.
- **Media Session Integration:** Automatically detects the currently playing track. Works seamlessly with YouTube Music and other media players.
- **Real-Time Fetching:** Fetches lyrics on-the-fly using the free and open-source [Lrclib API](https://lrclib.net/).
- **Manual Sync Adjustments:** Tap the left or right side of the car screen to manually adjust the lyric sync offset (±50ms) in case the track and lyrics are slightly out of sync.
- **Native Android Auto Integration:** Built using the `androidx.car.app` library, rendering custom surfaces for a smooth, distraction-free scrolling experience.

## How It Works

1. The app runs a `NotificationListenerService` (`MediaTrackerService`) to listen to active media sessions and grab current track metadata (Artist, Title) and playback state.
2. It cleans the track title and requests synchronized LRC lyrics from the `lrclib.net` API.
3. The lyrics are parsed and displayed on an Android Auto `Surface`, smoothly scrolling to the active lyric line using a custom render loop based on the track's current timestamp.

## Prerequisites

To run this project, you need:

- Android Studio
- An Android Device running Android 6.0 (API 23) or higher.
- Android Auto companion app (or the Desktop Head Unit for testing).

## Setup & Testing

1. Clone this repository.
2. Open the project in Android Studio.
3. Build and install the app on your Android device.
4. Open the app and grant notification access.
5. Connect your phone to your car (or launch the Android Auto Desktop Head Unit emulator).
6. Open the **AutoLyrics** app from your car's launcher.
7. Start playing music on your phone and sing along!
