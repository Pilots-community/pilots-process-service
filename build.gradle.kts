plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("org.openapi.generator") version "7.4.0"
}

group = "io.pilots"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
}

val flowableVersion = "7.0.1"

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Flowable BPMN engine
    implementation("org.flowable:flowable-spring-boot-starter-process:$flowableVersion")
    // Flowable REST API (process management endpoints)
    implementation("org.flowable:flowable-spring-boot-starter-rest:$flowableVersion")

    // SpringDoc – Swagger UI + provides swagger-annotations used by generated code
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0")

    // Database (H2 for development; swap for PostgreSQL in production)
    runtimeOnly("com.h2database:h2")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// ---------------------------------------------------------------------------
// OpenAPI code generation
// ---------------------------------------------------------------------------

val openApiOutputDir = layout.buildDirectory.dir("generated/openapi")

openApiGenerate {
    generatorName = "spring"
    inputSpec = "$rootDir/src/main/resources/api/service-instances.yaml"
    outputDir = openApiOutputDir.get().asFile.absolutePath
    apiPackage = "io.pilots.processservice.api"
    modelPackage = "io.pilots.processservice.api.model"

    // Do not generate test stubs or documentation
    generateApiTests = false
    generateModelTests = false
    generateModelDocumentation = false
    generateApiDocumentation = false

    configOptions = mapOf(
        // Emit interfaces only — implementations live in our own code
        "interfaceOnly"        to "true",
        // Spring Boot 3 / Jakarta EE namespaces
        "useSpringBoot3"       to "true",
        "useJakartaEe"         to "true",
        // Disable jackson-databind-nullable wrapper types
        "openApiNullable"      to "false",
        // Group API interface by the 'tags' field in the spec
        "useTags"              to "true",
        // java.time types
        "dateLibrary"          to "java8",
        // Do not emit default 501 stub bodies on the interfaces
        "skipDefaultInterface" to "true",
    )
}

// Wire the generated sources into the main source set
sourceSets {
    main {
        java {
            srcDir(openApiOutputDir.map { it.dir("src/main/java") })
        }
    }
}

// openApiGenerate must run before compileJava
tasks.named("compileJava") {
    dependsOn(tasks.named("openApiGenerate"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
