pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "JPNR Extension"

// Include your custom provider module
include("WatchAnimeWorldProvider")

// Optional: include the other providers if you want them built too
// include("provider name")
// include("provider name")
// include("provider name")
// include("provider name")
// include("provider name")
// include("provider name")
