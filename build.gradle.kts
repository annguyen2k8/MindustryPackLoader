import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins{
    java
}

sourceSets.main{
    java.setSrcDirs(listOf("src"))
}

sourceSets.test{
    java.setSrcDirs(listOf("test"))
    resources.setSrcDirs(listOf("assets"))
}

// Mindustry version to depend on.
// Valid values:
// - latest: depend on the latest release of mindustry
// - be: depend on the very latest commit of mindustry
// - v<number>: depend on a specific version
val mindustryVersion = "v160"
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val sdkRoot: String? = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: File(System.getProperty("user.home", ""), ".android-sdk").takeIf { it.exists() }?.absolutePath
val projectName = project.name

repositories{
    mavenCentral()

    //Downloads the dependencies JAR file from Mindustry releases; does not use any real repository. Surprisingly, this is the most reliable option.
    ivy{
        url = uri("https://github.com/")
        patternLayout{
            val pattern = when(mindustryVersion){
                "latest" -> "/[organisation]/[module]/releases/[revision]/download/dependencies.jar" //latest stable release
                "be" -> "/[organisation]/[module]/releases/download/master/[revision].jar" //latest commit (BE)
                else -> "/[organisation]/[module]/releases/download/[revision]/dependencies.jar" //specific release
            }
            val path = pattern.substring(0, pattern.lastIndexOf('/'))

            artifact("$path/[classifier].jar")
            artifact(pattern.replaceFirst(Regex("""\.jar$"""), "(-[classifier]).jar"))
        }
        metadataSources{ artifact() }

        content{
            if(mindustryVersion == "be"){
                //BE artifact version is always 'latest'
                includeVersion("Anuken", "MindustryBuilds", "latest")
            }else{
                includeVersion("Anuken", "Mindustry", mindustryVersion)
            }
        }
    }
}

