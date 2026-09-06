version = 52

dependencies {
    // CloudStream already provides coroutines at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("androidx.webkit:webkit:1.14.0")
}

cloudstream {
    description = "AfterDark - détection Turnstile dans toutes les frames WebView"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    isCrossPlatform = false
}
