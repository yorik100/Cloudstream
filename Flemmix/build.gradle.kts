import com.lagradost.cloudstream3.gradle.tasks.GenerateManifestTask

version = 13

val publishedIconBase =
    "https://raw.githubusercontent.com/" +
        (System.getenv("GITHUB_REPOSITORY") ?: "yorik100/Cloudstream") +
        "/refs/heads/main/icons"

dependencies {
    // CloudStream already provides coroutines at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

cloudstream {
    description = "Flemmix/Wiflix - catalogue réel et résolution neufneuf.space puis KeepLink3"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    isCrossPlatform = false
    iconUrl = "$publishedIconBase/KeepLink3.png"
}

// Le nom technique du projet reste "Flemmix" afin de produire un fichier
// Flemmix.cs3 valide. Le nom embarqué et affiché par CloudStream garde le slash.
tasks.withType<GenerateManifestTask>().configureEach {
    pluginName.set("Flemmix/Wiflix")
}
