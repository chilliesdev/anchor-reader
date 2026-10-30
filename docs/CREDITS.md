# Credits

The supplied original design is the visual reference. Its original SVG cover and diagrams are drawn with Compose Canvas.

The player background is the complete video shared by [@BrockRiddickIFB on X](https://x.com/BrockRiddickIFB/status/2108122719547973921), post `2108122719547973921`, media `2108122581588946944`. The visible original TikTok watermark identifies `@bluefit.stories`; it is retained. This records source provenance, not ownership or a license grant. No reuse license was supplied with the requested source.

The bundled `workshop_background.mp4` is an H.264 conversion of that source for Android playback, with its audio stream removed. The matching `workshop_poster.jpg` comes from its first frame and is used for still mode and video fallback. Settings shows the source attribution and links to the post. These assets replace the design's GOES-16 ocean video and poster.

The prepared lesson and diagram are identified by the supplied product file as original AnchorReader demo material. No third-party paper or diagram is bundled. Imported material remains on the user's device.

Dependencies include AndroidX, Jetpack Compose, Kotlin, [PDFBox for Android](https://github.com/TomRoush/PdfBox-Android), [Jsoup](https://jsoup.org/), [ONNX Runtime](https://github.com/microsoft/onnxruntime), [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/), and [Supertonic 3](https://github.com/supertone-oss-archive/supertonic). AndroidX and PDFBox for Android use Apache License 2.0. Jsoup and ONNX Runtime use the MIT License. Apache Commons Compress uses Apache License 2.0. Supertonic 3 uses the MIT License for engine software and the BigScience Open RAIL-M license for model weights, with an Attachment A synthetic-speech notice. Their upstream notices apply.

Synthetic Speech Notice: In accordance with Open RAIL-M terms, audio synthesized on-device via Supertonic 3 is synthetic speech generated locally from learner reading text.

Device voices belong to the installed speech engine; AnchorReader plays device speech and does not package or export voice recordings. Voice pack downloads fetch static model weights over HTTPS without sending user data or telemetry.
