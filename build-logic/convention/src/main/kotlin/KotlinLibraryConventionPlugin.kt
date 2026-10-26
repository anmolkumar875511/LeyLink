import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure

/**
 * Pure Kotlin/JVM module. There is deliberately NO Android plugin, so any `import android.*`
 * fails to compile. This is what keeps :core:* portable.
 */
class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = ProjectConfig.JAVA_VERSION
                targetCompatibility = ProjectConfig.JAVA_VERSION
            }
            configureKotlinJvmTarget()
        }
    }
}
