-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.skytr1x.obhod.nativebridge.** { *; }
-keep class com.skytr1x.obhod.TelegramProxyLibrary { *; }
-keep class com.skytr1x.obhod.NativeTelegramProxy { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
