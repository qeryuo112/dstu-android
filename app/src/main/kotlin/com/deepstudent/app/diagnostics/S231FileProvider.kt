package com.deepstudent.app.diagnostics

import androidx.core.content.FileProvider

/** A distinct manifest component, so it cannot merge with the debug document provider. */
internal class S231FileProvider : FileProvider()
