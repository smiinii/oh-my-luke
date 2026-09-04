import org.gradle.api.tasks.WriteProperties

plugins {
    application
}

fun toNativePackageVersion(productVersion: String): String {
    val components = productVersion.substringBefore('-').split('.').map(String::toInt)
    require(components.size == 3) { "OML version must use MAJOR.MINOR.PATCH: $productVersion" }
    return "${components[0] + 1}.${components[1]}.${components[2]}"
}

group = "io.ohmyluke"
version = "0.1.0-rc.1"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.6")
    implementation("net.java.dev.jna:jna:5.19.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.ohmyluke.cli.OmlukeApplication"
    applicationName = "omluke"
}

val packageOs = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    else -> "unsupported"
}
val packageArch = when (System.getProperty("os.arch").lowercase()) {
    "aarch64", "arm64" -> "aarch64"
    "amd64", "x86_64" -> "x64"
    else -> System.getProperty("os.arch").lowercase().replace(Regex("[^a-z0-9]+"), "-")
}
val packageClassifier = "$packageOs-$packageArch"
val packageRoot = layout.buildDirectory.dir("package")
val packageImageName = if (packageOs == "macos") "omluke.app" else "omluke"
val packageImage = packageRoot.map { it.dir("app-image/$packageImageName") }
val packageReleaseRootName = "omluke-${project.version}-$packageClassifier"
val packageStagingRoot = packageRoot.map { it.dir("staging") }
val packageReleaseRoot = packageStagingRoot.map { it.dir(packageReleaseRootName) }
val packageArchiveFile = packageRoot.map { it.file("omluke-${project.version}-$packageClassifier.tar.gz") }
val packageChecksumFile = packageRoot.map { it.file("omluke-${project.version}-$packageClassifier.tar.gz.sha256") }
val packageEvidence = packageRoot.map { it.file("evidence/omluke-${project.version}-$packageClassifier.json") }
val packagePlatformFile = packageRoot.map { it.file("metadata/PLATFORM") }
val releaseTag = providers.gradleProperty("releaseTag").orElse("v${project.version}")
// macOS jpackage rejects versions that start with zero, so the native major is product major + 1.
val nativePackageVersion = toNativePackageVersion(project.version.toString())
val mainJarName = tasks.named<Jar>("jar").flatMap { it.archiveFileName }
val javaLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}
val archiveNormalizationArgs = when (packageOs) {
    "macos" -> listOf(
        "--format=ustar", "--uid=0", "--gid=0", "--uname=root", "--gname=root",
        "--options=gzip:!timestamp"
    )
    "linux" -> listOf(
        "--format=ustar", "--sort=name", "--mtime=@315532800",
        "--owner=0", "--group=0", "--numeric-owner"
    )
    else -> emptyList()
}

val cleanPackageAppImage = tasks.register<Delete>("cleanPackageAppImage") {
    delete(packageRoot.map { it.dir("app-image") })
    outputs.upToDateWhen { false }
}

val packageAppImage = tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "Builds a platform-specific OML image with its own Java runtime."
    dependsOn(tasks.installDist, cleanPackageAppImage)
    // Declare the parent only: Gradle may create an output directory before Exec,
    // while jpackage requires the final <name>.app/<name> path not to exist.
    outputs.dir(packageRoot.map { it.dir("app-image") })
    executable(javaLauncher.get().metadata.installationPath.file("bin/jpackage").asFile)
    args(
        "--type", "app-image",
        "--dest", packageRoot.get().dir("app-image").asFile.absolutePath,
        "--name", "omluke",
        "--input", layout.buildDirectory.dir("install/omluke/lib").get().asFile.absolutePath,
        "--main-jar", mainJarName.get(),
        "--main-class", application.mainClass.get(),
        "--app-version", nativePackageVersion,
        "--add-modules", "java.base,java.desktop,java.sql",
        "--jlink-options", "--strip-debug --no-header-files --no-man-pages --compress=zip-6"
    )
}

val normalizePackageAppImage = tasks.register<Exec>("normalizePackageAppImage") {
    group = "distribution"
    description = "Normalizes app-image timestamps before creating a reproducible archive."
    dependsOn(packageAppImage)
    executable("/usr/bin/find")
    environment("TZ", "UTC")
    args(
        packageImage.get().asFile.absolutePath,
        "-exec", "/usr/bin/touch", "-h", "-t", "198001010000.00", "{}", "+"
    )
}

val cleanPackageStaging = tasks.register<Delete>("cleanPackageStaging") {
    delete(packageStagingRoot)
    outputs.upToDateWhen { false }
}

