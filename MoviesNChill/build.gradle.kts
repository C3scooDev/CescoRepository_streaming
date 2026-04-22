import org.jetbrains.kotlin.konan.properties.Properties

version = 9

cloudstream {
    description = "Film e serie (TMDB) con streaming via vsrc.su — ispirato a Movies & Chill (moviesnchill.net)"
    authors = listOf("CescoDev")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )
    requiresResources = false
    language = "en"

    iconUrl = "https://moviesnchill.net/icon2.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        val properties = Properties()
        val secretsFile = rootProject.file("secrets.properties")
        if (secretsFile.exists()) {
            secretsFile.inputStream().use { properties.load(it) }
        }
        buildConfigField(
            "String",
            "TMDB_API",
            "\"${properties.getProperty("TMDB_API", "")}\"",
        )
    }
}
