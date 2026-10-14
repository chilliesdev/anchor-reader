# Screenshot capture details

Captured on 14 October 2026 from source commit `21ca723627c3ece9eb370f7cf40bc9ee72644ca2`, the main revision used for this README update. The app UI was not changed for capture.

- Build: `./scripts/build.sh :app:assembleDebug`; debug APK installed in a fresh task-owned Android emulator.
- Device: Android API 37, ARM64, 1080 × 2400 pixels, density 420 dpi, standard font scale.
- Data: bundled **How offline web caching works** lesson and its authored questions. No imported personal file, connected account or OpenAI request.
- Method: normal MainActivity navigation, then `adb exec-out screencap -p` through `scripts/qa.sh screenshot`. PNG files are unedited full-screen captures; no mockups, cropping or redaction.
- Voice: this emulator has no usable offline English voice. The Player and Settings show the actual notice. Devices with an installed supported voice can use narration.
- Background: the Player shows the current silent workshop video. See [media credits](../CREDITS.md).

| Image | Captured state |
| --- | --- |
| [Library](library.png) | Fresh Library with the bundled lesson ready to start. |
| [Book overview](overview.png) | Reading actions, three ready practice questions, and the Concepts heading. The concept list continues below the fold. |
| [Player](player.png) | First concept, first complete sentence, diagram, workshop video and real missing-voice notice. |
| [Quick check](quick-check.png) | First authored question, opened from the second concept in the Player. No answer selected. |
| [Results](results.png) | Complete after two correct sample answers and one skipped answer. Only two of four concepts were visited. Completing the check did not mark all concepts read. |
| [Settings](settings.png) | Disconnected ChatGPT account, device narration notice and local progress controls. |

These images show the app's current appearance and a sample journey. They do not establish physical-device behavior, audible narration, accessibility acceptance or live provider sign-in/generation.
