import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.OutputStream

val generatedMacOsIcon = layout.buildDirectory.file("generated/macos/icon.icns")

// Раскладка образа приложения. Compose собирает его только под ту ОС, где
// запущен, поэтому выбрать её можно прямо здесь — клиенту не нужно выяснять
// это на старте и перебирать каталоги в поисках плагинов.
val isMacOsHost = System.getProperty("os.name").lowercase().contains("mac")

/** Куда Compose кладёт образ. */
val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app")

/** Само приложение внутри образа: у macOS это бандл со своей раскладкой. */
val appImageEntry = if (isMacOsHost) "Bylins Client.app" else "Bylins Client"

/**
 * Каталог плагинов внутри образа.
 *
 * PluginManager ищет их на уровень выше своего jar-а: у Windows и Linux тот
 * лежит в `app/`, у macOS — в `Contents/app/`. Значит `<образ>/plugins` и
 * `Contents/plugins` — одно и то же место с точки зрения поиска, и правило
 * поиска остаётся одно на все платформы.
 */
val pluginsInImage = if (isMacOsHost) "$appImageEntry/Contents/plugins" else "$appImageEntry/plugins"

/** Скрипты в исходниках. */
val scriptsDir = file("scripts")

/** Скрипты ищутся тем же правилом, что и плагины, — значит и лежат рядом. */
val scriptsInImage = if (isMacOsHost) "$appImageEntry/Contents/scripts" else "$appImageEntry/scripts"

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("org.jetbrains.compose") version "1.5.12"
}

group = "com.bylins"
version = "1.0.3"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    // Compose for Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // Coroutines для асинхронной работы
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")

    // JSON для конфигов
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Логирование
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Apache Commons для telnet (базовая поддержка)
    implementation("commons-net:commons-net:3.10.0")

    // Scripting engines
    // Nashorn для JavaScript скриптов (standalone для JDK 15+)
    implementation("org.openjdk.nashorn:nashorn-core:15.4")
    // Jython для Python скриптов
    implementation("org.python:jython-standalone:2.7.3")
    // LuaJ для Lua скриптов
    implementation("org.luaj:luaj-jse:3.0.1")

    // SQLite для хранения карт
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")

    // YAML для plugin.yml
    implementation("org.yaml:snakeyaml:2.0")

    // Plugin modules
    // Note: Only plugins:core is a compile-time dependency
    // Assistant plugin is loaded at runtime via PluginManager from plugins/assistant.jar
    implementation(project(":plugins:core"))

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material.ExperimentalMaterialApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        )
    }
}

compose.desktop {
    application {
        mainClass = "com.bylins.client.MainKt"

        // Принудительно UTF-8 для всего JVM
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-DCONSOLE_CHARSET=UTF-8",
            // Хоткеи определяются по физической клавише: её код лежит в
            // приватном поле события AWT, читать его иначе нечем
            "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Bylins Client"
            // Одна версия на всё: в именах архивов, в метаданных
            // установщика и в теге релиза. Разойтись им нельзя —
            // разошлись однажды, и релиз v0.1.0 приехал с каталогами
            // bylins-client-1.0.0.
            //
            // Ограничение, о которое легко споткнуться: для DMG Compose
            // требует MAJOR > 0, и «0.x» роняет конфигурацию сборки на
            // любой платформе, а не только на macOS
            packageVersion = version.toString()

            // JRE собирается через jlink, и по умолчанию в неё попадает голый
            // минимум — дистрибутив падал ещё до окна: сначала «java/sql/Driver»,
            // затем «jdk/dynalink/RelinkableCallSite».
            //
            // Список модулей тут не помогает: jdeps видит только статические
            // ссылки, а движки скриптов (Nashorn, Jython, LuaJ) и JDBC-драйверы
            // грузятся рефлексией и через ServiceLoader. Каждый такой модуль
            // всплывал бы у игрока по одному, причём не при запуске, а когда
            // он впервые полезет в скрипты. Полсотни мегабайт дешевле.
            includeAllModules = true

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
            macOS {
                iconFile.set(generatedMacOsIcon)
            }
        }
    }
}

