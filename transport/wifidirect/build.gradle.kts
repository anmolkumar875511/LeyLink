plugins {
    alias(libs.plugins.leylink.android.library)
}

android {
    namespace = "com.leylink.transport.wifidirect"
}

dependencies {
    implementation(projects.core.routing)
}
