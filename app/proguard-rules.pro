-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.skytr1x.obhod.nativebridge.** { *; }
-keep class com.skytr1x.obhod.TelegramProxyLibrary { *; }
-keep class com.skytr1x.obhod.NativeTelegramProxy { *; }
-keep class com.skytr1x.obhod.NativeBridge { *; }
-keep class com.skytr1x.obhod.NativeBridge$* { *; }
-keepclassmembers class com.skytr1x.obhod.ZapretVpnService {
    public boolean protect(int);
    public void onState(int, java.lang.String);
    public void onEvent(java.lang.String);
}
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