val exportMacOsIcon by tasks.registering {
    inputs.dir("src/main/resources/macos.icon")
    outputs.file(generatedMacOsIcon)
    onlyIf { System.getProperty("os.name").contains("Mac", ignoreCase = true) }

    doLast {
        val source = project.file("src/main/resources/macos.icon")
        val sourcePng = project.file("src/main/resources/macos.icon/Assets/alatir.png")
        val iconset = layout.buildDirectory.dir("generated/macos.iconset").get().asFile
        iconset.deleteRecursively()
        iconset.mkdirs()

        val ictool = listOf(
            file("/Applications/Xcode.app/Contents/Applications/Icon Composer.app/Contents/Executables/ictool"),
            // можно добавить другие пути, если кто-то установил Xcode в нестандартное место (бета-версии, альтернативные сборки)
        ).firstOrNull { candidate ->
            candidate.canExecute() && runCatching {
                exec {
                    isIgnoreExitValue = true
                    commandLine(candidate.absolutePath, "--help")
                     // чтобы не засорять консоль
                    standardOutput = OutputStream.nullOutputStream()
                    errorOutput = OutputStream.nullOutputStream()
                }.exitValue == 0
            }.getOrDefault(false)
        }

        val sizes = listOf(
            "icon_16x16@2x.png" to 16,
            "icon_32x32@2x.png" to 32,
            // чтобы не было дополнительного отступа
            // не используем размеры 128 и 256
            "icon_512x512@2x.png" to 512
        )

        val scale = 2 // создаём только @2x, система уменьшит до @1x сама если надо
        if (ictool != null) {
            val tool = ictool.absolutePath
            val sourcePath = source.absolutePath
            sizes.forEach { (name, size) ->
                exec {
                    commandLine(
                        tool, sourcePath, "--export-image",
                        "--output-file", iconset.resolve(name).absolutePath,
                        "--platform", "macOS", "--rendition", "Dark",
                        "--width", size, "--height", size, "--scale", scale
                    )
                    // чтобы не засорять консоль
                    standardOutput = OutputStream.nullOutputStream()
                }
            }
        } else {
            val sourcePath = sourcePng.absolutePath
            sizes.forEach { (name, size) ->
                exec {
                    val scaledSize = size * scale
                    commandLine("/usr/bin/sips", "-z", scaledSize, scaledSize,
                        sourcePath, "--out", iconset.resolve(name).absolutePath)
                    // чтобы не засорять консоль
                    standardOutput = OutputStream.nullOutputStream()
                }
            }
        }

        exec {
            commandLine("/usr/bin/iconutil", "-c", "icns", "-o",
                generatedMacOsIcon.get().asFile.absolutePath, iconset.absolutePath)

            // чтобы не засорять консоль
            standardOutput = OutputStream.nullOutputStream()
        }
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(exportMacOsIcon)
}
tasks.matching { it.name == "processResources" }.configureEach {
    dependsOn(exportMacOsIcon)
}

tasks.test {
    useJUnitPlatform()
}

// === Плагины и скрипты внутрь образа ===
//
// Без плагинов клиент неполон: карта, миникарта и MSDP живут в ассистенте.
// `createDistributable` о них не знает, поэтому докладываем их сами — после
// него, потому что он пересоздаёт каталог образа целиком.
//
// Установщики на macOS (dmg, pkg) Compose собирает из готового образа — они
// получают плагины и скрипты вместе с ним, но только если копирование
// отработало раньше упаковки (см. ниже). На Windows и Linux msi и deb
// собираются не из образа, а из каталога с jar-ами, и всё положенное туда
// попало бы в classpath приложения — плагины перестали бы выгружаться. Там
// комплект для раздачи собирает только releaseDist.
val copyPluginsToImage by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Кладёт плагины рядом с приложением внутри образа"

    dependsOn(":plugins:assistant:buildPlugin", ":plugins:ai-control:buildPlugin")

    from(project(":plugins:assistant").layout.buildDirectory.file("libs/assistant.jar"))
    from(project(":plugins:ai-control").layout.buildDirectory.file("libs/ai-control.jar"))
    into(appImageDir.map { it.dir(pluginsInImage) })
}

