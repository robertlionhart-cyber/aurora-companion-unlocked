import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Optional: put all build output in another folder (set aurora.buildRoot in local.properties).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val buildRoot: String? = localProps.getProperty("aurora.buildRoot")
if (buildRoot != null) {
    allprojects {
        layout.buildDirectory.set(file("$buildRoot/${project.name}"))
    }
}
