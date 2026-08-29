// Kotlin 2.4 metadata requires R8 9.1.29+.
// Keep AGP 8.7.3 for CloudStream compatibility and override only D8/R8.
pluginManagement {
    buildscript {
        repositories {
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
            }
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
}

rootProject.name = "CloudstreamPlugins"

val disabled = emptyList<String>()

File(rootDir, ".").eachDir { dir ->
    if (dir.name !in disabled && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach(block)
}
