package com.lagradost.cloudstream3

object BuildConfig {
    const val DEBUG = false
    const val APPLICATION_ID: String = "com.lagradost.cloudstream3"
    const val FLAVOR: String = "release"
    const val VERSION_NAME: String = "4.0.0"
    const val VERSION_CODE: Int = 4000000
    const val SIMKL_CLIENT_ID: String = ""
    const val SIMKL_CLIENT_SECRET: String = ""
    val ANILIST_KEY: String = System.getenv("ANILIST_KEY") ?: System.getProperty("anilist.key", "14065")
    val MAL_KEY: String = System.getenv("MAL_KEY") ?: System.getProperty("mal.key", "6114d00ca681b7701d1e150047b134cb")
}
