import com.techshroom.inciseblue.commonLib
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
    id("com.techshroom.incise-blue") version "0.5.7"
    kotlin("jvm") version "1.3.61"
}

inciseBlue {
    ide()
    license()
    util {
        javaVersion = JavaVersion.VERSION_13
    }
}

dependencies {
    "implementation"(kotlin("stdlib-jdk8"))
    "implementation"("org.slf4j:slf4j-api:1.7.30")
    commonLib("ch.qos.logback", "logback", "1.2.3") {
        "implementation"(lib("classic"))
        "implementation"(lib("core"))
    }
    commonLib("io.ktor", "ktor", "1.3.0") {
        "implementation"(lib("server-core"))
        "implementation"(lib("server-netty"))
        "implementation"(lib("client-okhttp"))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.freeCompilerArgs = listOf("-Xuse-experimental=kotlin.Experimental")
}

application.mainClassName = "net.octyl.mavencache.MainKt"
