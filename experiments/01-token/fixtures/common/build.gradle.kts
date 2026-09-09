plugins { java }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
// Dependency-free Java checks make the offline fixture reproducible.
// Every *Checks.java must expose public static void main(String[] args).
val checks = fileTree("src/test/java") { include("**/*Checks.java") }.files.sorted().map { source ->
    tasks.register<JavaExec>("run" + source.nameWithoutExtension) {
        dependsOn(tasks.testClasses)
        classpath = sourceSets.test.get().runtimeClasspath
        mainClass = "benchmark.order." + source.nameWithoutExtension
        enableAssertions = true
    }
}
tasks.test {
    dependsOn(checks)
    // The JavaExec checks above are the tests; no JUnit engine is installed.
    failOnNoDiscoveredTests = false
}
