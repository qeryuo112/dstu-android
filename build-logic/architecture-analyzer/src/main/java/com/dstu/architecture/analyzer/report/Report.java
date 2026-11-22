package com.dstu.architecture.analyzer.report;

import com.dstu.architecture.analyzer.model.Violation;

import java.util.ArrayList;
import java.util.List;

public final class Report {
    public String mode;
    public String root;
    public String manifest;
    public String startedAt;
    public String finishedAt;
    public long durationMillis;
    public int modulesScanned;
    public int filesScanned;
    public String fatal;
    public List<String> exceptions = new ArrayList<>();
    public List<Violation> violations = new ArrayList<>();
}
