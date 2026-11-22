package com.dstu.architecture.analyzer.scan;

import com.dstu.architecture.analyzer.model.ExceptionEntry;
import com.dstu.architecture.analyzer.model.ModuleScan;
import com.dstu.architecture.analyzer.model.SourceRoot;
import com.dstu.architecture.analyzer.model.Violation;
import com.dstu.architecture.analyzer.psi.KotlinPsi;
import com.dstu.architecture.analyzer.psi.PsiFiles;
import com.dstu.architecture.analyzer.report.Report;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class ScanSupport {
    private ScanSupport() {
    }

    static Set<String> exceptionPaths(List<ExceptionEntry> entries) {
        Set<String> paths = new HashSet<>();
        if (entries != null) {
            for (ExceptionEntry entry : entries) {
                if (entry != null && entry.path != null) {
                    paths.add(entry.path.replace('\\', '/'));
                }
            }
        }
        return paths;
    }

    static List<ScannedFile> readFiles(Report report, KotlinPsi psi, List<ModuleScan> modules,
                                       List<String> sourceSets, Set<String> exceptions,
                                       String mode, Path root) throws IOException {
        ReadContext context = new ReadContext(report, psi, sourceSets, exceptions, mode, root);
        for (ModuleScan module : modules) {
            report.modulesScanned++;
            readModule(context, module);
        }
        return context.files;
    }

    private static void readModule(ReadContext context, ModuleScan module) throws IOException {
        if (module.sources == null) {
            return;
        }
        for (SourceRoot sourceRoot : module.sources) {
            readSourceRoot(context, module, sourceRoot);
        }
    }

    private static void readSourceRoot(
        ReadContext context,
        ModuleScan module,
        SourceRoot sourceRoot
    ) throws IOException {
        if (sourceRoot == null || sourceRoot.dir == null
                || !context.sourceSets.contains(sourceRoot.sourceSet)) {
            return;
        }
        for (Path file : PsiFiles.kotlinFiles(Path.of(sourceRoot.dir))) {
            readFile(context, module, file);
        }
    }

    private static void readFile(ReadContext context, ModuleScan module, Path file)
            throws IOException {
        String absolute = file.toAbsolutePath().normalize().toString();
        if (!context.seen.add(absolute)) {
            return;
        }
        String relative = relativePath(context.root, file);
        if (context.exceptions.contains(relative)) {
            return;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        KtFile ktFile = context.psi.parse(file.getFileName().toString(), text);
        reportParseError(context, module, relative, text, ktFile);
        context.files.add(new ScannedFile(module, file, relative, text, ktFile));
        context.report.filesScanned++;
    }

    private static void reportParseError(
        ReadContext context,
        ModuleScan module,
        String relative,
        String text,
        KtFile ktFile
    ) {
        Collection<PsiErrorElement> errors = PsiTreeUtil.collectElementsOfType(
                ktFile, PsiErrorElement.class);
        if (errors.isEmpty()) {
            return;
        }
        PsiErrorElement error = errors.iterator().next();
        context.report.violations.add(Violation.create(
                "ARCH_PARSE_ERROR",
                context.mode,
                module.path,
                relative,
                lineOf(text, error.getTextOffset()),
                null,
                "Kotlin PSI parse error: " + error.getErrorDescription()));
    }

    private static final class ReadContext {
        private final Report report;
        private final KotlinPsi psi;
        private final Set<String> sourceSets;
        private final Set<String> exceptions;
        private final String mode;
        private final Path root;
        private final Set<String> seen = new HashSet<>();
        private final List<ScannedFile> files = new ArrayList<>();

        private ReadContext(Report report, KotlinPsi psi, List<String> sourceSets,
                            Set<String> exceptions, String mode, Path root) {
            this.report = report;
            this.psi = psi;
            this.sourceSets = new HashSet<>(sourceSets);
            this.exceptions = exceptions;
            this.mode = mode;
            this.root = root;
        }
    }

    static String relativePath(Path root, Path file) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (normalizedFile.startsWith(normalizedRoot)) {
            return normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/');
        }
        return normalizedFile.toString().replace('\\', '/');
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        int limit = Math.max(0, Math.min(offset, text.length()));
        for (int index = 0; index < limit; index++) {
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    static boolean roleIn(String role, List<String> roles) {
        return roles != null && roles.contains(role);
    }

    static boolean startsWithAny(String value, List<String> prefixes) {
        if (value == null || prefixes == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
