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
