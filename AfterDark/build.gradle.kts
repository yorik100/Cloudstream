import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.gradle.api.attributes.Attribute

version = 56

val embeddedWebkit by configurations.creating
val androidClassesJar = Attribute.of("artifactType", String::class.java)
val embeddedWebkitClasses = embeddedWebkit.incoming.artifactView {
    attributes.attribute(androidClassesJar, "android-classes-jar")
}.files

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

tasks.named<CompileDexTask>("compileDex") {
    input.from(embeddedWebkitClasses)
}

cloudstream {
    description = "AfterDark - détection directe de la checkbox Cloudflare"
    authors = listOf("yorik100")
    status = 3
    tvTypes = listOf("Movie", "TvSeries")
    language = "fr"
    isCrossPlatform = false
}
