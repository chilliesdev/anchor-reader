-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn org.bouncycastle.**
# PDFBox's optional JPX codec is not used for text extraction.
# Original source pages use Android PdfRenderer instead.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
