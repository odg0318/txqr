# Keep fountain decoder
-keep class com.github.divan.txqr.FountainDecoder { *; }
-keep class com.github.divan.txqr.FountainDecoder$LtBlock { *; }

# Keep ZXing classes
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.barcodescanner.**
