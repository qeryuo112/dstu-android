package com.dstu.architecture.analyzer.scan;

import com.dstu.architecture.analyzer.model.ModuleScan;
import org.jetbrains.kotlin.psi.KtFile;

import java.nio.file.Path;

final class ScannedFile {
    final ModuleScan module;
    final Path absolutePath;
    final String relativePath;
    final String text;
    final KtFile ktFile;

    ScannedFile(ModuleScan module, Path absolutePath, String relativePath, String text, KtFile ktFile) {
        this.module = module;
        this.absolutePath = absolutePath;
        this.relativePath = relativePath;
        this.text = text;
        this.ktFile = ktFile;
    }

    int lineOf(int offset) {
        int line = 1;
        int limit = Math.max(0, Math.min(offset, text.length()));
        for (int index = 0; index < limit; index++) {
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }
}
