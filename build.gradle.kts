import java.time.Instant

plugins {
    java
    application
    // Publishes the plugin api of the site, so plugin authors can depend on it
    // straight from the repository (JitPack) instead of building it by hand.
    `maven-publish`
    id("com.gradleup.shadow") version "9.0.0"
}

// JitPack serves whatever it finds in the local maven repository after the
// build, under com.github.<owner>. Keeping that as the group here means the
// coordinates are the same locally and on JitPack.
group = "com.github.openbancho"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("com.osuserverlist.koneko.App")
}

val lombokVersion = "1.18.46"
val javalinVersion = "7.2.2"
val dotenvVersion = "5.2.2"
val jacksonVersion = "2.21.2"
val logbackVersion = "1.5.38"
val pf4jVersion = "3.13.0"
val junitVersion = "5.14.2"

dependencies {
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // JavalinVue ships with the core artifact (io.javalin.vue)
    implementation("io.javalin:javalin:$javalinVersion")

    implementation("io.github.cdimascio:java-dotenv:$dotenvVersion")

    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVersion")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // The .jar plugin host. Plugins are loaded from the plugins directory at
    // boot, so nothing about them is known at build time.
    implementation("org.pf4j:pf4j:$pf4jVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()

    // The tests are deliberately offline: a suite that needs DNS fails for reasons that
    // have nothing to do with the code under test, and a security test nobody trusts is a
    // security test nobody runs.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * The jar a plugin author compiles against: the plugin API, the two config
 * classes it exposes and the API exception. Everything else stays internal.
 *
 * <p>A plugin must depend on this with `compileOnly`, never `implementation`:
 * the PF4J class loader is plugin-first, so a bundled copy of these classes
 * would be loaded instead of the host's and every extension would be invisible.
 */
val pluginApiJar by tasks.registering(Jar::class) {
    archiveBaseName.set("koneko-plugin-api")
    archiveVersion.set("")
    from(sourceSets.main.get().output) {
        include("com/osuserverlist/koneko/plugin/api/**")
        include("com/osuserverlist/koneko/config/Env*.class")
        include("com/osuserverlist/koneko/config/SiteConfig*.class")
        include("com/osuserverlist/koneko/api/ApiException.class")
    }
}

/**
 * `gradlew publishToMavenLocal` (which is what JitPack runs) publishes the api
 * jar as `com.github.openbancho:koneko-web:<version>`, so a plugin can do:
 *
 *     repositories { mavenCentral(); maven("https://jitpack.io") }
 *     dependencies { compileOnly("com.github.OpenBancho:koneko-web:v0.1.0") }
 *
 * Only the api is published on purpose: the artifact carries no dependencies,
 * so nothing of the host can leak into a plugin jar by accident.
 */
publishing {
    publications {
        create<MavenPublication>("pluginApi") {
            artifactId = "koneko-web"
            artifact(pluginApiJar)

            pom {
                name.set("koneko-web plugin api")
                description.set("Plugin API of koneko-web, the web frontend for bancho.jar")
            }
        }
    }
}

val generateBuildProperties by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/resources/main/build.properties")

    property("name", project.name)
    property("version", project.version)
    property("buildTime", Instant.now().toString())
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources/main"))
}

tasks.processResources {
    dependsOn(generateBuildProperties)
}

tasks.jar {
    archiveFileName.set("koneko-web.jar")

    manifest {
        attributes["Main-Class"] = "com.osuserverlist.koneko.App"
    }
}

tasks.shadowJar {
    // A jar that ships without its tests having run is a jar nobody knows anything about.
    dependsOn(tasks.test)

    archiveFileName.set("koneko-web-shaded.jar")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.osuserverlist.koneko.App"
    }
}
