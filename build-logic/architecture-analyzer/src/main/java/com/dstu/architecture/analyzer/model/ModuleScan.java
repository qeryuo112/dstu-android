package com.dstu.architecture.analyzer.model;

import java.util.ArrayList;
import java.util.List;

public final class ModuleScan {
    public String path;
    public String role;
    public String layer;
    public String domain;
    public String capability;
    public List<SourceRoot> sources = new ArrayList<>();
}