java{
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies{
    compileOnly(if(mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")
    testImplementation(if(mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

fun calculateSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    val bytes = digest.digest()
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append(String.format("%02x", b.toInt() and 0xFF))
    }
    return sb.toString()
}

fun resolveArtifactCopyDir(validate: Boolean = false): File? {
    // 1. Gradle project property "artifactCopyDir"
    val prop = (project.findProperty("artifactCopyDir") as? String)?.trim()
    if (!prop.isNullOrEmpty()) {
        if (prop.equals("false", ignoreCase = true) || prop.equals("none", ignoreCase = true) || prop.equals("off", ignoreCase = true)) {
            return null
        }
        val dir = File(prop)
        if (validate) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw GradleException("Configured artifactCopyDir does not exist and could not be created: ${dir.absolutePath}")
            }
            if (!dir.isDirectory || !dir.canWrite()) {
                throw GradleException("Configured artifactCopyDir is not a writable directory: ${dir.absolutePath}")
            }
        }
        return dir
    }

    // 2. Environment variable "ARTIFACT_COPY_DIR" or "artifactCopyDir"
    val env = (System.getenv("ARTIFACT_COPY_DIR") ?: System.getenv("artifactCopyDir"))?.trim()
    if (!env.isNullOrEmpty()) {
        if (env.equals("false", ignoreCase = true) || env.equals("none", ignoreCase = true) || env.equals("off", ignoreCase = true)) {
            return null
        }
        val dir = File(env)
        if (validate) {
            if (!dir.exists() && !dir.mkdirs()) {
                throw GradleException("Configured ARTIFACT_COPY_DIR does not exist and could not be created: ${dir.absolutePath}")
            }
            if (!dir.isDirectory || !dir.canWrite()) {
                throw GradleException("Configured ARTIFACT_COPY_DIR is not a writable directory: ${dir.absolutePath}")
            }
        }
        return dir
    }

    // 3. Default Termux behavior: only if in Termux and $HOME/storage/shared/Download exists and is writable
    val userHome = System.getProperty("user.home", "")
    val termuxDownload = File(userHome, "storage/shared/Download")
    val isTermux = System.getenv("TERMUX_VERSION") != null || userHome.contains("com.termux")
    if (isTermux && termuxDownload.exists() && termuxDownload.isDirectory && termuxDownload.canWrite()) {
        return termuxDownload
    }

    return null
}

fun copyAndVerifyArtifact(sourceFile: File, destDir: File) {
    if (!sourceFile.exists()) {
        throw GradleException("Artifact file to copy does not exist: ${sourceFile.absolutePath}")
    }
    val destFile = File(destDir, sourceFile.name)
    try {
        Files.copy(
            sourceFile.toPath(),
            destFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (e: Exception) {
        throw GradleException("Failed to copy artifact ${sourceFile.name} to ${destDir.absolutePath}: ${e.message}", e)
    }

    if (!destFile.exists()) {
        throw GradleException("Artifact copy failed: destination file does not exist: ${destFile.absolutePath}")
    }

    if (destFile.length() != sourceFile.length()) {
        throw GradleException("Artifact verification failed: copied file ${destFile.absolutePath} size (${destFile.length()}) does not match source (${sourceFile.length()})")
    }

    val sourceHash = calculateSha256(sourceFile)
    val destHash = calculateSha256(destFile)
    if (sourceHash != destHash) {
        throw GradleException("Artifact checksum verification failed for ${destFile.name}: SHA-256 mismatch (source: $sourceHash, dest: $destHash)")
    }

    logger.lifecycle("[AutoCopy] Successfully copied ${sourceFile.name} -> ${destFile.absolutePath} (${destFile.length()} bytes, SHA-256: $sourceHash)")
}

fun verifyJarLocalization(jarFile: File) {
    if (!jarFile.exists()) {
        throw GradleException("JAR localization verification failed: file does not exist: ${jarFile.absolutePath}")
    }
    ZipFile(jarFile).use { zip ->
        val modJsonEntry = zip.getEntry("mod.json")
        val modHjsonEntry = zip.getEntry("mod.hjson")
        if (modJsonEntry == null && modHjsonEntry == null) {
            throw GradleException("JAR metadata verification failed: Neither 'mod.json' nor 'mod.hjson' found in ${jarFile.name}")
        }
        val metaContent = (modJsonEntry ?: modHjsonEntry)?.let { entry ->
            zip.getInputStream(entry).bufferedReader().readText()
        } ?: ""

        val minVerMatch = Regex("""["']?minGameVersion["']?\s*:\s*["']?([0-9.]+)["']?""").find(metaContent)
        val minVerStr = minVerMatch?.groupValues?.get(1)
        val minMajor = minVerStr?.split(".")?.get(0)?.toIntOrNull() ?: 0
        if (minMajor < 154) {
            throw GradleException("JAR metadata verification failed: minGameVersion ($minVerStr) in ${jarFile.name} is less than 154 (incompatible with Mindustry v8)")
        }

        val defaultBundle = zip.getEntry("bundles/bundle.properties")
        val viBundle = zip.getEntry("bundles/bundle_vi.properties")
        if (defaultBundle == null) {
            throw GradleException("JAR localization verification failed: 'bundles/bundle.properties' is missing in ${jarFile.name}")
        }
        if (viBundle == null) {
            throw GradleException("JAR localization verification failed: 'bundles/bundle_vi.properties' is missing in ${jarFile.name}")
        }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entryName = entries.nextElement().name
            if (entryName.contains("bundles/bundles/")) {
                throw GradleException("JAR localization verification failed: nested 'bundles/bundles/' detected in ${jarFile.name}: $entryName")
            }
        }
    }
    logger.lifecycle("[JAR Verification] ${jarFile.name} verified: bundles correctly packaged at bundles/* (no nested bundles/bundles/).")
}

tasks.jar{
    archiveFileName.set("${projectName}Desktop.jar")

    inputs.property("effectiveArtifactCopyDir", provider {
        resolveArtifactCopyDir(validate = false)?.absolutePath ?: ""
    })

    outputs.upToDateWhen {
        val destDir = resolveArtifactCopyDir(validate = false)
        if (destDir != null) {
            val srcFile = archiveFile.get().asFile
            val destFile = File(destDir, archiveFileName.get())
            if (!srcFile.exists() || !destFile.exists() || destFile.length() != srcFile.length()) {
                false
            } else {
                calculateSha256(srcFile) == calculateSha256(destFile)
            }
        } else {
            true
        }
    }

    from(configurations.runtimeClasspath.map{ files ->
        files.map{ if(it.isDirectory) it else zipTree(it) }
    })

    from(projectDir){
        include("mod.json")
        include("mod.hjson")
        include("icon.png")
    }

    from("assets/"){
        include("**")
    }

    doLast{
        val jarFile = archiveFile.get().asFile
        verifyJarLocalization(jarFile)

        val copyDir = resolveArtifactCopyDir(validate = true)
        if (copyDir != null) {
            copyAndVerifyArtifact(jarFile, copyDir)
        }
    }
}

tasks.test{
    useJUnitPlatform()
}

val verifyJar = tasks.register("verifyJar"){
    dependsOn(tasks.jar)
    description = "Verifies packaging and bundle integrity of the desktop JAR."
    group = "verification"
    doLast{
        verifyJarLocalization(tasks.jar.get().archiveFile.get().asFile)
    }
}

tasks.check{
    dependsOn(verifyJar)
}

val jarAndroid = tasks.register("jarAndroid"){
    dependsOn(tasks.jar)

    doLast{
        val sdk = sdkRoot?.let{ File(it) }
        if(sdk == null || !sdk.exists()){
            throw GradleException("No valid Android SDK found. Ensure that ANDROID_HOME is set to your Android SDK directory.")
        }

        val platformRoot = File(sdk, "platforms").listFiles()
            ?.sortedDescending()
            ?.firstOrNull{ File(it, "android.jar").exists() }
            ?: throw GradleException("No android.jar found. Ensure that you have an Android platform installed.")

        //collect dependencies needed for desugaring
        val classpath = (configurations.compileClasspath.get().files +
                configurations.runtimeClasspath.get().files +
                File(platformRoot, "android.jar"))

        val d8 = if(isWindows) "d8.bat" else "d8"

        val command = buildList{
            add(d8)
            classpath.forEach{ addAll(listOf("--classpath", it.path)) }
            addAll(listOf("--min-api", "21"))
            addAll(listOf("--output", "${projectName}Android.jar"))
            add("${projectName}Desktop.jar")
        }

        //dex and desugar files - this requires d8 in your PATH
        val exitCode = ProcessBuilder(command)
            .directory(layout.buildDirectory.dir("libs").get().asFile)
            .inheritIO()
            .start()
            .waitFor()

        if(exitCode != 0) throw GradleException("d8 failed with exit code $exitCode")
    }
}

tasks.register<Jar>("deploy"){
    dependsOn(jarAndroid, tasks.jar)
    archiveFileName.set("$projectName.jar")

    inputs.property("effectiveArtifactCopyDir", provider {
        resolveArtifactCopyDir(validate = false)?.absolutePath ?: ""
    })

    outputs.upToDateWhen {
        val destDir = resolveArtifactCopyDir(validate = false)
        if (destDir != null) {
            val srcFile = archiveFile.get().asFile
            val destFile = File(destDir, archiveFileName.get())
            if (!srcFile.exists() || !destFile.exists() || destFile.length() != srcFile.length()) {
                false
            } else {
                calculateSha256(srcFile) == calculateSha256(destFile)
            }
        } else {
            true
        }
    }

    val libs = layout.buildDirectory.dir("libs")
    from(provider{
        listOf(
            zipTree(libs.get().file("${projectName}Desktop.jar")),
            zipTree(libs.get().file("${projectName}Android.jar"))
        )
    })

    doLast{
        libs.get().file("${projectName}Android.jar").asFile.delete()

        val deployJar = archiveFile.get().asFile
        verifyJarLocalization(deployJar)

        ZipFile(deployJar).use { zip ->
            val dex = zip.getEntry("classes.dex")
            if (dex == null) {
                throw GradleException("Deploy JAR verification failed: 'classes.dex' is missing in ${deployJar.name}. Android runtime requires classes.dex.")
            }
        }
        logger.lifecycle("[Deploy Verification] ${deployJar.name} verified: classes.dex is present for Android compatibility.")

        val copyDir = resolveArtifactCopyDir(validate = true)
        if (copyDir != null) {
            copyAndVerifyArtifact(deployJar, copyDir)
        }
    }
}