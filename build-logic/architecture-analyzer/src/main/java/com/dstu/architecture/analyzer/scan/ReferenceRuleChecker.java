package com.dstu.architecture.analyzer.scan;

import com.dstu.architecture.analyzer.model.ScanManifest;
import com.dstu.architecture.analyzer.model.SourceRules;
import com.dstu.architecture.analyzer.model.Violation;
import com.dstu.architecture.analyzer.report.Report;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies role-aware import/reference rules extracted from ArchitectureScanner
 * to keep each class below the fixed class effective-line limit.
 */
final class ReferenceRuleChecker {
    private static final Pattern FEATURE_INTERNAL = Pattern.compile(
            "^com\\.deepstudent\\.features\\.([A-Za-z0-9_]+)\\.(backend|data|ui|internal)(?:\\.|$)");
    private static final Pattern SERVICE_INTERNAL = Pattern.compile(
            "^com\\.deepstudent\\.services\\.([A-Za-z0-9_]+)\\.(backend|data|internal)(?:\\.|$)");
    private static final Pattern FEATURE_API = Pattern.compile(
            "^com\\.deepstudent\\.features\\.([A-Za-z0-9_]+)\\.api(?:\\.|$)");
    private static final Pattern SERVICE_API = Pattern.compile(
            "^com\\.deepstudent\\.services\\.([A-Za-z0-9_]+)\\.api(?:\\.|$)");

    private final ScanManifest manifest;
    private final Report report;
    private final Set<String> reported;

    ReferenceRuleChecker(ScanManifest manifest, Report report, Set<String> reported) {
        this.manifest = manifest;
        this.report = report;
        this.reported = reported;
    }

    void check(ScannedFile file, String reference, int offset, String suffix) {
        SourceRules rules = rulesOrDefault();
        int line = file.lineOf(offset);
        checkDomainPure(file, reference, line, suffix, rules);
        checkUi(file, reference, line, suffix, rules);
        checkCore(file, reference, line, suffix, rules);
        checkDomainApplication(file, reference, line, suffix, rules);
        checkCrossDomainReference(file, reference, line, suffix, rules);
    }

    private SourceRules rulesOrDefault() {
        return manifest.sourceRules == null ? new SourceRules() : manifest.sourceRules;
    }

    private void checkDomainPure(
        ScannedFile file,
        String reference,
        int line,
        String suffix,
        SourceRules rules
    ) {
        if (!ScanSupport.roleIn(file.module.role, rules.domainPureRoles)
                || !ScanSupport.startsWithAny(reference, rules.domainForbiddenImportPrefixes)) {
            return;
        }
        add(file, "ARCH_DOMAIN_ANDROID_" + suffix, line, reference,
                "pure Kotlin role " + file.module.role
                        + " references Android/Compose/SQL/HTTP type without an allowed port");
    }

    private void checkUi(
        ScannedFile file,
        String reference,
        int line,
        String suffix,
        SourceRules rules
    ) {
        if (!ScanSupport.roleIn(file.module.role, rules.uiStrictRoles)
                || !ScanSupport.startsWithAny(reference, rules.uiForbiddenImportPrefixes)
                || isAllowedUiReference(reference, file.module.domain)) {
            return;
        }
        add(file, "ARCH_UI_IMPLEMENTATION_" + suffix, line, reference,
                "UI role " + file.module.role
                        + " references backend/data/platform/app implementation");
    }

    private void checkCore(
        ScannedFile file,
        String reference,
        int line,
        String suffix,
        SourceRules rules
    ) {
        if (!ScanSupport.roleIn(file.module.role, rules.coreNoPlatformRoles)
                || !ScanSupport.startsWithAny(reference, rules.coreForbiddenImportPrefixes)) {
            return;
        }
        add(file, "ARCH_CORE_PLATFORM_" + suffix, line, reference,
                "core role " + file.module.role + " references platform/engine business code");
    }

