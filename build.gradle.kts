import java.util.zip.ZipFile

plugins {
    java
}

group = "com.plexon"
version = "2.3.0"

val pluginVersion = version.toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.121-stable")
    compileOnly("com.zpkdxgames:PlexonCore:2.0.0")
    compileOnly("me.clip:placeholderapi:2.12.1")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
        exclude(group = "org.bukkit", module = "craftbukkit")
    }

    implementation("com.zaxxer:HikariCP:6.3.3") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.papermc.paper:paper-api:26.2.build.121-stable")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:deprecation", "-Xlint:-processing"))
}

tasks.processResources {
    val resourceProperties = mapOf("version" to pluginVersion)
    inputs.properties(resourceProperties)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(resourceProperties)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        charSet = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

val shadowJar = tasks.register<Jar>("shadowJar") {
    group = "build"
    description = "Builds the self-contained installable Paper plugin JAR."
    dependsOn(tasks.classes)
    archiveBaseName.set("PlexonShops")
    archiveVersion.set(pluginVersion)
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.layout.projectDirectory.file("licenses/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "HikariCP-Apache-2.0.txt" }
    }
    from(rootProject.layout.projectDirectory.file("licenses/Apache-2.0.txt")) {
        into("META-INF/licenses")
        rename { "SQLite-JDBC-Apache-2.0.txt" }
    }

    manifest {
        attributes(
            "Implementation-Title" to "PlexonShops",
            "Implementation-Version" to pluginVersion,
            "Implementation-Vendor" to "ZpkDxGames"
        )
    }
}

val verifyDistribution = tasks.register("verifyDistribution") {
    group = "verification"
    description = "Checks the installable JAR contract and verifies PlexonCore is not shaded."
    dependsOn(shadowJar)
    inputs.file(shadowJar.flatMap { it.archiveFile })
        .withPropertyName("distributionJar")
        .withPathSensitivity(PathSensitivity.NONE)
    doLast {
        val archive = inputs.files.singleFile
        require(archive.isFile && archive.length() > 1_000_000L) {
            "Installable JAR is missing or unexpectedly small: $archive"
        }
        ZipFile(archive).use { zip ->
            listOf(
                "plugin.yml",
                "com/plexon/shops/PlexonShops.class",
                "com/plexon/shops/api/PlexonShopsAPI.class",
                "com/plexon/shops/api/ShopView.class",
                "com/plexon/shops/event/PlexonShopCreatedEvent.class",
                "com/plexon/shops/event/PlexonShopVisitedEvent.class",
                "com/plexon/shops/event/PlexonShopRatedEvent.class",
                "com/plexon/shops/integration/core/runtime/CoreRuntimeShopsBridge.class",
                "com/zaxxer/hikari/HikariDataSource.class",
                "org/sqlite/JDBC.class",
                "META-INF/THIRD_PARTY_NOTICES.md"
            ).forEach { entry -> require(zip.getEntry(entry) != null) { "Missing JAR entry: $entry" } }
            require(zip.entries().asSequence().none { it.name.startsWith("com/zpkdxgames/plexoncore/") }) {
                "PlexonCore runtime classes must not be shaded into PlexonShops"
            }
        }
    }
}

tasks.jar {
    archiveClassifier.set("plain")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.assemble {
    dependsOn(shadowJar)
}

tasks.check {
    dependsOn(verifyDistribution)
}
