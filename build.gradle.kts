/*
 * Copyright © 2024 Mark Raynsford <code@io7m.com> https://www.io7m.com
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

val gradleVersionRequired = "8.10"
val gradleVersionReceived = gradle.gradleVersion

if (gradleVersionRequired != gradleVersionReceived) {
  throw GradleException(
    "Gradle version $gradleVersionRequired is required to run this build. You are using Gradle $gradleVersionReceived",
  )
}

plugins {
  signing

  id("org.jetbrains.kotlin.jvm")
    .version("2.0.20")
    .apply(false)

  id("org.jetbrains.kotlin.android")
    .version("2.0.20")
    .apply(false)

  id("com.android.library")
    .version("8.5.0")
    .apply(false)

  id("com.android.application")
    .version("8.5.0")
    .apply(false)

  id("maven-publish")
}

/*
 * The various paths used during the build.
 */

val io7mRootBuildDirectory =
  "$rootDir/build"
val io7mDeployDirectory =
  "$io7mRootBuildDirectory/maven"

/**
 * Convenience functions to read strongly-typed values from property files.
 */

fun property(
  project: Project,
  name: String,
): String {
  return project.extra[name] as String
}

fun propertyOptional(project: Project, name: String): String? {
  val map = project.extra
  if (map.has(name)) {
    return map[name] as String?
  }
  return null
}

fun propertyInt(
  project: Project,
  name: String,
): Int {
  val text = property(project, name)
  return text.toInt()
}

fun propertyBoolean(
  project: Project,
  name: String,
): Boolean {
  val text = property(project, name)
  return text.toBooleanStrict()
}

fun propertyBooleanOptional(
  project: Project,
  name: String,
  defaultValue: Boolean,
): Boolean {
  val value = propertyOptional(project, name) ?: return defaultValue
  return value.toBooleanStrict()
}

/**
 * Configure Maven publishing. Artifacts are published to a local directory
 * so that they can be pushed to Maven Central in one step using brooklime.
 */

fun configurePublishingFor(project: Project) {
  val versionName =
    property(project, "VERSION_NAME")
  val packaging =
    property(project, "POM_PACKAGING")

  val publishSources =
    propertyBoolean(project, "com.io7m.build.publishSources")
  val enableSigning =
    propertyBooleanOptional(project, "com.io7m.build.enableSigning", true)

  /*
   * Create an empty JavaDoc jar. Required for Maven Central deployments.
   */

  val taskJavadocEmpty =
    project.task("JavadocEmptyJar", org.gradle.jvm.tasks.Jar::class) {
      this.archiveClassifier = "javadoc"
    }

  /*
   * Create a publication. Note that the name of the publication must be unique across all
   * modules, because the broken Gradle signing plugin will create a signing task for each
   * one that, in the case of a name conflict, will silently overwrite the previous signing
   * task.
   */

  project.publishing {
    publications {
      create<MavenPublication>("_${project.name}_MavenPublication") {
        groupId = property(project, "GROUP")
        artifactId = property(project, "POM_ARTIFACT_ID")
        version = versionName

        /*
         * https://central.sonatype.org/publish/requirements/#sufficient-metadata
         */

        pom {
          name.set(property(project, "POM_NAME"))
          description.set(property(project, "POM_DESCRIPTION"))
          url.set(property(project, "POM_URL"))

          scm {
            connection.set(property(project, "POM_SCM_CONNECTION"))
            developerConnection.set(property(project, "POM_SCM_DEV_CONNECTION"))
            url.set(property(project, "POM_SCM_URL"))
          }

          licenses {
            license {
              name.set(property(project, "POM_LICENCE_NAME"))
              url.set(property(project, "POM_LICENCE_URL"))
            }
          }

          developers {
            developer {
              name.set("The Palace Project")
              email.set("info@thepalaceproject.org")
              organization.set("The Palace Project")
              organizationUrl.set("https://thepalaceproject.org/")
            }
          }
        }

        artifact(taskJavadocEmpty)

        from(
          when (packaging) {
            "jar" -> {
              project.components["java"]
            }

            "aar" -> {
              project.components["release"]
            }

            "apk" -> {
              project.components["release"]
            }

            else -> {
              throw java.lang.IllegalArgumentException(
                "Cannot set up publishing for packaging type $packaging",
              )
            }
          },
        )
      }
    }

    repositories {
      maven {
        name = "Directory"
        url = uri(io7mDeployDirectory)
      }
    }
  }

  /*
   * If source publications are disabled in the project properties, it seems that the only
   * way to stop the Android plugins from publishing sources is to manually "disable" the
   * publication tasks by deleting all of the actions within the tasks, and then specifying
   * a dependency on our own task that produces an empty jar file.
   */

  if (!publishSources) {
    logger.info("com.io7m.build.publishSources is false, so source jars are disabled.")

    val taskSourcesEmpty =
      project.task("SourcesEmptyJar", org.gradle.jvm.tasks.Jar::class) {
        this.archiveClassifier = "sources"
      }

    project.tasks.matching { task -> task.name.endsWith("SourcesJar") }
      .forEach { task ->
        task.actions.clear()
        task.dependsOn.add(taskSourcesEmpty)
      }
  }

  /*
   * Configure signing.
   */

  if (enableSigning) {
    signing {
      useGpgCmd()
      sign(project.publishing.publications)
    }
  }
}

