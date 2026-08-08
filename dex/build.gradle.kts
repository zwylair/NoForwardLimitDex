import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

val customPropertiesFile = project.rootProject.file("API_KEYS")
val customProps = Properties()

if (customPropertiesFile.exists()) {
    customPropertiesFile.inputStream().use { stream ->
        customProps.load(stream)
    }
} else {
    logger.warn("Warning: API_KEYS file not found!")
}

val telegramDir = file(providers.gradleProperty("telegram.dir").get())
val botToken: String? = customProps.getProperty("telegram.bot.token")
val nflPath = providers.gradleProperty("nfl.plugin.path").get()

val androidSdkDir = file(providers.gradleProperty("android.sdk.dir").get())
val compileSdkVersion = providers.gradleProperty("android.compile.sdk").get()

val exteraHookVersion = providers.gradleProperty("exterahook.version").get()
val exteraHookAar = file("$telegramDir/Tools/maven/dev/exterahook/exterahook/$exteraHookVersion/exterahook-$exteraHookVersion.aar")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

val aarCompileOnly by configurations.creating { isTransitive = true }

val extractAarsDir = layout.buildDirectory.dir("extracted-aars")

val extractAarsTask = tasks.register("extractAars") {
    inputs.files(aarCompileOnly)
    outputs.dir(extractAarsDir)

    doLast {
        val outDir = extractAarsDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        aarCompileOnly.files.forEach { file ->
            val baseName = file.nameWithoutExtension

            if (file.extension == "aar") {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        if (entry.name == "classes.jar") {
                            val outFile = File(outDir, "$baseName-classes.jar")
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                        else if (entry.name.startsWith("libs/") && entry.name.endsWith(".jar")) {
                            val cleanName = entry.name.substringAfterLast("/")
                            val outFile = File(outDir, "$baseName-$cleanName")
                            zip.getInputStream(entry).use { input ->
                                outFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            } else if (file.extension == "jar") {
                file.copyTo(File(outDir, file.name), overwrite = true)
            }
        }
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))

    compileOnly(files("$androidSdkDir/platforms/android-$compileSdkVersion/android.jar"))
    aarCompileOnly("androidx.core:core-ktx:1.18.0")

    compileOnly(files(file("$projectDir/libs/exteragram-clean-stubs.jar")))
    if (exteraHookAar.exists()) {
        aarCompileOnly(files(exteraHookAar))
    } else {
        println("Warning: exterahook AAR not found at ${exteraHookAar.absolutePath}")
    }

    compileOnly(fileTree(extractAarsDir) {
        include("*.jar")
    }.builtBy(extractAarsTask))
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

fun sendTelegramDocument(
    token: String,
    chatId: String,
    file: File,
    caption: String,
    messageThreadId: String? = null
) {
    val boundary = "---GradleTelegramBoundary" + System.currentTimeMillis()
    val lineFeed = "\r\n"
    val url = URL("https://api.telegram.org/bot$token/sendDocument")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        doInput = true
        useCaches = false
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    }

    connection.outputStream.use { output ->
        PrintWriter(OutputStreamWriter(output, "UTF-8"), true).use { writer ->
            // Поле chat_id
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"chat_id\"").append(lineFeed)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineFeed)
            writer.append(lineFeed).append(chatId).append(lineFeed)

            // Поле message_thread_id (для топиков/форумов)
            if (!messageThreadId.isNullOrEmpty()) {
                writer.append("--$boundary").append(lineFeed)
                writer.append("Content-Disposition: form-data; name=\"message_thread_id\"").append(lineFeed)
                writer.append("Content-Type: text/plain; charset=UTF-8").append(lineFeed)
                writer.append(lineFeed).append(messageThreadId).append(lineFeed)
            }

            // Поле caption
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"caption\"").append(lineFeed)
            writer.append("Content-Type: text/plain; charset=UTF-8").append(lineFeed)
            writer.append(lineFeed).append(caption).append(lineFeed)
            writer.flush()

            // Файл
            writer.append("--$boundary").append(lineFeed)
            writer.append("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"").append(lineFeed)
            writer.append("Content-Type: application/octet-stream").append(lineFeed)
            writer.append(lineFeed)
            writer.flush()

            file.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()

            writer.append(lineFeed)
            writer.append("--$boundary--").append(lineFeed)
            writer.flush()
        }
    }

    val responseCode = connection.responseCode
    if (responseCode == HttpURLConnection.HTTP_OK) {
        println("Успешно отправлено в Telegram!")
    } else {
        val errorStream = connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
        println("Ошибка отправки в Telegram (код $responseCode): $errorStream")
    }
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

            val compressedBytes = ByteArrayOutputStream().use { os ->
                val process = ProcessBuilder("python", "-c",
                    "import sys, lzma; sys.stdout.buffer.write(lzma.compress(sys.stdin.buffer.read(), format=lzma.FORMAT_XZ, preset=9 | lzma.PRESET_EXTREME))"
                ).redirectError(ProcessBuilder.Redirect.INHERIT).start()

                process.outputStream.use { it.write(inputBytes) }
                process.inputStream.copyTo(os)
                process.waitFor()
                os.toByteArray()
            }

            val base64Dex = Base64.getEncoder().encodeToString(compressedBytes)

            println("Размер исходного DEX: ${inputBytes.size} байт")
            println("Размер после LZMA: ${compressedBytes.size} байт")

            val nflPluginFile = file(nflPath)
            if (nflPluginFile.exists()) {
                val content = nflPluginFile.readText()
                val updatedContent = content.replace(
                    Regex("""DEX_BASE64\s*=\s*".*?""""),
                    """DEX_BASE64 = "$base64Dex""""
                )
                nflPluginFile.writeText(updatedContent)
                println("Успешно обновлено в ${nflPluginFile.name}")
            } else {
                println("Файл ${nflPluginFile.absolutePath} не найден!")
            }

            if (!botToken.isNullOrEmpty()) {
                println("Отправка DEX файла в Telegram (форум/топик)...")
                val caption = "Сборка завершена!\n" +
                        "Исходный размер: ${inputBytes.size} байт\n" +
                        "LZMA сжатие: ${compressedBytes.size} байт"

                val targetChatId = customProps.getProperty("telegram.send.target")
                val targetTopicId: String? = customProps.getProperty("telegram.send.target.topic")

                try {
                    sendTelegramDocument(
                        token = botToken,
                        chatId = targetChatId,
                        messageThreadId = targetTopicId,
                        file = nflPluginFile,
                        caption = caption
                    )
                } catch (e: Exception) {
                    println("Не удалось отправить сообщение в Telegram:")
                    e.printStackTrace()
                }
            } else {
                println("Токен бота не найден в файле ключей API_KEYS. Пропуск отправки в Telegram.")
            }
        }
    }
}
