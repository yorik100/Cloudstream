version = 20

dependencies {
    // CloudStream already provides coroutines at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

cloudstream {
    description = "Frembed - résolution config main, KeepLink puis fallback crt.sh"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    isCrossPlatform = false
}
