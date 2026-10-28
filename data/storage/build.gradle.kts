plugins {
    alias(libs.plugins.leylink.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.leylink.data.storage"
}

ksp {
    // Commit the generated schemas so migrations can be reviewed and tested.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(projects.core.routing)
    // RoomDatabase appears in this module's public API (LeyLinkDatabase), so consumers need it.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
