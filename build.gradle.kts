import com.techshroom.inciseblue.commonLib
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    id("com.techshroom.incise-blue") version "0.5.7"
    id("net.researchgate.release") version "2.8.1"
    kotlin("jvm") version "1.3.61"
}

inciseBlue {
    ide()
    license()
    util {
        javaVersion = JavaVersion.VERSION_13
    }
    maven {
        projectDescription = "A simple Maven cache server"
        coords("octylFractal", "simple-maven-cache")
        licenseName = "GPL 3.0 or later"
    }
}

dependencies {
    "implementation"(kotlin("stdlib-jdk8"))
    "implementation"("org.slf4j:slf4j-api:1.7.30")
    commonLib("ch.qos.logback", "logback", "1.2.3") {
        "implementation"(lib("classic"))
        "implementation"(lib("core"))
    }
    commonLib("io.ktor", "ktor", "1.3.1") {
        "implementation"(lib("server-core"))
        "implementation"(lib("server-netty"))
        "implementation"(lib("client-okhttp"))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs = listOf("-Xuse-experimental=kotlin.Experimental")
}

application.mainClassName = "net.octyl.mavencache.MainKt"
