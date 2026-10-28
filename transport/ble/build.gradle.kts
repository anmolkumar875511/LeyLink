plugins {
    alias(libs.plugins.leylink.android.library)
}

android {
    namespace = "com.leylink.transport.ble"
}

dependencies {
    implementation(projects.core.routing)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
}
