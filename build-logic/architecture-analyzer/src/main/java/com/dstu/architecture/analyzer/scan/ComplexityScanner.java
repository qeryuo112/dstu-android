package com.dstu.architecture.analyzer.scan;

import com.dstu.architecture.analyzer.model.Thresholds;
import com.dstu.architecture.analyzer.model.ScanManifest;
import com.dstu.architecture.analyzer.model.Violation;
import com.dstu.architecture.analyzer.psi.ComplexityMetrics;
import com.dstu.architecture.analyzer.psi.EffectiveLines;
import com.dstu.architecture.analyzer.psi.KotlinPsi;
import com.dstu.architecture.analyzer.report.Report;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * PSI-based effective-line and control-flow metrics. Detekt runs separately;
 * this scanner is the independent structural check required by S1.01.
 */
public final class ComplexityScanner {
    private static final String MODE = "complexity";

    private final ScanManifest manifest;
    private final Report report;
    private final Thresholds thresholds;

    public ComplexityScanner(ScanManifest manifest, Report report) {
        this.manifest = manifest;
        this.report = report;
        this.thresholds = manifest.thresholds == null ? new Thresholds() : manifest.thresholds;
    }

    public void scan() throws Exception {
        Path root = Path.of(manifest.root);
        Set<String> exceptions = ScanSupport.exceptionPaths(manifest.complexityExceptions);
        List<String> sourceSets = manifest.complexitySourceSets;
        if (sourceSets == null || sourceSets.isEmpty()) {
            sourceSets = List.of("main");
        }
        try (KotlinPsi psi = new KotlinPsi()) {
            List<ScannedFile> files = ScanSupport.readFiles(
                    report, psi, manifest.modules, sourceSets, exceptions, MODE, root);
            for (ScannedFile file : files) {
                scanFile(file);
            }
        }
    }

    private void scanFile(ScannedFile file) {
        int fileLines = EffectiveLines.count(file.ktFile);
        if (fileLines > thresholds.fileEffectiveLines) {
            report.violations.add(Violation.create(
                    "COMPLEXITY_FILE_LINES",
                    MODE,
                    file.module.path,
                    file.relativePath,
                    1,
                    file.absolutePath.getFileName().toString(),
                    "file effective lines exceed limit")
                    .withMetric("fileEffectiveLines", fileLines, thresholds.fileEffectiveLines));
        }

        for (KtNamedFunction function : PsiTreeUtil.collectElementsOfType(
                file.ktFile, KtNamedFunction.class)) {
            scanFunction(file, function);
        }
        for (KtClass klass : PsiTreeUtil.collectElementsOfType(file.ktFile, KtClass.class)) {
            scanClass(file, klass);
        }
    }

    private void scanFunction(ScannedFile file, KtNamedFunction function) {
        String name = function.getName() == null ? "<anonymous>" : function.getName();
        int line = file.lineOf(function.getTextOffset());
        int effective = EffectiveLines.count(function);
        if (effective > thresholds.functionEffectiveLines) {
            report.violations.add(Violation.create(
                    "COMPLEXITY_FUNCTION_LINES",
                    MODE,
                    file.module.path,
                    file.relativePath,
                    line,
                    name,
                    "function effective lines exceed limit")
                    .withMetric("functionEffectiveLines", effective, thresholds.functionEffectiveLines));
        }
        int cyclomatic = ComplexityMetrics.cyclomatic(function);
        if (cyclomatic > thresholds.cyclomaticComplexity) {
            report.violations.add(Violation.create(
                    "COMPLEXITY_CYCLOMATIC",
                    MODE,
                    file.module.path,
                    file.relativePath,
                    line,
                    name,
                    "cyclomatic complexity exceeds limit")
                    .withMetric("cyclomaticComplexity", cyclomatic, thresholds.cyclomaticComplexity));
        }
        int nesting = ComplexityMetrics.nesting(function);
        if (nesting > thresholds.nestedBlockDepth) {
            report.violations.add(Violation.create(
                    "COMPLEXITY_NESTING",
                    MODE,
                    file.module.path,
                    file.relativePath,
                    line,
                    name,
                    "nested block depth exceeds limit")
                    .withMetric("nestedBlockDepth", nesting, thresholds.nestedBlockDepth));
        }
    }

    private void scanClass(ScannedFile file, KtClass klass) {
        String name = klass.getName() == null ? "<anonymous>" : klass.getName();
        int line = file.lineOf(klass.getTextOffset());
        int effective = EffectiveLines.count(klass);
        if (effective > thresholds.classEffectiveLines) {
            report.violations.add(Violation.create(
                    "COMPLEXITY_CLASS_LINES",
                    MODE,
                    file.module.path,
                    file.relativePath,
                    line,
                    name,
                    "class effective lines exceed limit")
                    .withMetric("classEffectiveLines", effective, thresholds.classEffectiveLines));
        }
        int parameters = ComplexityMetrics.constructorParameters(klass);
        if (parameters > thresholds.constructorParameters) {
            report.violations.add(Violation.create(
                    "COMPLEXITY_CONSTRUCTOR_PARAMETERS",
                    MODE,
                    file.module.path,
                    file.relativePath,
                    line,
                    name,
                    "constructor parameter count exceeds limit")
                    .withMetric("constructorParameters", parameters, thresholds.constructorParameters));
        }
    }
}