// Внутрь, а не рядом: то, что лежит около бандла, пропадает при переносе
// приложения в Applications
val copyScriptsToImage by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Кладёт скрипты рядом с приложением внутри образа"

    from(scriptsDir) { exclude("*.disabled") }
    into(appImageDir.map { it.dir(scriptsInImage) })
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(copyPluginsToImage, copyScriptsToImage)
}

// На macOS установщик читает образ, в который пишут задачи копирования.
// Без явной зависимости Gradle отказывается собирать: вывод чужой задачи
// используется без объявления — и порядок не гарантирован.
tasks.matching { it.name == "packageDmg" || it.name == "packagePkg" }.configureEach {
    dependsOn(copyPluginsToImage, copyScriptsToImage)
}

// Только для запуска из Gradle: в дистрибутиве плагины лежат рядом с приложением,
// и зашитый сюда путь увёл бы установленный клиент искать их в build/run/plugins
tasks.matching { it.name == "run" }.configureEach {
    (this as? JavaExec)?.systemProperty("bylins.plugins.dir", "build/run/plugins")
}

// === Prepare Run Directory ===

val prepareRun by tasks.registering(Copy::class) {
    group = "application"
    description = "Prepares run directory with plugins and scripts"

    dependsOn(":plugins:assistant:buildPlugin", ":plugins:ai-control:buildPlugin")

    // Создаём директорию для плагинов
    doFirst {
        layout.buildDirectory.dir("run/plugins").get().asFile.mkdirs()
    }

    // Копируем JAR плагинов
    from(project(":plugins:assistant").layout.buildDirectory.file("libs/assistant.jar"))
    from(project(":plugins:ai-control").layout.buildDirectory.file("libs/ai-control.jar"))
    into(layout.buildDirectory.dir("run/plugins"))
}

// Копирование скриптов
val prepareScripts by tasks.registering(Copy::class) {
    group = "application"
    description = "Copies scripts to run directory"

    from("scripts") {
        exclude("*.disabled")
    }
    into(layout.buildDirectory.dir("run/scripts"))
}

// Обновление плагинов в УЖЕ ЗАПУЩЕННОМ клиенте.
//
// Перезагрузка плагина в UI читает JAR из снимка, зафиксированного при старте,
// поэтому свежую сборку туда нужно положить. JAR загруженного плагина занят
// classloader'ом — сначала выгрузите плагин в UI («Выгрузить»), затем эта
// задача, затем «Загрузить».
val deployPlugins by tasks.registering {
    group = "application"
    description = "Кладёт свежие плагины в активный снимок (для #plugin reload)"

    dependsOn(":plugins:assistant:buildPlugin", ":plugins:ai-control:buildPlugin")

    doLast {
        val appDir = layout.buildDirectory.dir("app").get().asFile
        val currentFile = File(appDir, "current.txt")
        if (!currentFile.exists()) {
            println("Снимок не найден — клиент ещё ни разу не запускался через bylins.bat")
            return@doLast
        }
        val target = File(appDir, currentFile.readText().trim() + "/plugins")

        listOf(
            project(":plugins:assistant").layout.buildDirectory.file("libs/assistant.jar"),
            project(":plugins:ai-control").layout.buildDirectory.file("libs/ai-control.jar")
        ).forEach { provider ->
            val jar = provider.get().asFile
            val dest = File(target, jar.name)
            val ok = runCatching { jar.copyTo(dest, overwrite = true) }.isSuccess
            println(if (ok) "обновлён: ${dest.name}" else "занят (выгрузите плагин в UI): ${dest.name}")
        }
    }
}

