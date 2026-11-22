package com.dstu.checkstyle;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.FileContents;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;

/**
 * Counts non-blank, non-comment lines of a class-like declaration. Checkstyle
 * has no built-in class-length check; this custom AST check closes that gap
 * without a source-text grep or blanket suppress.
 */
public final class ClassEffectiveLinesCheck extends AbstractCheck {
    private static final String MESSAGE_KEY = "class.effective.lines";
    private int max = 300;

    public void setMax(int max) {
        this.max = max;
    }

    @Override
    public int[] getDefaultTokens() {
        return new int[]{
            TokenTypes.CLASS_DEF,
            TokenTypes.INTERFACE_DEF,
            TokenTypes.ENUM_DEF,
            TokenTypes.ANNOTATION_DEF,
            TokenTypes.RECORD_DEF
        };
    }

    @Override
    public int[] getAcceptableTokens() {
        return getDefaultTokens();
    }

    @Override
    public int[] getRequiredTokens() {
        return new int[0];
    }

    @Override
    public void visitToken(DetailAST ast) {
        FileContents contents = getFileContents();
        int effective = 0;
        int lastLine = lastLine(ast);
        for (int line = ast.getLineNo(); line <= lastLine; line++) {
            int index = line - 1;
            if (!contents.lineIsBlank(index) && !contents.lineIsComment(index)) {
                effective++;
            }
        }
        if (effective > max) {
            log(ast, MESSAGE_KEY, max, effective);
        }
    }

    private int lastLine(DetailAST ast) {
        int last = ast.getLineNo();
        for (DetailAST child = ast.getFirstChild(); child != null; child = child.getNextSibling()) {
            last = Math.max(last, lastLine(child));
        }
        return last;
    }
}
