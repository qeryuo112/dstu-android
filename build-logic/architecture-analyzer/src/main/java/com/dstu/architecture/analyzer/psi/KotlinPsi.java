package com.dstu.architecture.analyzer.psi;

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtPsiFactory;

/**
 * Owns the single Kotlin PSI environment used for one analyzer process.
 */
public final class KotlinPsi implements AutoCloseable {
    private final Disposable disposable;
    private final KtPsiFactory factory;

    public KotlinPsi() {
        this.disposable = Disposer.newDisposable("dstu-architecture-analyzer");
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.put(CommonConfigurationKeys.MODULE_NAME, "dstu-architecture-analyzer");
        KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES);
        this.factory = new KtPsiFactory(environment.getProject(), false);
    }

    public KtFile parse(String fileName, String source) {
        return factory.createFile(fileName, source);
    }

    @Override
    public void close() {
        Disposer.dispose(disposable);
    }
}
