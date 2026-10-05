package io.jettra.store.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Motor de Operaciones Matemáticas de Alto Rendimiento para JettraStore.
 */
public final class JettraMath {

    private JettraMath() {}

    public static double abs(double x) {
        return Math.abs(x);
    }

    public static double round(double x, int decimals) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        BigDecimal bd = BigDecimal.valueOf(x);
        bd = bd.setScale(decimals, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    public static double ceil(double x) {
        return Math.ceil(x);
    }

    public static double floor(double x) {
        return Math.floor(x);
    }

    public static double sqrt(double x) {
        if (x < 0) throw new IllegalArgumentException("Cannot compute square root of negative number: " + x);
        return Math.sqrt(x);
    }

    public static double pow(double base, double exp) {
        return Math.pow(base, exp);
    }

    public static double mod(double x, double y) {
        if (y == 0) throw new ArithmeticException("Division by zero in modulo operation");
        return x % y;
    }

    public static double log(double x) {
        if (x <= 0) throw new IllegalArgumentException("Logarithm argument must be positive: " + x);
        return Math.log(x);
    }

    public static double log10(double x) {
        if (x <= 0) throw new IllegalArgumentException("Logarithm argument must be positive: " + x);
        return Math.log10(x);
    }

    public static double exp(double x) {
        return Math.exp(x);
    }

    public static double sin(double radians) {
        return Math.sin(radians);
    }

    public static double cos(double radians) {
        return Math.cos(radians);
    }

    public static double tan(double radians) {
        return Math.tan(radians);
    }

    public static double asin(double x) {
        return Math.asin(x);
    }

    public static double acos(double x) {
        return Math.acos(x);
    }

    public static double atan(double x) {
        return Math.atan(x);
    }

    public static double atan2(double y, double x) {
        return Math.atan2(y, x);
    }

    public static double toDegrees(double radians) {
        return Math.toDegrees(radians);
    }

    public static double toRadians(double degrees) {
        return Math.toRadians(degrees);
    }

    public static double sign(double x) {
        return Math.signum(x);
    }

    public static double hypot(double x, double y) {
        return Math.hypot(x, y);
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double cbrt(double x) {
        return Math.cbrt(x);
    }

    public static long factorial(int n) {
        if (n < 0) throw new IllegalArgumentException("Factorial undefined for negative numbers: " + n);
        if (n > 20) throw new ArithmeticException("Factorial overflow for n > 20: " + n);
        long res = 1;
        for (int i = 2; i <= n; i++) res *= i;
        return res;
    }

    public static long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }

    public static long lcm(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return Math.abs(a * (b / gcd(a, b)));
    }

    /**
     * Evaluador de expresiones matemáticas (soporta operadores +, -, *, /, %, ^ y funciones matemáticas).
     */
    public static double eval(String expression) {
        return eval(expression, Collections.emptyMap());
    }

    public static double eval(String expression, Map<String, Double> variables) {
        if (expression == null || expression.isBlank()) return 0.0;
        String expr = expression.trim();
        for (Map.Entry<String, Double> entry : variables.entrySet()) {
            expr = expr.replaceAll("(?i)\\b" + entry.getKey() + "\\b", String.valueOf(entry.getValue()));
        }
        return new Parser(expr).parse();
    }

    private static class Parser {
        private final String str;
        private int pos = -1, ch;

        Parser(String str) { this.str = str; }

        void nextChar() {
            ch = (++pos < str.length()) ? str.charAt(pos) : -1;
        }

        boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        double parse() {
            nextChar();
            double x = parseExpression();
            if (pos < str.length()) throw new RuntimeException("Unexpected character in expression: " + (char)ch);
            return x;
        }

        double parseExpression() {
            double x = parseTerm();
            for (;;) {
                if      (eat('+')) x += parseTerm();
                else if (eat('-')) x -= parseTerm();
                else return x;
            }
        }

        double parseTerm() {
            double x = parseFactor();
            for (;;) {
                if      (eat('*')) x *= parseFactor();
                else if (eat('/')) x /= parseFactor();
                else if (eat('%')) x %= parseFactor();
                else return x;
            }
        }

        double parseFactor() {
            if (eat('+')) return +parseFactor();
            if (eat('-')) return -parseFactor();

            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                eat(')');
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                x = Double.parseDouble(str.substring(startPos, this.pos));
            } else if (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z') {
                while (ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9' || ch == '_') nextChar();
                String func = str.substring(startPos, this.pos).toLowerCase();
                if (eat('(')) {
                    List<Double> args = new ArrayList<>();
                    if (!eat(')')) {
                        args.add(parseExpression());
                        while (eat(',')) {
                            args.add(parseExpression());
                        }
                        eat(')');
                    }
                    x = switch (func) {
                        case "sqrt" -> Math.sqrt(args.get(0));
                        case "sin"  -> Math.sin(args.get(0));
                        case "cos"  -> Math.cos(args.get(0));
                        case "tan"  -> Math.tan(args.get(0));
                        case "abs"  -> Math.abs(args.get(0));
                        case "log", "ln" -> Math.log(args.get(0));
                        case "log10" -> Math.log10(args.get(0));
                        case "exp"  -> Math.exp(args.get(0));
                        case "ceil" -> Math.ceil(args.get(0));
                        case "floor"-> Math.floor(args.get(0));
                        case "round"-> (args.size() > 1) ? round(args.get(0), args.get(1).intValue()) : Math.round(args.get(0));
                        case "pow", "power" -> Math.pow(args.get(0), args.get(1));
                        case "mod"  -> args.get(0) % args.get(1);
                        case "cbrt" -> Math.cbrt(args.get(0));
                        case "hypot"-> Math.hypot(args.get(0), args.get(1));
                        case "atan2"-> Math.atan2(args.get(0), args.get(1));
                        case "clamp"-> clamp(args.get(0), args.get(1), args.get(2));
                        case "sign" -> Math.signum(args.get(0));
                        case "deg", "todegrees" -> Math.toDegrees(args.get(0));
                        case "rad", "toradians" -> Math.toRadians(args.get(0));
                        case "fact", "factorial" -> (double) factorial(args.get(0).intValue());
                        case "gcd"  -> (double) gcd(args.get(0).longValue(), args.get(1).longValue());
                        case "lcm"  -> (double) lcm(args.get(0).longValue(), args.get(1).longValue());
                        default -> throw new RuntimeException("Unknown math function: " + func);
                    };
                } else {
                    x = switch (func) {
                        case "pi" -> Math.PI;
                        case "e"  -> Math.E;
                        default -> throw new RuntimeException("Unknown constant: " + func);
                    };
                }
            } else {
                throw new RuntimeException("Unexpected character in expression: " + (char)ch);
            }

            if (eat('^')) x = Math.pow(x, parseFactor());

            return x;
        }
    }
}
