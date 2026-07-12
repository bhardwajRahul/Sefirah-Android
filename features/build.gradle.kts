plugins {
    alias(libs.plugins.sefirah.android.library)
}

android {
    namespace = "sefirah.features"

    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,DEPENDENCIES}"
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    api(projects.core.common)
    api(projects.core.presentation)
    api(projects.domain)
    implementation(projects.core.database)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.android.smsmms)
    implementation(libs.commons.io)
    implementation(libs.commons.lang3)
    implementation(libs.commons.collections4)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.session)
    implementation(libs.bundles.ktor)
    implementation(libs.androidx.documentfile)
    implementation(libs.apache.sshd.core)
    implementation(libs.apache.sshd.sftp)
    implementation(libs.apache.sshd.scp)
    implementation(libs.apache.sshd.mina)
    implementation(libs.apache.mina.core)
}
