plugins { id("dstu.android.library") }
android { namespace = "com.deepstudent.features.documents.data" }
dependencies {
    implementation(project(":features:documents:api"))
    implementation(project(":engines:pdf"))
    implementation(libs.kotlinx.coroutines.android)
}