// Задача для полной подготовки директории запуска
val prepareRunDir by tasks.registering {
    group = "application"
    description = "Prepares complete run directory"

    dependsOn(prepareRun, prepareScripts)
}

// === Изолированный снимок приложения (build/app) ===
//
// Клиент запускается ИЗ СНИМКА, а не из build/classes. Иначе пересборка во
// время работы подменяет .class-файлы под живой JVM, и приложение падает,
// когда доходит до ленивой загрузки изменённого класса (NoClassDefFoundError).
// Снимок обновляется только этой задачей — обычные compileKotlin/buildPlugin
// его не касаются.
// Сторонние зависимости кладём отдельно и один раз: они весят сотни мегабайт
// и от правок кода не меняются, поэтому копировать их в каждый снимок незачем.
val stageDeps by tasks.registering(Sync::class) {
    group = "application"
    description = "Копирует зависимости приложения в build/app/deps"

    from(configurations.named("runtimeClasspath"))
    into(layout.buildDirectory.dir("app/deps"))
}

// Версионированный снимок: каждая сборка кладётся в свою папку, а ярлык
// читает build/app/current.txt. Так снимок можно обновлять ДАЖЕ при
// работающем клиенте — он продолжает читать свою (старую) папку, а
// следующий запуск подхватит новую.
val stageApp by tasks.registering {
    group = "application"
    description = "Собирает версионированный снимок приложения в build/app"

    dependsOn(stageDeps, tasks.named("jar"), ":plugins:assistant:buildPlugin", ":plugins:ai-control:buildPlugin")

    doLast {
        val appDir = layout.buildDirectory.dir("app").get().asFile
        val version = "v${System.currentTimeMillis()}"
        val target = File(appDir, version)

        copy {
            from(tasks.named("jar"))
            into(File(target, "lib"))
        }
        copy {
            from(project(":plugins:assistant").layout.buildDirectory.file("libs/assistant.jar"))
            from(project(":plugins:ai-control").layout.buildDirectory.file("libs/ai-control.jar"))
            into(File(target, "plugins"))
        }
        copy {
            from("scripts") { exclude("*.disabled") }
            into(File(target, "scripts"))
        }

        // Указатель для скрипта запуска
        File(appDir, "current.txt").writeText(version)

        // Чистим старые снимки, кроме текущего. Занятые запущенным клиентом
        // удалить не получится — это нормально, уберутся в следующий раз.
        appDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("v") && it.name != version }
            ?.forEach { runCatching { it.deleteRecursively() } }

        println("Снимок готов: ${target.absolutePath}")
    }
}

// Обновляем run задачу чтобы зависела от prepareRun
afterEvaluate {
    tasks.findByName("run")?.dependsOn(prepareRunDir)
}

// === Packaging Tasks ===

val userHome: String = System.getProperty("user.home")
val userDataDir = file("$userHome/.bylins-client")
val packageDir = layout.buildDirectory.dir("package")

// Task to create install script for Windows
val createInstallScript by tasks.registering {
    val outputFile = packageDir.map { it.file("install-userdata.bat") }
    outputs.file(outputFile)

    doLast {
        outputFile.get().asFile.parentFile.mkdirs()
        outputFile.get().asFile.writeText("""
            @echo off
            chcp 65001 >nul
            echo === Bylins Client Installer ===
            echo.

            set "TARGET=%USERPROFILE%\.bylins-client"

            if exist "userdata\config.json" (
                echo Installing config...
                if not exist "%TARGET%" mkdir "%TARGET%"
                copy /Y "userdata\config.json" "%TARGET%\" >nul
            )

            if exist "userdata\maps\maps.db" (
                echo Installing maps...
                if not exist "%TARGET%\maps" mkdir "%TARGET%\maps"
                copy /Y "userdata\maps\maps.db" "%TARGET%\maps\" >nul
            )

            echo.
            echo Done! User data installed to: %TARGET%
            echo.
            echo Now run "Bylins Client.exe" to start the client.
            pause
        """.trimIndent())
    }
}

