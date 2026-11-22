package com.deepstudent.platform.storage

import java.io.File

class DataSpaceJvmTest : DataSpaceSharedTests() {
    override fun temporaryParent(): File = File(System.getProperty("java.io.tmpdir"))
}
