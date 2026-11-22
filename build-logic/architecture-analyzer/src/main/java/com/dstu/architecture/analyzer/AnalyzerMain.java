package com.dstu.architecture.analyzer;

import com.dstu.architecture.analyzer.model.ExceptionEntry;
import com.dstu.architecture.analyzer.model.ScanManifest;
import com.dstu.architecture.analyzer.model.Violation;
import com.dstu.architecture.analyzer.report.Report;
import com.dstu.architecture.analyzer.scan.ArchitectureScanner;
import com.dstu.architecture.analyzer.scan.ComplexityScanner;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone Kotlin-PSI architecture analyzer.
 *
 * <p>This process is launched by the {@code dstu.architecture} Gradle plugin in
 * its own JVM so that the Kotlin compiler used for PSI parsing cannot clash
 * with Gradle's embedded Kotlin runtime.</p>
 */
public final class AnalyzerMain {
    private static final String MODE_ARCHITECTURE = "architecture";
    private static final String MODE_COMPLEXITY = "complexity";

    private AnalyzerMain() {
    }

    public static void main(String[] args) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException error) {
            System.err.println("DSTU_ARCH_FATAL code=ANALYZER_ARGUMENT_ERROR message=" + oneLine(error.getMessage()));
            System.exit(2);
            return;
        }

        Report report = new Report();
        report.mode = arguments.mode;
        report.startedAt = Instant.now().toString();
        report.manifest = arguments.manifest.toString();
        long started = System.nanoTime();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        try {
            String manifestText = Files.readString(arguments.manifest, StandardCharsets.UTF_8);
            ScanManifest manifest = gson.fromJson(manifestText, ScanManifest.class);
            if (manifest == null) {
                throw new IllegalStateException("manifest is empty: " + arguments.manifest);
            }
            report.root = manifest.root;
            report.exceptions = collectExceptionPaths(
                    MODE_ARCHITECTURE.equals(arguments.mode)
                            ? manifest.architectureExceptions
                            : manifest.complexityExceptions);

            if (MODE_ARCHITECTURE.equals(arguments.mode)) {
                new ArchitectureScanner(manifest, report).scan();
            } else if (MODE_COMPLEXITY.equals(arguments.mode)) {
                new ComplexityScanner(manifest, report).scan();
            } else {
                throw new IllegalArgumentException("unknown mode: " + arguments.mode);
            }
            report.fatal = null;
        } catch (Throwable error) {
            report.fatal = error.getClass().getName() + ": " + oneLine(error.getMessage());
            error.printStackTrace(System.err);
        }

        report.durationMillis = (System.nanoTime() - started) / 1_000_000L;
        report.finishedAt = Instant.now().toString();
        writeReport(gson, arguments.report, report);
        printSummary(report, arguments.report);

        if (report.fatal != null) {
            System.exit(2);
        }
        if (!report.violations.isEmpty()) {
            System.exit(1);
        }
    }

    private static List<String> collectExceptionPaths(List<ExceptionEntry> entries) {
        List<String> paths = new ArrayList<>();
        if (entries != null) {
            for (ExceptionEntry entry : entries) {
                if (entry != null && entry.path != null) {
                    paths.add(entry.path);
                }
            }
        }
        return paths;
    }

    private static void writeReport(Gson gson, Path reportPath, Report report) {
        try {
            Path parent = reportPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(reportPath, gson.toJson(report), StandardCharsets.UTF_8);
        } catch (IOException error) {
            System.err.println("DSTU_ARCH_FATAL code=REPORT_WRITE_FAILED path=" + reportPath
                    + " message=" + oneLine(error.getMessage()));
        }
    }

    private static void printSummary(Report report, Path reportPath) {
        System.out.println("DSTU_ARCH mode=" + report.mode
                + " modules=" + report.modulesScanned
                + " files=" + report.filesScanned
                + " exceptions=" + report.exceptions.size()
                + " violations=" + report.violations.size()
                + " duration_ms=" + report.durationMillis
                + " report=" + reportPath);
        for (Violation violation : report.violations) {
            System.out.println("DSTU_ARCH_VIOLATION"
                    + " code=" + violation.code
                    + " mode=" + violation.mode
                    + " project=" + value(violation.project)
                    + " file=" + value(violation.file)
                    + " line=" + violation.line
                    + " symbol=" + value(violation.symbol)
                    + " metric=" + value(violation.metric)
                    + " actual=" + value(violation.actual)
                    + " threshold=" + value(violation.threshold)
                    + " message=" + value(violation.message));
        }
        if (report.fatal != null) {
            System.out.println("DSTU_ARCH_FATAL code=ANALYZER_FAILED message=" + value(report.fatal));
        }
    }

    private static String value(Object value) {
        if (value == null) {
            return "-";
        }
        return oneLine(String.valueOf(value)).replace(' ', '_');
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static final class Arguments {
        private final String mode;
        private final Path manifest;
        private final Path report;

        private Arguments(String mode, Path manifest, Path report) {
            this.mode = mode;
            this.manifest = manifest;
            this.report = report;
        }

        private static Arguments parse(String[] args) {
            String mode = null;
            Path manifest = null;
            Path report = null;
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                switch (argument) {
                    case "--mode":
                        mode = requireValue(args, ++index, argument);
                        break;
                    case "--manifest":
                        manifest = Paths.get(requireValue(args, ++index, argument));
                        break;
                    case "--report":
                        report = Paths.get(requireValue(args, ++index, argument));
                        break;
                    default:
                        throw new IllegalArgumentException("unknown argument: " + argument);
                }
            }
            if (mode == null || manifest == null || report == null) {
                throw new IllegalArgumentException("--mode, --manifest and --report are required");
            }
            if (!MODE_ARCHITECTURE.equals(mode) && !MODE_COMPLEXITY.equals(mode)) {
                throw new IllegalArgumentException("unsupported mode: " + mode);
            }
            return new Arguments(mode, manifest, report);
        }

        private static String requireValue(String[] args, int index, String argument) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + argument);
            }
            return args[index];
        }
    }
}