// Task to create README
val createReadme by tasks.registering {
    val outputFile = packageDir.map { it.file("README.txt") }
    outputs.file(outputFile)

    doLast {
        outputFile.get().asFile.parentFile.mkdirs()
        outputFile.get().asFile.writeText("""
            ===============================================
                        BYLINS MUD CLIENT
            ===============================================

            БЫСТРЫЙ СТАРТ
            -------------
            1. Запусти install-userdata.bat (установит настройки и карту)
            2. Запусти "Bylins Client.exe"
            3. Готово!


            СОДЕРЖИМОЕ АРХИВА
            -----------------
            Bylins Client.exe    - Главный исполняемый файл
            app/                 - Файлы приложения (не трогать)
            runtime/             - Java Runtime (не трогать)
            scripts/             - Скрипты автоматизации
            userdata/            - Настройки и карта для установки
            install-userdata.bat - Установщик данных пользователя
            README.txt           - Этот файл


            РУЧНАЯ УСТАНОВКА
            ----------------
            Если install-userdata.bat не работает, скопируй вручную:

            ИЗ АРХИВА                      КУДА КОПИРОВАТЬ
            ─────────────────────────────────────────────────────────────
            userdata/config.json      ->   %USERPROFILE%\.bylins-client\config.json
            userdata/maps/maps.db     ->   %USERPROFILE%\.bylins-client\maps\maps.db

            Где %USERPROFILE% - это твоя домашняя папка, например:
            C:\Users\Vasya\.bylins-client\


            СТРУКТУРА ПАПОК
            ---------------
            После установки должно получиться:

            C:\Users\<ИМЯ>\.bylins-client\
            ├── config.json              <- Настройки, триггеры, алиасы, хоткеи
            └── maps\
                └── maps.db              <- База данных карты

            <ПАПКА С КЛИЕНТОМ>\
            ├── Bylins Client.exe        <- Запускай это
            └── scripts\
                └── bylins_msdp.js       <- Скрипт для MSDP/статус-панели


            ЧТО ВКЛЮЧЕНО В НАСТРОЙКИ
            ------------------------
            - Триггеры
            - Алиасы
            - Горячие клавиши
            - Профили подключения
            - Тема оформления
            - Настройки шрифтов


            ГОРЯЧИЕ КЛАВИШИ ПО УМОЛЧАНИЮ
            ----------------------------
            Numpad 8/2/4/6  - Движение (север/юг/запад/восток)
            Numpad 7/9/1/3  - Движение (сз/св/юз/юв)
            Numpad +/-      - Вверх/вниз
            Ctrl+L          - Очистить экран
            Tab             - Автодополнение команд


            ПРОБЛЕМЫ?
            ---------
            - Клиент не запускается: проверь что установлена Java 17+
            - Карта не загрузилась: проверь что maps.db в правильной папке
            - Скрипты не работают: проверь что папка scripts рядом с .exe

        """.trimIndent(), Charsets.UTF_8)
    }
}

// Main packaging task
val packageWithUserData by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Creates a distributable ZIP with app, scripts, and user data"

    dependsOn("createDistributable", createInstallScript, createReadme)

    archiveFileName.set("bylins-client-${version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    // App files
    from(layout.buildDirectory.dir("compose/binaries/main/app/Bylins Client")) {
        into("")
    }

    // Scripts (exclude .disabled files)
    from(scriptsDir) {
        into("scripts")
        exclude("*.disabled")
    }

    // User config
    from(file("$userDataDir/config.json")) {
        into("userdata")
    }

    // Maps database
    from(file("$userDataDir/maps/maps.db")) {
        into("userdata/maps")
    }

    // Install script and readme
    from(packageDir) {
        include("install-userdata.bat", "README.txt")
    }

    doLast {
        println("\n=== Package Complete ===")
        println("Output: ${archiveFile.get().asFile.absolutePath}")
        println("Size: ${archiveFile.get().asFile.length() / 1024 / 1024} MB")
    }
}

