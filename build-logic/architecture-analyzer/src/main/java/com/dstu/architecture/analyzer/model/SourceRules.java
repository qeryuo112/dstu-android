package com.dstu.architecture.analyzer.model;

import java.util.ArrayList;
import java.util.List;

public final class SourceRules {
    public List<String> domainPureRoles = new ArrayList<>();
    public List<String> domainForbiddenImportPrefixes = new ArrayList<>();
    public List<String> uiStrictRoles = new ArrayList<>();
    public List<String> uiForbiddenImportPrefixes = new ArrayList<>();
    public List<String> uiAllowedImportPrefixes = new ArrayList<>();
    public List<String> crossDomainInternalExemptRoles = new ArrayList<>();
    public List<String> globalLookupSymbols = new ArrayList<>();
    public List<String> globalLookupAllowedRoles = new ArrayList<>();
    public List<String> globalLookupAllowedFiles = new ArrayList<>();
    public List<String> coreNoPlatformRoles = new ArrayList<>();
    public List<String> coreForbiddenImportPrefixes = new ArrayList<>();
    public List<String> domainPackageSegments = new ArrayList<>();
    public List<String> applicationPackageSegments = new ArrayList<>();
}
