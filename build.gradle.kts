import com.lagradost.cloudstream3.gradle.CloudstreamExtension

// use an integer for version numbers
version = 1

configure<CloudstreamExtension> {
    language = "hi"
    description = "Hindi, Tamil and Telugu dubbed anime and cartoons from WatchAnimeWorld"
    authors = listOf("Alif")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1

    tvTypes = listOf("Anime", "AnimeMovie", "Cartoon", "Movie")

    iconUrl = "https://watchanimeworld.one/wp-content/uploads/cropped-AW_Smiley-192x192.png"
}
