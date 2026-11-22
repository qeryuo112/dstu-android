package com.dstu.architecture.analyzer.psi;

import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.PsiComment;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

/**
 * Counts non-blank source lines after blanking comment characters. The
 * replacement is offset-based, so comment markers inside string literals are
 * never mistaken for comments.
 */
public final class EffectiveLines {
    private EffectiveLines() {
    }

    public static int count(PsiElement element) {
        TextRange elementRange = element.getTextRange();
        String text = element.getText();
        char[] chars = text.toCharArray();
        int elementStart = elementRange.getStartOffset();
        for (PsiComment comment : PsiTreeUtil.collectElementsOfType(element, PsiComment.class)) {
            TextRange commentRange = comment.getTextRange();
            int start = Math.max(0, commentRange.getStartOffset() - elementStart);
            int end = Math.min(chars.length, commentRange.getEndOffset() - elementStart);
            for (int index = start; index < end; index++) {
                if (chars[index] != '\n' && chars[index] != '\r') {
                    chars[index] = ' ';
                }
            }
        }
        int lines = 0;
        boolean hasContent = false;
        for (char character : chars) {
            if (character == '\n') {
                if (hasContent) {
                    lines++;
                }
                hasContent = false;
            } else if (!Character.isWhitespace(character)) {
                hasContent = true;
            }
        }
        if (hasContent) {
            lines++;
        }
        return lines;
    }
}
