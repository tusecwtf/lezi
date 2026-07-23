# Keep useful source and line metadata for actionable release crash reports.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod

# Room generates direct adapters, but retaining the database declaration and
# persisted entities makes release shrinking resilient to schema/adapter changes.
-keep class com.lezi.babylog.core.database.LeziDatabase { *; }
-keep class com.lezi.babylog.core.database.**Entity { *; }
