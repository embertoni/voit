plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voit.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // The sandbox HOME (/root) is read-only, so Robolectric must not write its
                // download lock / cached android-all runtime there. Redirect user.home and
                // its dependency cache into the writable sandbox tree.
                test.systemProperty("user.home", "/projects/sandbox/fakehome")
            }
        }
    }
}

// Export the Room schema JSON into a committed schemas/ directory so migrations have a
// baseline to diff against. The exported v1/v2 schemas are validated by a JVM test (the
// sandbox has no emulator, so MigrationTestHelper's instrumented path cannot run here).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric lets the Room-backed repositories run on the JVM (no device needed) so
    // CRUD/search/atomic-update/duplicate-prevention are exercised against real SQLite.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.ktx)
}
