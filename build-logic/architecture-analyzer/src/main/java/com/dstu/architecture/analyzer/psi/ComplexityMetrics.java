package com.dstu.architecture.analyzer.psi;

import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtCatchClause;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtContainerNodeForControlStructureBody;
import org.jetbrains.kotlin.psi.KtIfExpression;
import org.jetbrains.kotlin.psi.KtLoopExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtSecondaryConstructor;
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid;
import org.jetbrains.kotlin.psi.KtTryExpression;
import org.jetbrains.kotlin.psi.KtWhenEntry;
import org.jetbrains.kotlin.psi.KtWhenExpression;

public final class ComplexityMetrics {
    private ComplexityMetrics() {
    }

    public static int cyclomatic(KtNamedFunction function) {
        CyclomaticVisitor visitor = new CyclomaticVisitor(function);
        function.accept(visitor);
        return visitor.complexity;
    }

    public static int nesting(KtNamedFunction function) {
        NestingVisitor visitor = new NestingVisitor(function);
        function.accept(visitor);
        return visitor.maxDepth;
    }

    public static int constructorParameters(KtClass klass) {
        int count = klass.getPrimaryConstructorParameters().size();
        for (KtSecondaryConstructor constructor : klass.getSecondaryConstructors()) {
            count = Math.max(count, constructor.getValueParameters().size());
        }
        return count;
    }

    private static final class CyclomaticVisitor extends KtTreeVisitorVoid {
        private final KtNamedFunction root;
        private int complexity = 1;

        private CyclomaticVisitor(KtNamedFunction root) {
            this.root = root;
        }

        @Override
        public void visitNamedFunction(KtNamedFunction function) {
            if (function == root) {
                super.visitNamedFunction(function);
            }
        }

        @Override
        public void visitClassOrObject(KtClassOrObject classOrObject) {
            // Local classes do not contribute decisions to the enclosing function.
        }

        @Override
        public void visitIfExpression(KtIfExpression expression) {
            complexity++;
            super.visitIfExpression(expression);
        }

        @Override
        public void visitWhenEntry(KtWhenEntry entry) {
            if (!entry.isElse()) {
                complexity++;
            }
            super.visitWhenEntry(entry);
        }

        @Override
        public void visitLoopExpression(KtLoopExpression expression) {
            complexity++;
            super.visitLoopExpression(expression);
        }

        @Override
        public void visitCatchSection(KtCatchClause clause) {
            complexity++;
            super.visitCatchSection(clause);
        }

        @Override
        public void visitBinaryExpression(KtBinaryExpression expression) {
            if (expression.getOperationToken() == KtTokens.ANDAND
                    || expression.getOperationToken() == KtTokens.OROR
                    || expression.getOperationToken() == KtTokens.ELVIS) {
                complexity++;
            }
            super.visitBinaryExpression(expression);
        }
    }

    private static final class NestingVisitor extends KtTreeVisitorVoid {
        private final KtNamedFunction root;
        private int depth;
        private int maxDepth;

        private NestingVisitor(KtNamedFunction root) {
            this.root = root;
        }

        @Override
        public void visitNamedFunction(KtNamedFunction function) {
            if (function == root) {
                super.visitNamedFunction(function);
            }
        }

        @Override
        public void visitClassOrObject(KtClassOrObject classOrObject) {
            // Local classes are separate owners of nesting.
        }

        @Override
        public void visitIfExpression(KtIfExpression expression) {
            if (isElseIf(expression)) {
                super.visitIfExpression(expression);
            } else {
                enter(expression);
            }
        }

        @Override
        public void visitWhenExpression(KtWhenExpression expression) {
            enter(expression);
        }

        @Override
        public void visitLoopExpression(KtLoopExpression expression) {
            enter(expression);
        }

        @Override
        public void visitTryExpression(KtTryExpression expression) {
            enter(expression);
        }

        private void enter(PsiElement element) {
            depth++;
            maxDepth = Math.max(maxDepth, depth);
            try {
                element.acceptChildren(this);
            } finally {
                depth--;
            }
        }

        private boolean isElseIf(KtIfExpression expression) {
            PsiElement parent = expression.getParent();
            if (!(parent instanceof KtContainerNodeForControlStructureBody)) {
                return false;
            }
            PsiElement grandParent = parent.getParent();
            return grandParent instanceof KtIfExpression
                    && ((KtIfExpression) grandParent).getElse() == parent;
        }
    }
}
