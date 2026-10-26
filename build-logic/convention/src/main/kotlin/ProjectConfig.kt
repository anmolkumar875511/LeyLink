import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Single source of truth for SDK levels and JVM level across all modules. */
internal object ProjectConfig {
    const val COMPILE_SDK = 34
    const val TARGET_SDK = 34
    const val MIN_SDK = 26
    val JAVA_VERSION: JavaVersion = JavaVersion.VERSION_17
}

/** Same Kotlin settings for every module (JVM target, optional warnings-as-errors). */
internal fun Project.configureKotlinJvmTarget() {
    val warningsAsErrors = providers.gradleProperty("leylink.warningsAsErrors")
        .map { it.toBoolean() }
        .orElse(false)

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(warningsAsErrors)
        }
    }
}
