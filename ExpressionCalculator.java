/**
 * ExpressionCalculator
 *
 * A command-line mathematical expression evaluator that uses FixedArithmetic
 * by Mario Gianota as its sole calculation engine.
 *
 * Supported syntax
 * ────────────────
 *   Numbers   : integer or decimal literals, e.g. 42, 3.14, -2.5
 *   Operators : + - * /   (standard precedence: * and / before + and -)
 *   Grouping  : parentheses ( … ) for explicit precedence
 *   Unary -   : negation, e.g. -(3+4) or -7
 *
 * Usage
 * ─────
 *   Compile both files in the same directory, then run:
 *
 *     java ExpressionCalculator "3.14 * (2 + 8) / 5"
 *
 *   Or launch with no arguments for an interactive REPL:
 *
 *     java ExpressionCalculator
 *
 * Grammar (recursive-descent)
 * ───────────────────────────
 *   expression  →  term  ( ( '+' | '-' )  term  )*
 *   term        →  factor  ( ( '*' | '/' )  factor  )*
 *   factor      →  '-' factor
 *                | '(' expression ')'
 *                | number
 *   number      →  ['-'] digit+ [ '.' digit+ ]
 */
import java.util.Scanner;

public class ExpressionCalculator {

    // ── Tokeniser ─────────────────────────────────────────────────────────────

    /** Token types produced by the lexer. */
    private enum TokenType {
        NUMBER, PLUS, MINUS, STAR, SLASH, LPAREN, RPAREN, EOF
    }

    private static final class Token {
        final TokenType type;
        final String    value;   // set for NUMBER tokens
        Token(TokenType type, String value) { this.type = type; this.value = value; }
        Token(TokenType type)               { this(type, null); }
        @Override public String toString()  { return value != null ? value : type.name(); }
    }

    /** Converts a raw expression string into a Token array. */
    private static Token[] tokenise(String input) {
        // We'll collect tokens into a growable array (no ArrayList to keep it
        // dependency-light; we over-allocate and trim at the end).
        Token[] buf = new Token[input.length() + 1];
        int     count = 0;
        int     i     = 0;

        while (i < input.length()) {
            char c = input.charAt(i);

            // Skip whitespace
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i = i + 1;
                continue;
            }

            switch (c) {
                case '+': buf[count++] = new Token(TokenType.PLUS);   i++; break;
                case '-': buf[count++] = new Token(TokenType.MINUS);  i++; break;
                case '*': buf[count++] = new Token(TokenType.STAR);   i++; break;
                case '/': buf[count++] = new Token(TokenType.SLASH);  i++; break;
                case '(': buf[count++] = new Token(TokenType.LPAREN); i++; break;
                case ')': buf[count++] = new Token(TokenType.RPAREN); i++; break;
                default:
                    if (c >= '0' && c <= '9') {
                        // Read a decimal number literal
                        StringBuilder sb = new StringBuilder();
                        while (i < input.length()) {
                            char ch = input.charAt(i);
                            if (ch >= '0' && ch <= '9') {
                                sb.append(ch); i++;
                            } else if (ch == '.' && sb.indexOf(".") == -1) {
                                sb.append(ch); i++;
                            } else {
                                break;
                            }
                        }
                        buf[count++] = new Token(TokenType.NUMBER, sb.toString());
                    } else {
                        throw new IllegalArgumentException(
                            "Unknown character '" + c + "' at position " + i);
                    }
            }
        }

        buf[count++] = new Token(TokenType.EOF);