/*
 * A task that cleans up the Maven deployment directory. The "clean" tasks of
 * each project are configured to depend upon this task. This prevents any
 * deployment of stale artifacts to remote repositories.
 */

val cleanTask = task("CleanMavenDeployDirectory", Delete::class) {
  this.delete.add(io7mDeployDirectory)
}

/**
 * A task to unpack native libraries from the SQLite package.
 */

fun createSQLiteUnpackTask(project: Project): Task {
  val commandLineArguments: List<String> = arrayListOf(
    "java",
    "make/UnpackSQLite.java",
    property(project, "VERSION_NAME")
  )

  return project.task("UnpackSQLite", Exec::class) {
    commandLine = commandLineArguments
  }
}

/*
 * Create a task in the root project that unpacks SQLite.
 */

lateinit var sqliteUnpackTask: Task

rootProject.afterEvaluate {
  sqliteUnpackTask = createSQLiteUnpackTask(this)
}

allprojects {

  /*
   * Configure the project metadata.
   */

  this.group =
    property(this, "GROUP")
  this.version =
    property(this, "VERSION_NAME")

  val jdkBuild =
    propertyInt(this, "com.io7m.build.jdkBuild")
  val jdkBytecodeTarget =
    propertyInt(this, "com.io7m.build.jdkBytecodeTarget")

  /*
   * Configure builds and tests for various project types.
   */

  when (extra["POM_PACKAGING"]) {
    "pom" -> {
      logger.info("Configuring ${this.project} $version as a pom project")
    }

    "apk" -> {
      logger.info("Configuring ${this.project} $version as an apk project")

      apply(plugin = "com.android.application")
      apply(plugin = "org.jetbrains.kotlin.android")

      /*
       * Configure the JVM toolchain version that we want to use for Kotlin.
       */

      val kotlin: KotlinAndroidProjectExtension =
        this.extensions["kotlin"] as KotlinAndroidProjectExtension
      val java: JavaPluginExtension =
        this.extensions["java"] as JavaPluginExtension

      kotlin.jvmToolchain(jdkBuild)
      java.toolchain.languageVersion.set(JavaLanguageVersion.of(jdkBuild))

      /*
       * Configure the various required Android properties.
       */

      val android: ApplicationExtension =
        this.extensions["android"] as ApplicationExtension

      android.namespace =
        property(this, "POM_ARTIFACT_ID")
      android.compileSdk =
        propertyInt(this, "com.io7m.build.androidSDKCompile")

      android.defaultConfig {
        multiDexEnabled = true
        targetSdk =
          propertyInt(this@allprojects, "com.io7m.build.androidSDKTarget")
        minSdk =
          propertyInt(this@allprojects, "com.io7m.build.androidSDKMinimum")
      }

      /*
       * Produce JDK bytecode of the correct version.
       */

      tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = jdkBytecodeTarget.toString()
      }
      java.sourceCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)
      java.targetCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)

      android.compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)
        targetCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)
      }
    }

    "aar" -> {
      logger.info("Configuring ${this.project} $version as an aar project")

      apply(plugin = "com.android.library")
      apply(plugin = "org.jetbrains.kotlin.android")

      /*
       * Configure the JVM toolchain version that we want to use for Kotlin.
       */

      val kotlin: KotlinAndroidProjectExtension =
        this.extensions["kotlin"] as KotlinAndroidProjectExtension
      val java: JavaPluginExtension =
        this.extensions["java"] as JavaPluginExtension

      kotlin.jvmToolchain(jdkBuild)
      java.toolchain.languageVersion.set(JavaLanguageVersion.of(jdkBuild))

      /*
       * Configure the various required Android properties.
       */

      val android: LibraryExtension =
        this.extensions["android"] as LibraryExtension

      android.namespace =
        property(this, "POM_ARTIFACT_ID")
      android.compileSdk =
        propertyInt(this, "com.io7m.build.androidSDKCompile")

      android.defaultConfig {
        multiDexEnabled = true
        minSdk = propertyInt(this@allprojects, "com.io7m.build.androidSDKMinimum")
      }

      /*
       * Produce JDK bytecode of the correct version.
       */

      tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = jdkBytecodeTarget.toString()
      }
      java.sourceCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)
      java.targetCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)

      android.compileOptions {
        encoding = "UTF-8"
        sourceCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)
        targetCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)
      }
    }

    "jar" -> {
      logger.info("Configuring ${this.project} $version as a jar project")

      apply(plugin = "java-library")
      apply(plugin = "org.jetbrains.kotlin.jvm")

      /*
       * Configure the JVM toolchain versions that we want to use for Kotlin and Java.
       */

      val kotlin: KotlinProjectExtension =
        this.extensions["kotlin"] as KotlinProjectExtension
      val java: JavaPluginExtension =
        this.extensions["java"] as JavaPluginExtension

      kotlin.jvmToolchain(jdkBuild)
      java.toolchain.languageVersion.set(JavaLanguageVersion.of(jdkBuild))

      /*
       * Produce JDK bytecode of the correct version.
       */

      tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = jdkBytecodeTarget.toString()
      }
      java.sourceCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)
      java.targetCompatibility = JavaVersion.toVersion(jdkBytecodeTarget)

      /*
       * Configure JUnit tests.
       */

      tasks.named<Test>("test") {
        useJUnitPlatform()

        // Required for the Mockito ByteBuddy agent on modern VMs.
        systemProperty("jdk.attach.allowAttachSelf", "true")

        testLogging {
          events("passed")
        }

        this.reports.html.required = true
        this.reports.junitXml.required = true
      }
    }
  }

  /*
   * Configure publishing.
   */

  when (extra["POM_PACKAGING"]) {
    "jar", "aar" -> {
      apply(plugin = "maven-publish")

      afterEvaluate {
        configurePublishingFor(this.project)
      }
    }
  }

  /*
   * Configure some aggressive version resolution behaviour. The listed configurations have
   * transitive dependency resolution enabled; all other configurations do not. This forces
   * projects to be extremely explicit about what is imported.
   */

  val transitiveConfigurations = setOf(
    "androidTestDebugImplementation",
    "androidTestDebugImplementationDependenciesMetadata",
    "androidTestImplementation",
    "androidTestImplementationDependenciesMetadata",
    "androidTestReleaseImplementation",
    "androidTestReleaseImplementationDependenciesMetadata",
    "annotationProcessor",
    "debugAndroidTestCompilationImplementation",
    "debugAndroidTestImplementation",
    "debugAndroidTestImplementationDependenciesMetadata",
    "debugAnnotationProcessor",
    "debugAnnotationProcessorClasspath",
    "debugUnitTestCompilationImplementation",
    "debugUnitTestImplementation",
    "debugUnitTestImplementationDependenciesMetadata",
    "kotlinBuildToolsApiClasspath",
    "kotlinCompilerClasspath",
    "kotlinCompilerPluginClasspath",
    "kotlinCompilerPluginClasspathDebug",
    "kotlinCompilerPluginClasspathDebugAndroidTest",
    "kotlinCompilerPluginClasspathDebugUnitTest",
    "kotlinCompilerPluginClasspathMain",
    "kotlinCompilerPluginClasspathRelease",
    "kotlinCompilerPluginClasspathReleaseUnitTest",
    "kotlinCompilerPluginClasspathTest",
    "kotlinKlibCommonizerClasspath",
    "kotlinNativeCompilerPluginClasspath",
    "kotlinScriptDef",
    "kotlinScriptDefExtensions",
    "mainSourceElements",
    "releaseAnnotationProcessor",
    "releaseAnnotationProcessorClasspath",
    "releaseUnitTestCompilationImplementation",
    "releaseUnitTestImplementation",
    "releaseUnitTestImplementationDependenciesMetadata",
    "testDebugImplementation",
    "testDebugImplementationDependenciesMetadata",
    "testFixturesDebugImplementation",
    "testFixturesDebugImplementationDependenciesMetadata",
    "testFixturesImplementation",
    "testFixturesImplementationDependenciesMetadata",
    "testFixturesReleaseImplementation",
    "testFixturesReleaseImplementationDependenciesMetadata",
    "testImplementation",
    "testImplementationDependenciesMetadata",
    "testReleaseImplementation",
    "testReleaseImplementationDependenciesMetadata",
  )

  /*
   * Write the set of available configurations to files, for debugging purposes. Plugins can
   * add new configurations at any time, and so it's nice to have a list of the available
   * configurations visible.
   */

  val configurationsActual = mutableSetOf<String>()
  afterEvaluate {
    configurations.all {
      configurationsActual.add(this.name)
    }
    // File("configurations.txt").writeText(configurationsActual.joinToString("\n"))
  }

  afterEvaluate {
    configurations.all {
      isTransitive = transitiveConfigurations.contains(name)
      // resolutionStrategy.failOnVersionConflict()
    }
  }

  /*
   * Configure all compile tasks to depend upon the SQLite unpack task.
   */

  afterEvaluate {
    tasks.matching { task -> task.name == "assemble" }
      .forEach { task -> task.dependsOn(sqliteUnpackTask) }
  }

  /*
   * Configure all "test" tasks to be disabled. The tests are enabled only in those modules
   * that specifically ask for them. Why do this? Because the Android plugins do lots of
   * expensive per-module configuration for tests that don't exist.
   */

  afterEvaluate {
    tasks.matching { task -> task.name.contains("UnitTest") }
      .forEach { task -> task.enabled = false }
  }
}
