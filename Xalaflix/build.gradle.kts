version = 26

val publishedIconBase =
    "https://raw.githubusercontent.com/" +
        (System.getenv("GITHUB_REPOSITORY") ?: "yorik100/Cloudstream") +
        "/refs/heads/main/icons"

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    compileOnly("org.jspecify:jspecify:1.0.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

cloudstream {
    description = "Xalaflix - lecteur natif"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    isCrossPlatform = false
    iconUrl = "$publishedIconBase/KeepLink4.png"
}
