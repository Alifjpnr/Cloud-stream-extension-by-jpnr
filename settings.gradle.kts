pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "CloudstreamPlugins"

// List folder names here if you want to skip building them (e.g., listOf("TwitchProvider"))
val disabled = listOf<String>()

// Automatically scan and include every subfolder that has a build.gradle.kts file
File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
