import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// ali
buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Official Cloudstream gradle plugin dependency syntax
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/Alifjpnr/Cloud-stream-extension-by-jpnr")
    }

    android {
        namespace = "recloudstream"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val implementation by configurations

        implementation("com.github.recloudstream.cloudstream:library:-SNAPSHOT")

        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
        implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
   .replace("/", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return if (name.isNotBlank()) name else "Episode ${epNum ?: ""}".trim()
    }

    private suspend fun fetchSeasonEpisodes(postId: String, season: Int): List<String> {
        if (postId.isBlank()) return emptyList()
        return try {
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$season&post=$postId"
            val response = app.get(ajaxUrl, headers = mapOf(
                "Referer" to mainUrl,
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val doc = Jsoup.parse(response.text)
            doc.select("a[href*=/episode/]").map { it.attr("href") }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadFirebaseUrl()
        return try {
            val response = app.get(data)
            val doc = Jsoup.parse(response.text)

            val iframes = doc.select("iframe, .mobius iframe, .play-box-iframe iframe").mapNotNull { el ->
                val src = el.attr("data-src").ifBlank { el.attr("src") }
                if (src.isNotBlank()) src else null
            }.distinct()

            var found = false
            for (iframe in iframes) {
                if (iframe.contains("zephyrix") || iframe.contains("zephyrflick")) {
                    if (resolveZephyrix(iframe, subtitleCallback, callback)) found = true
                } else {
                    if (loadExtractor(iframe, data, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun resolveZephyrix(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val videoId = Regex("/video/([a-f0-9]+)").find(iframeUrl)?.groupValues?.get(1) ?: return false
            val playerBase = Regex("(https?://[^/]+)").find(iframeUrl)?.groupValues?.get(1) ?: return false

            val response = app.post(
                "$playerBase/player/index.php?data=$videoId&do=getVideo",
                data = mapOf("hash" to videoId, "r" to "$mainUrl/"),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to playerBase,
                    "Referer" to "$playerBase/video/$videoId",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                timeout = 30_000L
            )
            val video = parseJson<ZephyrixVideo>(response.text)
            val streamUrl = video.videoSource ?: video.securedLink ?: return false
            val absoluteUrl = if (streamUrl.startsWith("http")) streamUrl else playerBase + streamUrl

            val link = newExtractorLink(
                name,
                "Anime World India - JPNR",
                absoluteUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = "$playerBase/"
                this.headers = mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                )
            }
            callback.invoke(link)
            true
        } catch (_: Exception) {
            false
        }
    }
}
