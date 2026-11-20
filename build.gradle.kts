// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

abstract class EnsureDebugKeystoreTask : DefaultTask() {
  @get:Internal
  abstract val rootDirProperty: DirectoryProperty

  @TaskAction
  fun ensureKeystore() {
    val rootDirFile = rootDirProperty.get().asFile
    val keystoreFile = File(rootDirFile, "debug.keystore")
    if (!keystoreFile.exists()) {
      val base64File = File(rootDirFile, "debug.keystore.base64")
      if (base64File.exists()) {
        val encoded = base64File.readText().replace("\\s".toRegex(), "")
        keystoreFile.writeBytes(java.util.Base64.getDecoder().decode(encoded))
      } else {
        ProcessBuilder(
          "keytool", "-genkeypair", "-v",
          "-keystore", keystoreFile.absolutePath,
          "-storepass", "android",
          "-alias", "androiddebugkey",
          "-keypass", "android",
          "-keyalg", "RSA",
          "-keysize", "2048",
          "-validity", "10000",
          "-dname", "CN=Android Debug,O=Android,C=US"
        ).inheritIO().start().waitFor()
      }
    }
  }
}

val ensureDebugKeystore = tasks.register<EnsureDebugKeystoreTask>("ensureDebugKeystore") {
  rootDirProperty.set(layout.projectDirectory)
}

subprojects {
  tasks.matching { it.name == "validateSigningDebug" }.configureEach {
    dependsOn(ensureDebugKeystore)
  }
}
