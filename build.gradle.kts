import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

group = "com.stavfx"
version = "0.3.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.kctfork.ksp)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()

    coordinates(group.toString(), "nav3-hilt-vm", version.toString())

    pom {
        name.set("nav3-hilt-vm")
        description.set(
            "KSP processor that takes the boilerplate out of Hilt + Navigation 3 + " +
                "ViewModels with assisted NavKey injection."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/stavfx/nav3-hilt-vm")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("stavfx")
                name.set("Stav Raviv")
                url.set("https://github.com/stavfx/")
            }
        }

        scm {
            url.set("https://github.com/stavfx/nav3-hilt-vm/")
            connection.set("scm:git:git://github.com/stavfx/nav3-hilt-vm.git")
            developerConnection.set("scm:git:ssh://git@github.com/stavfx/nav3-hilt-vm.git")
        }
    }
}
