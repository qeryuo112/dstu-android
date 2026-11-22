package com.dstu.architecture.analyzer.psi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class PsiFiles {
    private PsiFiles() {
    }

    public static List<Path> kotlinFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".kt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        }
        return files;
    }
}
