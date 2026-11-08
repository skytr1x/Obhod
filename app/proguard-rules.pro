-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.skytr1x.obhod.proxy.TelegramProxyLibrary { *; }
-keep class com.skytr1x.obhod.proxy.NativeTelegramProxy { *; }
-keep class com.skytr1x.obhod.bridge.NativeBridge { *; }
-keep class com.skytr1x.obhod.bridge.NativeBridge$* { *; }
-keepclassmembers class com.skytr1x.obhod.vpn.ZapretVpnService {
    public boolean protect(int);
    public void onState(int, java.lang.String);
    public void onEvent(java.lang.String);
}
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