// Quick package without user data (just app + scripts)
val packageApp by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Creates a distributable ZIP with app and scripts only (no user data)"

    dependsOn("createDistributable")

    archiveFileName.set("bylins-client-${version}-app.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    from(layout.buildDirectory.dir("compose/binaries/main/app/Bylins Client")) {
        into("")
    }

    from(scriptsDir) {
        into("scripts")
        exclude("*.disabled")
    }
}

// JAR package (smaller, cross-platform, requires Java)
val packageJar by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Creates a lightweight ZIP with JAR (requires Java 17+)"

    dependsOn("packageUberJarForCurrentOS", createInstallScript, createReadme)

    archiveFileName.set("bylins-client-${version}-jar.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    // JAR file
    from(layout.buildDirectory.dir("compose/jars")) {
        include("*.jar")
    }

    // Scripts
    from(scriptsDir) {
        into("scripts")
        exclude("*.disabled")
    }

    // User data
    from(file("$userDataDir/config.json")) {
        into("userdata")
    }
    from(file("$userDataDir/maps/maps.db")) {
        into("userdata/maps")
    }

    // Install script and readme
    from(packageDir) {
        include("install-userdata.bat", "README.txt")
    }

    // Run script for Windows
    doFirst {
        val runScript = packageDir.get().file("run.bat").asFile
        runScript.parentFile.mkdirs()
        runScript.writeText("""
            @echo off
            java -jar bylins-client-windows-x64-${version}.jar
            pause
        """.trimIndent())
    }

    from(packageDir) {
        include("run.bat")
    }

    doLast {
        println("\n=== JAR Package Complete ===")
        println("Output: ${archiveFile.get().asFile.absolutePath}")
        println("Size: ${archiveFile.get().asFile.length() / 1024 / 1024} MB")
        println("Note: Requires Java 17+ installed")
    }
}

// --- Сборка для релиза ---

/** Метка платформы в имени архива: сборка Compose всегда под ОС, где её делали. */
val osTag: String = System.getProperty("os.name").lowercase().let { name ->
    when {
        name.contains("win") -> "windows"
        name.contains("mac") -> "macos"
        else -> "linux"
    }
}

/**
 * Готовый к запуску комплект: приложение с собственной JRE, плагины и скрипты.
 *
 * Отдельно от packageApp, потому что тот не кладёт плагины — а без ассистента и
 * ai-control клиент неполон. Собирается на каждой платформе своя: Compose тянет
 * платформозависимый Skiko, кроссплатформенного архива тут не бывает.
 *
 * Каталогом, а не архивом: GitHub Actions упаковывает артефакт сам, и готовый
 * zip внутри давал бы архив в архиве.
 */
val releaseDist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Каталог для релиза: приложение, плагины и скрипты"

    dependsOn("createDistributable", copyPluginsToImage, copyScriptsToImage)

    into(layout.buildDirectory.dir("release/bylins-client-$version-$osTag"))

    // Плагины уже внутри образа, отдельно досыпать нечего. У macOS в архив
    // едет бандл целиком: всё, что лежит рядом с ним, а не внутри, пропадёт,
    // как только пользователь перетащит приложение в Applications.
    if (isMacOsHost) {
        from(appImageDir)
    } else {
        from(appImageDir.map { it.dir(appImageEntry) })
    }

    // Мост для ИИ-агентов: чистый Python без зависимостей, полезен рядом с клиентом
    from("plugins/ai-control/mcp") {
        into("mcp")
        include("bylins_mcp.py")
    }
}

/** Тот же комплект архивом — для раздачи вручную, вне CI. */
val releaseBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Архив для релиза: приложение, плагины и скрипты"

    dependsOn(releaseDist)

    archiveFileName.set("bylins-client-$version-$osTag.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(releaseDist.map { it.destinationDir })

    doLast {
        val file = archiveFile.get().asFile
        println("Архив: ${file.absolutePath} (${file.length() / 1024 / 1024} МБ)")
    }
}
