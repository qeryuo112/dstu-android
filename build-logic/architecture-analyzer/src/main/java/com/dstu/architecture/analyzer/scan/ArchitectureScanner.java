package com.dstu.architecture.analyzer.scan;

import com.dstu.architecture.analyzer.model.ScanManifest;
import com.dstu.architecture.analyzer.model.SourceRules;
import com.dstu.architecture.analyzer.model.Violation;
import com.dstu.architecture.analyzer.psi.KotlinPsi;
import com.dstu.architecture.analyzer.report.Report;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtPackageDirective;
import org.jetbrains.kotlin.psi.KtUserType;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Syntax/structure-aware Kotlin PSI checks.
 *
 * <p>This scanner does not perform type resolution or classpath linking. It
 * reads parsed imports, qualified references and reference expressions, then
 * applies module-role rules. The real Gradle graph independently constrains
 * declared project dependencies, including explicitly registered cross-domain
 * APIs.</p>
 */
public final class ArchitectureScanner {
    private static final String MODE = "architecture";

    private final ScanManifest manifest;
    private final Report report;
    private final Set<String> reported = new HashSet<>();
    private final ReferenceRuleChecker references;

    public ArchitectureScanner(ScanManifest manifest, Report report) {
        this.manifest = manifest;
        this.report = report;
        this.references = new ReferenceRuleChecker(manifest, report, reported);
    }

    public void scan() throws Exception {
        Path root = Path.of(manifest.root);
        Set<String> exceptions = ScanSupport.exceptionPaths(manifest.architectureExceptions);
        List<String> sourceSets = manifest.architectureSourceSets;
        if (sourceSets == null || sourceSets.isEmpty()) {
            sourceSets = List.of("main");
        }
        try (KotlinPsi psi = new KotlinPsi()) {
            List<ScannedFile> files = ScanSupport.readFiles(
                    report, psi, manifest.modules, sourceSets, exceptions, MODE, root);
            for (ScannedFile file : files) {
                checkImports(file);
                checkQualifiedReferences(file);
                checkGlobalLookup(file);
            }
        }
    }

    private void checkImports(ScannedFile file) {
        for (KtImportDirective directive : file.ktFile.getImportDirectives()) {
            FqName importedName = directive.getImportedFqName();
            if (importedName != null) {
                references.check(
                        file,
                        importedName.asString(),
                        directive.getTextOffset(),
                        "IMPORT"
                );
            }
        }
    }

    private void checkQualifiedReferences(ScannedFile file) {
        for (KtDotQualifiedExpression expression : PsiTreeUtil.collectElementsOfType(
                file.ktFile, KtDotQualifiedExpression.class)) {
            if (expression.getParent() instanceof KtDotQualifiedExpression
                    || isDeclarationQualifier(expression)) {
                continue;
            }
            String reference = normalize(expression.getText());
            if (reference.contains(".")) {
                references.check(file, reference, expression.getTextOffset(), "REFERENCE");
            }
        }
        for (KtUserType type : PsiTreeUtil.collectElementsOfType(file.ktFile, KtUserType.class)) {
            if (isDeclarationQualifier(type)) {
                continue;
            }
            String reference = normalize(type.getText());
            if (reference.contains(".")) {
                references.check(file, reference, type.getTextOffset(), "REFERENCE");
            }
        }
    }

    private void checkGlobalLookup(ScannedFile file) {
        SourceRules rules = globalRules(file);
        if (rules == null) {
            return;
        }
        checkGlobalImports(file, rules);
        checkGlobalReferences(file, rules);
    }

    private SourceRules globalRules(ScannedFile file) {
        SourceRules rules = manifest.sourceRules;
        if (rules == null || rules.globalLookupSymbols == null
                || rules.globalLookupSymbols.isEmpty()) {
            return null;
        }
        if (ScanSupport.roleIn(file.module.role, rules.globalLookupAllowedRoles)
                || (rules.globalLookupAllowedFiles != null
                && rules.globalLookupAllowedFiles.contains(file.relativePath))) {
            return null;
        }
        return rules;
    }

    private void checkGlobalImports(ScannedFile file, SourceRules rules) {
        for (KtImportDirective directive : file.ktFile.getImportDirectives()) {
            FqName importedName = directive.getImportedFqName();
            if (importedName == null) {
                continue;
            }
            String simpleName = importedName.shortName().asString();
            if (rules.globalLookupSymbols.contains(simpleName)) {
                addGlobal(file, directive.getTextOffset(), simpleName,
                        "imports global graph/service-locator symbol outside allowed composition roots");
            }
        }
    }

    private void checkGlobalReferences(ScannedFile file, SourceRules rules) {
        for (KtNameReferenceExpression reference
                : PsiTreeUtil.collectElementsOfType(file.ktFile, KtNameReferenceExpression.class)) {
            String name = reference.getReferencedName();
            if (!rules.globalLookupSymbols.contains(name) || isInsideImport(reference)) {
                continue;
            }
            addGlobal(file, reference.getTextOffset(), name,
                    "references global graph/service-locator symbol outside allowed composition roots");
        }
    }

    private boolean isInsideImport(PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, KtImportDirective.class) != null;
    }

    private boolean isDeclarationQualifier(PsiElement element) {
        return PsiTreeUtil.getParentOfType(element, KtPackageDirective.class) != null
                || PsiTreeUtil.getParentOfType(element, KtImportDirective.class) != null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private void addGlobal(ScannedFile file, int offset, String symbol, String message) {
        String key = "ARCH_GLOBAL_GRAPH_LOOKUP:" + file.relativePath + ":" + offset + ":" + symbol;
        if (!reported.add(key)) {
            return;
        }
        report.violations.add(Violation.create(
                "ARCH_GLOBAL_GRAPH_LOOKUP",
                MODE,
                file.module.path,
                file.relativePath,
                file.lineOf(offset),
                symbol,
                message));
    }
}
