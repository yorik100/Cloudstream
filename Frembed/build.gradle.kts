version = 24

val publishedIconBase =
    "https://raw.githubusercontent.com/" +
        (System.getenv("GITHUB_REPOSITORY") ?: "yorik100/Cloudstream") +
        "/refs/heads/main/icons"

dependencies {
    // CloudStream already provides coroutines at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

cloudstream {
    description = "Frembed - lecteur natif"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    iconUrl = "$publishedIconBase/KeepLink.png"
    isCrossPlatform = false
}
