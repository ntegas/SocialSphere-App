import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
}

// Релизная подпись: значения из keystore.properties в корне модуля (НЕ в репозитории).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
  if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
  namespace = "com.aistudio.socialsphere.crmlxb"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.socialsphere.crmlxb"
    minSdk = 24
    targetSdk = 36
    versionCode = 11
    versionName = "1.4.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      if (keystorePropsFile.exists()) {
        storeFile = file(keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      // Подпись только при наличии keystore.properties — иначе debug/CI не ломаются
      if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    debug {
      // Без суффикса — проще устанавливать на телефон без конфликтов
    }
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }

  // Ловит хардкоженные строки мимо stringResource()/@string — единственный
  // способ поймать ВСЕ такие места (не только те, что нашли грепом вручную).
  // См. правило локализации в SOCIALSPHERE_KNOWLEDGE.md.
  lint {
    checkReleaseBuilds = false
    abortOnError = false
    error += "HardcodedText"
  }
}

// Kotlin 2.0: jvmTarget задаётся только через compilerOptions (старый DSL запрещён правилами)
kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

// Room: экспорт схемы в app/schemas — фиксирует каждую версию БД в git,
// чтобы пропуск миграции (как был v5→v6) ловился на сборке, а не на устройстве.
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = "local.properties"
  defaultPropertiesFileName = "local.properties.example"
  ignoreList.add("keyToIgnore")
  ignoreList.add("sdk.*")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  coreLibraryDesugaring(libs.android.tools.desugar)
  implementation(platform(libs.androidx.compose.bom))
  // accompanist-permissions УБРАН (2026-07-02): 0.37.x требует Compose 1.8, на
  // BOM 2024.09 падал в рантайме (крэш сканера). Разрешения — Activity Result API.
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.fragment.ktx) // фикс lintVital: fragment ≥1.3 для ActivityResult
  // Per-app язык через AppCompatDelegate.setApplicationLocales (2026-07-22) —
  // заменяет самодельный createConfigurationContext-хак (LocalizedApp).
  implementation(libs.androidx.appcompat)
  // Камера-сканер визитки (CameraX) + OCR (Tesseract4Android)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.tesseract4android)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // Фото контактов (photoUri): выбор из галереи + показ. Coil 2.7.0 —
  // Maven Central, без требований к AGP (пин 8.5.2 не трогаем).
  implementation(libs.coil.compose)
  // Биометрия/код устройства для «Защищено» (тянет androidx.fragment —
  // MainActivity переведена на FragmentActivity, требование BiometricPrompt)
  implementation(libs.androidx.biometric)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.maps.compose)
  implementation(libs.play.services.maps)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// ── Языковые данные Tesseract для сканера визиток ────────────────────────────
// Тянем fast-модели (eng/rus/ell, Apache-2.0) в assets/tessdata, чтобы они
// вшивались в APK. Файлы крупные (~10–15 МБ) — держим вне git (.gitignore),
// задача докачивает их при сборке, если отсутствуют. Идемпотентно.
// Аудит 2026-07-02 (supply chain): sha256 зафиксированы (trust-on-first-use от
// tessdata_fast@main 2026-07-01) — подмена файла на GitHub/в пути валит сборку.
val tessLangs = mapOf(
  "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
  "rus" to "e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225",
  "ell" to "4fba8a0b461038d51f1c20d043d4f2ac38c4e778f1b90830847f7bd8fa3ba726",
)
fun sha256Of(f: File): String =
  MessageDigest.getInstance("SHA-256").digest(f.readBytes())
    .joinToString("") { b -> "%02x".format(b) }
val downloadTessData = tasks.register("downloadTessData") {
  val outDir = layout.projectDirectory.dir("src/main/assets/tessdata").asFile
  outputs.dir(outDir)
  doLast {
    outDir.mkdirs()
    tessLangs.forEach { (lang, expectedSha) ->
      val out = File(outDir, "$lang.traineddata")
      if (!out.exists() || out.length() == 0L) {
        val url = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/$lang.traineddata"
        logger.lifecycle("Tesseract: скачиваю $lang.traineddata …")
        uri(url).toURL().openStream().use { input ->
          out.outputStream().use { output -> input.copyTo(output) }
        }
      }
      val actual = sha256Of(out)
      if (actual != expectedSha) {
        out.delete()
        throw GradleException(
          "tessdata: sha256 не совпал для $lang.traineddata (получен $actual). " +
          "Файл удалён; проверь источник или обнови хэш осознанно."
        )
      }
    }
  }
}
// Данные должны существовать до слияния assets (и до preBuild как страховка).
tasks.named("preBuild") { dependsOn(downloadTessData) }
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }
  .configureEach { dependsOn(downloadTessData) }

// ── Алиасы для кнопки Build в Android Studio/IDEA ────────────────────────────
// IDE при «Build/Rebuild Project» иногда шлёт JPS-задачи unitTestClasses/
// androidTestClasses, которых в Android-модуле НЕТ → «Cannot locate tasks that
// match ':app:unitTestClasses'» и сборка падает ещё до конфигурации кода.
// Регистрируем алиасы на реальные AGP-задачи компиляции тестов — кнопка IDE
// работает; на CLI-сборки (assembleDebug) это не влияет.
tasks.register("unitTestClasses") { dependsOn("compileDebugUnitTestKotlin") }
tasks.register("androidTestClasses") { dependsOn("compileDebugAndroidTestKotlin") }
