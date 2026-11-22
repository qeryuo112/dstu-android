package com.dstu.architecture.analyzer.model;

import java.util.ArrayList;
import java.util.List;

public final class ScanManifest {
    public int formatVersion;
    public String root;
    public List<ModuleScan> modules = new ArrayList<>();
    public SourceRules sourceRules = new SourceRules();
    public List<ExceptionEntry> architectureExceptions = new ArrayList<>();
    public List<ExceptionEntry> complexityExceptions = new ArrayList<>();
    public Thresholds thresholds = new Thresholds();
    public List<String> architectureSourceSets = new ArrayList<>(List.of("main"));
    public List<String> complexitySourceSets = new ArrayList<>(List.of("main"));
}
