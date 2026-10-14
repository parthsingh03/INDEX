# INDEX proguard rules.
# Room generates database code at build time; keep the generated classes and
# the entity classes so minified release builds keep working.
-keep class com.parth.index.data.local.** { *; }
-keep class com.parth.index.data.model.** { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
