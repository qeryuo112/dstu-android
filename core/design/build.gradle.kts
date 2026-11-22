plugins {
    id("dstu.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.deepstudent.design"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets {
        getByName("main").java.srcDir(layout.buildDirectory.dir("generated/s1Design/kotlin"))
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/s1Design/mainAssets"))
        getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/s1Design/androidTestAssets"))
    }
}

val generateDesignTokens by tasks.registering(Exec::class) {
    val root = rootProject.projectDir
    val output = layout.buildDirectory.dir("generated/s1Design")
    inputs.files(root.resolve("config/design/tokens.baseline.json"), root.resolve("config/design/brand.baseline.json"))
    inputs.file(root.resolve("config/stages/s1-design-source.json"))
    inputs.files(root.resolve("../dstu-node/LICENSE"), root.resolve("../dstu-node/node_modules/@phosphor-icons/react/LICENSE"))
    inputs.files(fileTree(root.resolve("tools/s1")) { include("design-*.mjs") })
    inputs.files(
        root.resolve("../dstu-node/src/components/icons/StudySidebarIcons.tsx"),
        root.resolve("../dstu-node/node_modules/@phosphor-icons/react/dist/defs/Database.es.js"),
    )
    outputs.dir(output)
    commandLine("node", root.resolve("tools/s1/design-generate.mjs"), output.get().asFile)
}

tasks.named("preBuild") { dependsOn(generateDesignTokens) }

tasks.register<Exec>("verifyDesignTokens") {
    commandLine("node", rootProject.file("tools/s1/design-verify.mjs"))
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
}
