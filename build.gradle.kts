import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
    id("com.gradleup.shadow") version "9.4.1"
}

group = "cn.superiormc.economyriskbridge"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.momirealms.net/releases/")
    maven("https://api.modrinth.com/maven")
}

configurations.configureEach {
    if (isCanBeResolved) {
        // Paper 26.2 and ExcellentEconomy 2.8 publish Java 25 metadata.
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.55-alpha")
    compileOnly("su.nightexpress.excellenteconomy:ExcellentEconomy:2.8.0")
    compileOnly("maven.modrinth:nightcore:2.15.1")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0") {
        exclude(group = "org.slf4j")
    }
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("net.momirealms:sparrow-reflection:0.27")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testCompileOnly("io.papermc.paper:paper-api:26.2.build.55-alpha")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("io.papermc.paper:paper-api:26.2.build.55-alpha")
}

tasks.shadowJar {
    archiveBaseName.set("EconomyRiskBridge")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("net.momirealms.sparrow.reflection", "cn.superiormc.economyriskbridge.lib.sparrow")
    relocate("org.objectweb.asm", "cn.superiormc.economyriskbridge.lib.asm")
    from("LICENSE") {
        into("META-INF")
        rename { "LICENSE-EconomyRiskBridge.txt" }
    }
    from("THIRD_PARTY_NOTICES.md") {
        into("META-INF")
    }
    from("third-party") {
        into("META-INF/licenses")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(mapOf("revision" to project.version.toString()))
    }
}
