import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

val telegramDir = file(providers.gradleProperty("telegram.dir").get())
val androidSdkDir = file(providers.gradleProperty("android.sdk.dir").get())
val compileSdkVersion = providers.gradleProperty("android.compile.sdk").get()
val nflPath = providers.gradleProperty("nfl.plugin.path").get()

val exteraHookAar = file("$telegramDir/Tools/maven/dev/exterahook/exterahook/0.2.3/exterahook-0.2.3.aar")
val exteraHookJar = file("$projectDir/libs/exterahook-0.2.3.jar")
val exteraStubs = file("$projectDir/libs/exteragram-stubs.jar")

if (exteraHookAar.exists() && !exteraHookJar.exists()) {
    exteraHookJar.parentFile.mkdirs()
    ZipFile(exteraHookAar).use { zip ->
        val entry = zip.getEntry("classes.jar")
        if (entry != null) {
            zip.getInputStream(entry).use { input ->
                exteraHookJar.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    compileOnly(files("$androidSdkDir/platforms/android-$compileSdkVersion/android.jar"))
    compileOnly(files(exteraHookJar))
    compileOnly(files(exteraStubs))
    compileOnly(kotlin("stdlib"))
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xskip-metadata-version-check",
            "-Xextended-compiler-checks=false",
        )
    }
}

val pluginJar = tasks.register<Jar>("pluginJar") {
    dependsOn(tasks.compileKotlin)
    archiveFileName.set("plugin-only.jar")
    from(tasks.compileKotlin.get().destinationDirectory)
}

tasks.register<Exec>("buildDex") {
    dependsOn(pluginJar)

    val jarFile = pluginJar.get().archiveFile.get().asFile
    val outputDir = layout.buildDirectory.dir("outputs/dex").get().asFile
    val androidJar = file("$androidSdkDir/platforms/android-$compileSdkVersion/android.jar")

    val buildTools = file("$androidSdkDir/build-tools").listFiles()?.maxByOrNull { it.name }!!
    val d8 = if (System.getProperty("os.name").lowercase().contains("win")) "${buildTools}/d8.bat" else "${buildTools}/d8"

    doFirst { outputDir.mkdirs() }

    commandLine(
        d8,
        "--lib", androidJar.absolutePath,
        "--output", outputDir.absolutePath,
        jarFile.absolutePath
    )

    doLast {
        val dexFile = file("${outputDir.absolutePath}/classes.dex")
        if (dexFile.exists()) {
            val inputBytes = dexFile.readBytes()

            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            deflater.setInput(inputBytes)
            deflater.finish()

            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(1024)

            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                outputStream.write(buffer, 0, count)
            }
            deflater.end()

            val compressedBytes = outputStream.toByteArray()
            val base64Dex = Base64.getEncoder().encodeToString(compressedBytes)

            println(base64Dex)

            val targetFile = file(nflPath)
            if (targetFile.exists()) {
                val content = targetFile.readText()
                val updatedContent = content.replace(
                    Regex("""DEX_BASE64\s*=\s*".*?""""),
                    """DEX_BASE64 = "$base64Dex""""
                )
                targetFile.writeText(updatedContent)
                println("Успешно обновлено в ${targetFile.name}")
            } else {
                println("Файл ${targetFile.absolutePath} не найден!")
            }
        }
    }
}
