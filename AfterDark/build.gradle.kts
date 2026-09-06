version = 50

dependencies {
    // CloudStream already provides coroutines at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

cloudstream {
    description = "AfterDark - résolution HTTP avant lecture sans WebView de registre"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    isCrossPlatform = false
}