val writePackagePlatform = tasks.register<WriteProperties>("writePackagePlatform") {
    group = "distribution"
    description = "Writes the target OS and CPU architecture into the release bundle."
    destinationFile = packagePlatformFile.get().asFile
    encoding = "UTF-8"
    lineSeparator = "\n"
    property("os", packageOs)
    property("arch", packageArch)
}

val preparePackageMetadata = tasks.register<Sync>("preparePackageMetadata") {
    group = "distribution"
    description = "Stages the installer, uninstaller, and product version for the release archive."
    dependsOn(normalizePackageAppImage, cleanPackageStaging, writePackagePlatform)
    into(packageReleaseRoot)
    from(layout.projectDirectory.dir("packaging"))
    from(layout.projectDirectory.dir("examples")) {
        into("examples")
    }
    from(layout.projectDirectory.file("src/main/resources/io/ohmyluke/version.properties")) {
        rename { "VERSION" }
    }
    from(packagePlatformFile)
}

val copyPackageImage = tasks.register<Exec>("copyPackageImage") {
    group = "distribution"
    description = "Copies the normalized native app image without dereferencing runtime symbolic links."
    dependsOn(preparePackageMetadata)
    inputs.dir(packageImage)
    outputs.dir(packageReleaseRoot.map { it.dir(packageImageName) })
    executable("/bin/cp")
    args("-R", packageImage.get().asFile.absolutePath, packageReleaseRoot.get().asFile.absolutePath)
}

val makePackageScriptsExecutable = tasks.register<Exec>("makePackageScriptsExecutable") {
    group = "distribution"
    description = "Marks the staged lifecycle scripts as executable."
    dependsOn(copyPackageImage)
    executable("/bin/chmod")
    args(
        "755",
        packageReleaseRoot.get().file("install.sh").asFile.absolutePath,
        packageReleaseRoot.get().file("uninstall.sh").asFile.absolutePath
    )
}

val normalizePackageBundle = tasks.register<Exec>("normalizePackageBundle") {
    group = "distribution"
    description = "Normalizes every staged release file before creating a reproducible archive."
    dependsOn(makePackageScriptsExecutable)
    executable("/usr/bin/find")
    environment("TZ", "UTC")
    args(
        packageReleaseRoot.get().asFile.absolutePath,
        "-exec", "/usr/bin/touch", "-h", "-t", "198001010000.00", "{}", "+"
    )
}

val packageArchive = tasks.register<Exec>("packageArchive") {
    group = "distribution"
    description = "Archives the self-contained OML release candidate while preserving symbolic links."
    dependsOn(normalizePackageBundle)
    inputs.dir(packageReleaseRoot)
    outputs.file(packageArchiveFile)
    executable("/usr/bin/tar")
    environment("COPYFILE_DISABLE", "1")
    args(*archiveNormalizationArgs.toTypedArray())
    args(
        "-czf", packageArchiveFile.get().asFile.absolutePath,
        "-C", packageStagingRoot.get().asFile.absolutePath,
        packageReleaseRootName
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.test {
    useJUnitPlatform()
    exclude("io/ohmyluke/distribution/PackagedApplicationTest.class")
    inputs.files(
        layout.projectDirectory.file("packaging/install.sh"),
        layout.projectDirectory.file("packaging/uninstall.sh"),
        layout.projectDirectory.file("scripts/release/assemble-bundle.sh"),
        layout.projectDirectory.file(".github/workflows/release-candidate-dry-run.yml")
    )
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val verifyPackagedApp = tasks.register<Test>("verifyPackagedApp") {
    group = "verification"
    description = "Extracts and runs the packaged OML without an external Java runtime."
    dependsOn(packageArchive)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("io/ohmyluke/distribution/PackagedApplicationTest.class")
    outputs.upToDateWhen { false }
    inputs.file(packageArchiveFile)
    inputs.dir(packageImage)
    outputs.file(packageChecksumFile)
    outputs.file(packageEvidence)
    inputs.property("releaseTag", releaseTag)
    systemProperty("omluke.package.archive", packageArchiveFile.get().asFile.absolutePath)
    systemProperty("omluke.package.checksum", packageChecksumFile.get().asFile.absolutePath)
    systemProperty("omluke.package.sourceImage", packageImage.get().asFile.absolutePath)
    systemProperty("omluke.package.imageName", packageImageName)
    systemProperty("omluke.package.releaseRootName", packageReleaseRootName)
    systemProperty("omluke.package.os", packageOs)
    systemProperty("omluke.package.arch", packageArch)
    systemProperty("omluke.package.productVersion", project.version.toString())
    systemProperty("omluke.package.releaseTag", releaseTag.get())
    systemProperty("omluke.package.evidence", packageEvidence.get().asFile.absolutePath)
    testLogging {
        events("failed", "skipped", "standardOut")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
