# Keep JavascriptInterface methods accessible from WebView.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
