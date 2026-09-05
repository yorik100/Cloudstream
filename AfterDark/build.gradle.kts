version = 39

dependencies {
    // CloudStream already provides coroutines at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Load Chromium's networking stack from Google Play services. Unlike
    // NiceHttp/OkHttp on Android 15, current Cronet providers can negotiate
    // the same modern TLS features used by Chrome (including ECH when the
    // provider and the target support it).
    implementation("com.google.android.gms:play-services-cronet:18.0.1")
}

cloudstream {
    description = "AfterDark - resolver et lecture via Cronet ECH"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    isCrossPlatform = false
}
