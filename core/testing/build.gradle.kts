plugins {
    alias(libs.plugins.leylink.kotlin.library)
}

dependencies {
    api(projects.core.routing)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
