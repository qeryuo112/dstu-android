plugins { id("dstu.android.library") }

android {
    namespace = "com.deepstudent.features.content.data"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/s1Content/assets"))
}

val contentAssets by tasks.registering(Exec::class) {
    val root = rootProject.projectDir
    val output = layout.buildDirectory.dir("generated/s1Content/assets")
    inputs.file(root.resolve("config/stages/s1-content-sources.json"))
    inputs.file(root.resolve("config/source-lock.json"))
    inputs.file(root.resolve("tools/s1/content-assets.mjs"))
    inputs.dir(root.resolve("../dstu-node/node_modules/katex"))
    inputs.dir(root.resolve("../dstu-node/node_modules/mermaid"))
    outputs.dir(output)
    outputs.upToDateWhen { false }
    commandLine("node", root.resolve("tools/s1/content-assets.mjs"), output.get().asFile)
}
tasks.named("preBuild") { dependsOn(contentAssets) }

dependencies {
    api(project(":features:content:api"))
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(project(":features:content:backend"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
