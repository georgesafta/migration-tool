package dev.snowdrop.mtool.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, analyzer-independent view of a Java source file for validators.
 * <p>
 * Comments are blanked (offsets and line numbers are preserved) and string/char literal contents are masked, so
 * annotation text inside comments or strings is never matched. Imports and annotation usages (with their raw
 * argument text) are extracted from the cleaned source. This avoids the gaps of the type extractors, which do not
 * report fully qualified annotation names under tree-sitter, constructors, or method bodies.
 */
public final class JavaSourceText {

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(?!static\\b)([\\w.]+(?:\\.\\*)?)\\s*;");
    private static final Pattern ANNOTATION = Pattern.compile("@\\s*((?:[A-Za-z_]\\w*\\s*\\.\\s*)*[A-Za-z_]\\w*)");

    private JavaSourceText() {
    }

    /** One annotation occurrence. {@code args} is the raw text between the parentheses, or empty. */
    public record AnnotationUse(String name, String simpleName, boolean qualified, String args, int line) {
    }

    /**
     * @param code comments blanked, strings intact (use for argument text)
     * @param masked comments blanked and string/char contents blanked (use for structural matching)
     */
    public record Parsed(Path file, String code, String masked, List<String> imports, List<AnnotationUse> annotations) {

        /**
         * True if {@code use} refers to the type {@code fqn}, through its explicit import, a wildcard import, or a qualified
         * name.
         */
        public boolean resolves(AnnotationUse use, String fqn) {
            if (use.qualified()) {
                return use.name().equals(fqn);
            }
            int dot = fqn.lastIndexOf('.');
            if (!fqn.substring(dot + 1).equals(use.simpleName())) {
                return false;
            }
            return imports.contains(fqn) || imports.contains(fqn.substring(0, dot) + ".*");
        }

        /** True if the file imports {@code fqn} explicitly or through a wildcard of its package. */
        public boolean imports(String fqn) {
            int dot = fqn.lastIndexOf('.');
            return imports.contains(fqn) || imports.contains(fqn.substring(0, dot) + ".*");
        }
    }

    public static Parsed parse(Path file, String source) {
        char[][] scanned = scan(source);
        String code = new String(scanned[0]);
        String masked = new String(scanned[1]);

        List<String> imports = new ArrayList<>();
        Matcher im = IMPORT.matcher(code);
        while (im.find()) {
            imports.add(im.group(1));
        }

        List<AnnotationUse> annotations = new ArrayList<>();
        Matcher am = ANNOTATION.matcher(masked);
        int line = 1;
        int counted = 0;
        while (am.find()) {
            String name = am.group(1).replaceAll("\\s+", "");
            if ("interface".equals(name)) {
                continue; // annotation type declaration
            }
            for (; counted < am.start(); counted++) {
                if (masked.charAt(counted) == '\n') {
                    line++;
                }
            }
            String args = "";
            int next = skipWhitespace(masked, am.end());
            if (next < masked.length() && masked.charAt(next) == '(') {
                int close = matchingParen(masked, next);
                if (close > 0) {
                    args = code.substring(next + 1, close);
                }
            }
            int lastDot = name.lastIndexOf('.');
            annotations.add(new AnnotationUse(name, name.substring(lastDot + 1), lastDot >= 0, args, line));
        }
        return new Parsed(file, code, masked, imports, annotations);
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Returns {code, masked}; both have the same length as the input and keep line breaks. */
    private static char[][] scan(String s) {
        char[] code = s.toCharArray();
        char[] masked = s.toCharArray();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            char next = i + 1 < n ? s.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                for (; i < n && s.charAt(i) != '\n'; i++) {
                    blank(code, i);
                    blank(masked, i);
                }
            } else if (c == '/' && next == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) {
                    blank(code, i);
                    blank(masked, i);
                }
            } else if (c == '"' && s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                int close = end < 0 ? n : end;
                for (int j = i + 3; j < close; j++) {
                    blank(masked, j);
                }
                i = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') {
                    if (s.charAt(j) == '\\') {
                        j++;
                    }
                    j++;
                }
                for (int k = i + 1; k < Math.min(j, n); k++) {
                    blank(masked, k);
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return new char[][] { code, masked };
    }

    private static void blank(char[] chars, int i) {
        if (chars[i] != '\n' && chars[i] != '\r') {
            chars[i] = ' ';
        }
    }
}
