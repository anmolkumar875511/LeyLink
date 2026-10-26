plugins {
    alias(libs.plugins.leylink.kotlin.library)
}

dependencies {
    // Exposed in the public API (Flow/StateFlow appear in the ports)
    api(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
