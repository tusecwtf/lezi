# Keep useful source and line metadata for actionable release crash reports.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Room generates direct adapters, but retaining the database declaration and
# persisted entities makes release shrinking resilient to schema/adapter changes.
-keep class com.lezi.babylog.core.database.LeziDatabase { *; }
-keep class com.lezi.babylog.core.database.**Entity { *; }

# ZXing / journeyapps barcode scanner — readers are often reached via reflection
# and R8 otherwise strips MultiFormatReader / QRCodeReader (breaks release 扫码).
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# tink (androidx.security:security-crypto) references compile-only
# error-prone annotations; they are CLASS-retained and never present at runtime.
-dontwarn com.google.errorprone.annotations.**
