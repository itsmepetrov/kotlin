import tasks.WriteCopyrightToFile

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

val runtimeOnly by configurations
val compileOnly by configurations
runtimeOnly.extendsFrom(compileOnly)

dependencies {
    compile(project(":compiler:psi"))
    compile(project(":compiler:frontend.common"))
    compile(project(":core:descriptors"))
    compile(project(":compiler:fir:cones"))
    compile(project(":compiler:resolution"))

    compileOnly(intellijCoreDep()) { includeJars("intellij-core", "guava", rootProject = rootProject) }
    compileOnly(intellijDep()) {
        includeJars("trove4j", "picocontainer", rootProject = rootProject)
    }

    Platform[192].orHigher {
        runtimeOnly(intellijCoreDep()) { includeJars("jdom") }
    }
    implementation(kotlin("reflect"))
}

val writeCopyright by task<WriteCopyrightToFile> {
    outputFile = file("$buildDir/copyright/notice.txt")
    commented = true
}

val processResources by tasks
processResources.dependsOn(writeCopyright)

sourceSets {
    "main" {
        projectDefault()
        resources.srcDir("$buildDir/copyright")
    }
    "test" {}
}
