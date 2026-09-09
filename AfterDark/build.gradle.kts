import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.Sync

version = 61

val publishedIconBase =
    "https://raw.githubusercontent.com/" +
        (System.getenv("GITHUB_REPOSITORY") ?: "yorik100/Cloudstream") +
        "/refs/heads/main/icons"

val embeddedWebkit by configurations.creating
val androidClassesJar = Attribute.of("artifactType", String::class.java)
val embeddedWebkitClasses = embeddedWebkit.incoming.artifactView {
    attributes.attribute(androidClassesJar, "android-classes-jar")
}.files
val unpackedWebkitClasses = layout.buildDirectory.dir("embedded-webkit-classes")

dependencies {
    // CloudStream already provides coroutines at runtime.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly("androidx.webkit:webkit:1.14.0")

    // CloudStream's compileDex task normally ignores dependency classes. Embed
    // WebKit explicitly so WebViewFeature/WebViewCompat exist inside the .cs3.
    embeddedWebkit("androidx.webkit:webkit:1.14.0") {
        isTransitive = false
    }
}

val unpackEmbeddedWebkit by tasks.registering(Sync::class) {
    from({ embeddedWebkitClasses.files.map { zipTree(it) } })
    include("**/*.class")
    into(unpackedWebkitClasses)
}

tasks.named<CompileDexTask>("compileDex") {
    dependsOn(unpackEmbeddedWebkit)
    // CompileDexTask closes JAR inputs before D8 consumes their entries.
    // Supplying extracted class files avoids the resulting "zip file closed".
    input.from(unpackedWebkitClasses)
}

cloudstream {
    description = "AfterDark - vérification + lancement de vidéo automatique"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    iconUrl = "$publishedIconBase/KeepLink2.png"
    isCrossPlatform = false
}