        // Trim to exact length
        Token[] result = new Token[count];
        for (int k = 0; k < count; k++) result[k] = buf[k];
        return result;
    }

    // ── Parser / Evaluator ────────────────────────────────────────────────────

    /**
     * Recursive-descent parser that evaluates directly to FixedArithmetic
     * values — no AST, no intermediate representation.
     */
    private static final class Parser {
        private final Token[] tokens;
        private       int     pos;

        Parser(Token[] tokens) {
            this.tokens = tokens;
            this.pos    = 0;
        }

        private Token peek() { return tokens[pos]; }

        private Token consume() {
            Token t = tokens[pos];
            pos = pos + 1;
            return t;
        }

        private Token expect(TokenType type) {
            Token t = consume();
            if (t.type != type)
                throw new IllegalArgumentException(
                    "Expected " + type + " but found '" + t + "'");
            return t;
        }

        // expression → term ( ( '+' | '-' ) term )*
        FixedArithmetic parseExpression() {
            FixedArithmetic left = parseTerm();

            while (peek().type == TokenType.PLUS || peek().type == TokenType.MINUS) {
                TokenType op = consume().type;
                FixedArithmetic right = parseTerm();
                if (op == TokenType.PLUS)  left = left.add(right);
                else                       left = left.subtract(right);
            }
            return left;
        }

        // term → factor ( ( '*' | '/' ) factor )*
        private FixedArithmetic parseTerm() {
            FixedArithmetic left = parseFactor();

            while (peek().type == TokenType.STAR || peek().type == TokenType.SLASH) {
                TokenType op = consume().type;
                FixedArithmetic right = parseFactor();
                if (op == TokenType.STAR) left = left.multiply(right);
                else                      left = left.divide(right);
            }
            return left;
        }

        // factor → '-' factor | '(' expression ')' | number
        private FixedArithmetic parseFactor() {
            Token t = peek();

            // Unary negation
            if (t.type == TokenType.MINUS) {
                consume();
                FixedArithmetic operand = parseFactor();
                return FixedArithmetic.of(0).subtract(operand);
            }

            // Parenthesised sub-expression
            if (t.type == TokenType.LPAREN) {
                consume();
                FixedArithmetic value = parseExpression();
                expect(TokenType.RPAREN);
                return value;
            }

            // Numeric literal
            if (t.type == TokenType.NUMBER) {
                consume();
                return FixedArithmetic.of(t.value);
            }

            throw new IllegalArgumentException(
                "Unexpected token '" + t + "' — expected a number or '('");
        }
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Evaluates a mathematical expression string and returns the result as a
     * FixedArithmetic value.
     *
     * @param  expression  infix expression, e.g. "(3.14 + 1) * 2 / 4"
     * @return             the computed result
     * @throws IllegalArgumentException on syntax errors
     * @throws ArithmeticException      on division by zero
     */
    public static FixedArithmetic evaluate(String expression) {
        Token[] tokens = tokenise(expression);
        Parser  parser = new Parser(tokens);
        FixedArithmetic result = parser.parseExpression();
        if (parser.peek().type != TokenType.EOF)
            throw new IllegalArgumentException(
                "Unexpected token '" + parser.peek() + "' — expression not fully consumed");
        return result;
    }

    // ── main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        if (args.length > 0) {
            // ── Single-shot mode: expression passed as command-line argument ──
            // Join all args in case the shell split on spaces without quoting
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(args[i]);
            }
            String expr = sb.toString().trim();
            runExpression(expr);
        } else {
            // ── Interactive REPL mode ─────────────────────────────────────────
            System.out.println("ExpressionCalculator  (engine: FixedArithmetic by Mario Gianota)");
            System.out.println("Operators: + - * /    Grouping: ( )    Precision: "
                               + FixedArithmetic.PRECISION + " decimal places");
            System.out.println("Type an expression and press Enter.  Type 'quit' or 'exit' to stop.\n");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) break;           // EOF (Ctrl-D)
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                if (line.equalsIgnoreCase("quit") || line.equalsIgnoreCase("exit")) break;
                runExpression(line);
            }
            System.out.println("Goodbye.");
            scanner.close();
        }
    }

    /** Evaluate one expression string and print the result (or an error). */
    private static void runExpression(String expr) {
        try {
            FixedArithmetic result = evaluate(expr);
            // Strip unnecessary trailing ".0" so "4 + 6" shows "10" not "10.0"
            String s = result.toString();
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            System.out.println("  = " + s);
        } catch (ArithmeticException e) {
            System.out.println("  Error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("  Syntax error: " + e.getMessage());
        }
    }
}