    private void checkDomainApplication(
        ScannedFile file,
        String reference,
        int line,
        String suffix,
        SourceRules rules
    ) {
        if (!hasAnySegment(file.ktFile.getPackageFqName().asString(), rules.domainPackageSegments)
                || !hasAnySegment(reference, rules.applicationPackageSegments)) {
            return;
        }
        add(file, "ARCH_DOMAIN_APPLICATION_" + suffix, line, reference,
                "domain package references application package");
    }

    private void checkCrossDomainReference(ScannedFile file, String reference, int line,
                                           String suffix, SourceRules rules) {
        if (ScanSupport.roleIn(file.module.role, rules.crossDomainInternalExemptRoles)) {
            return;
        }
        if (checkFeatureReference(file, reference, line, suffix)) {
            return;
        }
        checkServiceReference(file, reference, line, suffix);
    }

    private boolean checkFeatureReference(
        ScannedFile file,
        String reference,
        int line,
        String suffix
    ) {
        Matcher feature = FEATURE_INTERNAL.matcher(reference);
        if (!feature.find()) {
            return false;
        }
        String targetDomain = feature.group(1);
        String layer = feature.group(2);
        if (targetDomain.equals(file.module.domain) && file.module.role.startsWith("feature-")) {
            if (isFeatureRoleBoundaryViolation(file.module.role, layer)) {
                add(file, "ARCH_CROSS_DOMAIN_INTERNAL_" + suffix, line, reference,
                        "feature role " + file.module.role + " references its own "
                                + layer + " package");
            }
            return true;
        }
        add(file, "ARCH_CROSS_DOMAIN_INTERNAL_" + suffix, line, reference,
                "module " + file.module.path + " references feature " + targetDomain
                        + " internal layer " + layer);
        return true;
    }

    private void checkServiceReference(
        ScannedFile file,
        String reference,
        int line,
        String suffix
    ) {
        Matcher service = SERVICE_INTERNAL.matcher(reference);
        if (!service.find()) {
            return;
        }
        String targetCapability = service.group(1);
        String layer = service.group(2);
        if (targetCapability.equals(file.module.capability)
                && file.module.role.startsWith("service-")) {
            if (isServiceRoleBoundaryViolation(file.module.role, layer)) {
                add(file, "ARCH_CROSS_DOMAIN_INTERNAL_" + suffix, line, reference,
                        "service role " + file.module.role
                                + " references its own " + layer + " package");
            }
            return;
        }
        add(file, "ARCH_CROSS_DOMAIN_INTERNAL_" + suffix, line, reference,
                "module " + file.module.path + " references service " + targetCapability
                        + " internal layer " + layer);
    }

    private boolean isFeatureRoleBoundaryViolation(String role, String layer) {
        if ("feature-api".equals(role)) {
            return true;
        }
        if ("feature-backend".equals(role)) {
            return "data".equals(layer) || "ui".equals(layer);
        }
        if ("feature-data".equals(role)) {
            return "ui".equals(layer);
        }
        return false;
    }

    private boolean isServiceRoleBoundaryViolation(String role, String layer) {
        return "service-api".equals(role) && !"api".equals(layer)
                || "service-backend".equals(role) && "data".equals(layer);
    }

    private boolean isAllowedUiReference(String reference, String domain) {
        if (reference.startsWith("com.deepstudent.core.")) {
            return true;
        }
        if (FEATURE_API.matcher(reference).find() || SERVICE_API.matcher(reference).find()) {
            return true;
        }
        return domain != null
                && (reference.equals("com.deepstudent.features." + domain + ".ui")
                || reference.startsWith("com.deepstudent.features." + domain + ".ui."));
    }

    private boolean hasAnySegment(String value, List<String> segments) {
        if (value == null || segments == null || segments.isEmpty()) {
            return false;
        }
        for (String part : value.split("\\.")) {
            if (segments.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private void add(ScannedFile file, String code, int line, String symbol, String message) {
        String key = code + ":" + file.relativePath + ":" + line + ":" + symbol;
        if (!reported.add(key)) {
            return;
        }
        report.violations.add(Violation.create(
                code,
                "architecture",
                file.module.path,
                file.relativePath,
                line,
                symbol,
                message));
    }
}
