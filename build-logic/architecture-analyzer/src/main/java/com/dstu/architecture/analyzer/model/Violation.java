package com.dstu.architecture.analyzer.model;

public final class Violation {
    public String code;
    public String mode;
    public String project;
    public String file;
    public int line;
    public String symbol;
    public String message;
    public String metric;
    public Integer actual;
    public Integer threshold;

    public static Violation create(String code, String mode, String project, String file,
                                   int line, String symbol, String message) {
        Violation violation = new Violation();
        violation.code = code;
        violation.mode = mode;
        violation.project = project;
        violation.file = file;
        violation.line = line;
        violation.symbol = symbol;
        violation.message = message;
        return violation;
    }

    public Violation withMetric(String metric, int actual, int threshold) {
        this.metric = metric;
        this.actual = actual;
        this.threshold = threshold;
        return this;
    }
}
