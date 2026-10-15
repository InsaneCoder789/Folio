# PDFBox loads GlyphList data and Noto fonts by resource name.
-keep class com.tom_roush.pdfbox.resources.** { *; }
# OpenCV binds native methods by their Java class and method names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Optional PDFBox JPEG-2000 codec is not bundled. Folio creates JPEG-backed PDFs
# and previews with Android PdfRenderer; it does not import/decode JPX PDFs.
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.gemalto.jp2.JP2Encoder
