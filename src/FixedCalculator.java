import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;

/**
 * FixedCalculator – A Swing calculator using FixedArithmetic and FixedTrigonometry.
 * No floating-point arithmetic is used anywhere in computations.
 */
public class FixedCalculator extends JFrame {

    // ── Display ───────────────────────────────────────────────────────────────
    private JTextField display;
    private JLabel     historyLabel;

    // ── Calculator state ──────────────────────────────────────────────────────
    private FixedArithmetic accumulator   = null;
    private String          pendingOp     = null;
    private boolean         startNewInput = true;
    private boolean         justCalc      = false;   // true right after "="
    private String          lastHistory   = "";

    // ── Angle mode ────────────────────────────────────────────────────────────
    private boolean isDegrees = true;   // true = DEG, false = RAD

    // ── Fixed-point constants for degree↔radian conversion ───────────────────
    private static final FixedArithmetic FA_180    = FixedArithmetic.of(180);
    private static final FixedArithmetic FA_PI     = FixedArithmetic.of("3.141592653");

    // ── Colour palette ────────────────────────────────────────────────────────
    private static final Color BG          = new Color(0x1E1E2E);
    private static final Color DISPLAY_BG  = new Color(0x11111B);
    private static final Color DISPLAY_FG  = new Color(0xCDD6F4);
    private static final Color HIST_FG     = new Color(0x6C7086);

    private static final Color CLR_NUM     = new Color(0x313244);  // number keys
    private static final Color CLR_OP      = new Color(0x89B4FA);  // +  −  ×  ÷
    private static final Color CLR_FUNC    = new Color(0x45475A);  // sin, cos …
    private static final Color CLR_EQ      = new Color(0xA6E3A1);  // =
    private static final Color CLR_CLEAR   = new Color(0xF38BA8);  // AC / C
    private static final Color CLR_SPEC    = new Color(0xFAB387);  // ±  %  ( )
    private static final Color CLR_MODE    = new Color(0xCBA6F7);  // DEG/RAD

    private static final Color BTN_FG      = Color.WHITE;
    private static final Color BTN_PRESS   = new Color(0x585B70);

    // ── UI refs for mode toggle ───────────────────────────────────────────────
    private JButton degRadBtn;

    // =========================================================================
    // Constructor
    // =========================================================================

    public FixedCalculator() {
        super("Fixed-Point Calculator");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        add(buildDisplayPanel(), BorderLayout.NORTH);
        add(buildButtonPanel(),  BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // =========================================================================
    // Display panel
    // =========================================================================

    private JPanel buildDisplayPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(DISPLAY_BG);
        panel.setBorder(new EmptyBorder(12, 16, 12, 16));

        // History line (small, top)
        historyLabel = new JLabel(" ");
        historyLabel.setFont(new Font("Monospaced", Font.PLAIN, 13));
        historyLabel.setForeground(HIST_FG);
        historyLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        panel.add(historyLabel, BorderLayout.NORTH);

        // Main display
        display = new JTextField("0");
        display.setEditable(false);
        display.setHorizontalAlignment(JTextField.RIGHT);
        display.setBackground(DISPLAY_BG);
        display.setForeground(DISPLAY_FG);
        display.setFont(new Font("Monospaced", Font.BOLD, 32));
        display.setBorder(new EmptyBorder(6, 0, 2, 0));
        display.setCaretColor(DISPLAY_BG);
        panel.add(display, BorderLayout.CENTER);

        return panel;
    }

    // =========================================================================
    // Button panel
    // =========================================================================

    private JPanel buildButtonPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 5, 4, 4));
        panel.setBackground(BG);
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        // Row 1 – mode & memory-like extras
        degRadBtn = makeBtn("DEG", CLR_MODE);
        panel.add(degRadBtn);
        panel.add(makeBtn("(",   CLR_SPEC));
        panel.add(makeBtn(")",   CLR_SPEC));
        panel.add(makeBtn("%",   CLR_SPEC));
        panel.add(makeBtn("AC",  CLR_CLEAR));

        // Row 2 – trig row 1
        panel.add(makeBtn("sin",  CLR_FUNC));
        panel.add(makeBtn("cos",  CLR_FUNC));
        panel.add(makeBtn("tan",  CLR_FUNC));
        panel.add(makeBtn("±",    CLR_SPEC));
        panel.add(makeBtn("⌫",    CLR_CLEAR));

        // Row 3 – trig row 2
        panel.add(makeBtn("asin", CLR_FUNC));
        panel.add(makeBtn("acos", CLR_FUNC));
        panel.add(makeBtn("atan", CLR_FUNC));
        panel.add(makeBtn("1/x",  CLR_FUNC));
        panel.add(makeBtn("÷",    CLR_OP));

        // Row 4
        panel.add(makeBtn("7",  CLR_NUM));
        panel.add(makeBtn("8",  CLR_NUM));
        panel.add(makeBtn("9",  CLR_NUM));
        panel.add(makeBtn("x²", CLR_FUNC));
        panel.add(makeBtn("×",  CLR_OP));

        // Row 5
        panel.add(makeBtn("4",  CLR_NUM));
        panel.add(makeBtn("5",  CLR_NUM));
        panel.add(makeBtn("6",  CLR_NUM));
        panel.add(makeBtn("√x", CLR_FUNC));
        panel.add(makeBtn("−",  CLR_OP));

        // Row 6
        panel.add(makeBtn("1",  CLR_NUM));
        panel.add(makeBtn("2",  CLR_NUM));
        panel.add(makeBtn("3",  CLR_NUM));
        panel.add(makeBtn("π",  CLR_SPEC));
        panel.add(makeBtn("+",  CLR_OP));

        // Row 7
        panel.add(makeBtn("0",  CLR_NUM));
        panel.add(makeBtn("00", CLR_NUM));
        panel.add(makeBtn(".",  CLR_NUM));
        panel.add(makeBtn("e",  CLR_SPEC));
        panel.add(makeBtn("=",  CLR_EQ));

        return panel;
    }

    // =========================================================================
    // Button factory
    // =========================================================================

    private JButton makeBtn(String label, Color bg) {
        JButton btn = new JButton(label);
        btn.setFont(new Font("SansSerif", Font.BOLD, 15));
        btn.setBackground(bg);
        btn.setForeground(BTN_FG);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setPreferredSize(new Dimension(80, 56));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btn.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { btn.setBackground(BTN_PRESS); }
            @Override public void mouseReleased(MouseEvent e) { btn.setBackground(bg); }
        });

        btn.addActionListener(e -> handleButton(label));
        return btn;
    }

    // =========================================================================
    // Button handler – pure dispatch
    // =========================================================================

    private void handleButton(String label) {
        try {
            switch (label) {
                // ── digits ────────────────────────────────────────────────────
                case "0": case "1": case "2": case "3": case "4":
                case "5": case "6": case "7": case "8": case "9":
                    appendDigit(label); break;
                case "00":
                    appendDigit("0"); appendDigit("0"); break;
                case ".":
                    appendDot(); break;

                // ── constants ─────────────────────────────────────────────────
                case "π":
                    inputConstant("3.141592653"); break;
                case "e":
                    inputConstant("2.718281828"); break;

                // ── binary operators ──────────────────────────────────────────
                case "+": case "−": case "×": case "÷":
                    handleBinaryOp(label); break;

                // ── unary / instant functions ─────────────────────────────────
                case "sin":  applyUnary("sin");  break;
                case "cos":  applyUnary("cos");  break;
                case "tan":  applyUnary("tan");  break;
                case "asin": applyUnary("asin"); break;
                case "acos": applyUnary("acos"); break;
                case "atan": applyUnary("atan"); break;
                case "x²":   applyUnary("x²");  break;
                case "√x":   applyUnary("√x");  break;
                case "1/x":  applyUnary("1/x"); break;
                case "±":    applyUnary("±");   break;
                case "%":    applyUnary("%");   break;

                // ── equals ────────────────────────────────────────────────────
                case "=":    handleEquals(); break;

                // ── clear / backspace ──────────────────────────────────────────
                case "AC":   clearAll();    break;
                case "⌫":   backspace();   break;

                // ── angle mode ────────────────────────────────────────────────
                case "DEG": case "RAD":
                    toggleAngleMode(); break;

                // ── parentheses (handled via expression string) ───────────────
                case "(": appendRaw("("); break;
                case ")": appendRaw(")"); break;
            }
        } catch (ArithmeticException ex) {
            displayError(ex.getMessage());
        } catch (Exception ex) {
            displayError("Error");
        }
    }

    // =========================================================================
    // Digit / dot input
    // =========================================================================

    private void appendDigit(String d) {
        if (startNewInput || justCalc) {
            display.setText(d.equals("0") ? "0" : d);
            startNewInput = false;
            justCalc      = false;
        } else {
            String cur = display.getText();
            if (cur.equals("0")) display.setText(d);
            else display.setText(cur + d);
        }
    }

    private void appendDot() {
        if (startNewInput || justCalc) {
            display.setText("0.");
            startNewInput = false;
            justCalc      = false;
            return;
        }
        String cur = display.getText();
        if (!cur.contains(".")) display.setText(cur + ".");
    }

    private void appendRaw(String s) {
        // Simple append – used for ( and ) if user types them manually.
        if (startNewInput) { display.setText(s); startNewInput = false; }
        else display.setText(display.getText() + s);
    }

    private void inputConstant(String val) {
        display.setText(val);
        startNewInput = false;
        justCalc      = false;
    }

    // =========================================================================
    // Binary operators  (+  −  ×  ÷)
    // =========================================================================

    private void handleBinaryOp(String op) {
        FixedArithmetic current = parseDisplay();
        if (accumulator != null && !startNewInput) {
            // Chain calculation
            current = applyOp(accumulator, pendingOp, current);
            setDisplay(current);
        }
        accumulator   = current;
        pendingOp     = op;
        startNewInput = true;
        justCalc      = false;
        historyLabel.setText(fmtHistory(accumulator) + " " + op);
    }

    // =========================================================================
    // Equals
    // =========================================================================

    private void handleEquals() {
        if (accumulator == null || pendingOp == null) return;

        FixedArithmetic rhs = parseDisplay();
        String expr = fmtHistory(accumulator) + " " + pendingOp + " " + fmtHistory(rhs) + " =";

        FixedArithmetic result = applyOp(accumulator, pendingOp, rhs);

        historyLabel.setText(expr);
        setDisplay(result);

        accumulator   = null;
        pendingOp     = null;
        startNewInput = true;
        justCalc      = true;
    }

    // =========================================================================
    // Apply binary op
    // =========================================================================

    private FixedArithmetic applyOp(FixedArithmetic a, String op, FixedArithmetic b) {
        switch (op) {
            case "+": return a.add(b);
            case "−": return a.subtract(b);
            case "×": return a.multiply(b);
            case "÷": return a.divide(b);
        }
        throw new ArithmeticException("Unknown operator: " + op);
    }

    // =========================================================================
    // Unary / instant operations
    // =========================================================================

    private void applyUnary(String fn) {
        FixedArithmetic x = parseDisplay();
        FixedArithmetic result;
        String histStr;

        switch (fn) {
            case "sin":
                histStr = "sin(" + fmtHistory(x) + (isDegrees ? "°" : "ʳ") + ")";
                result  = FixedTrigonometry.sin(toRadians(x));
                break;
            case "cos":
                histStr = "cos(" + fmtHistory(x) + (isDegrees ? "°" : "ʳ") + ")";
                result  = FixedTrigonometry.cos(toRadians(x));
                break;
            case "tan":
                histStr = "tan(" + fmtHistory(x) + (isDegrees ? "°" : "ʳ") + ")";
                result  = FixedTrigonometry.tan(toRadians(x));
                break;
            case "asin":
                histStr = "asin(" + fmtHistory(x) + ")";
                result  = fromRadians(FixedTrigonometry.asin(x));
                break;
            case "acos":
                histStr = "acos(" + fmtHistory(x) + ")";
                result  = fromRadians(FixedTrigonometry.acos(x));
                break;
            case "atan":
                histStr = "atan(" + fmtHistory(x) + ")";
                result  = fromRadians(FixedTrigonometry.atan(x));
                break;
            case "x²":
                histStr = "(" + fmtHistory(x) + ")²";
                result  = x.multiply(x);
                break;
            case "√x": {
                histStr = "√(" + fmtHistory(x) + ")";
                if (x.rawScaled() < 0)
                    throw new ArithmeticException("√ of negative number");
                result = fixedSqrt(x);
                break;
            }
            case "1/x":
                histStr = "1/(" + fmtHistory(x) + ")";
                result  = FixedArithmetic.of(1).divide(x);
                break;
            case "±":
                histStr = "-(" + fmtHistory(x) + ")";
                result  = FixedArithmetic.of(0).subtract(x);
                break;
            case "%":
                histStr = fmtHistory(x) + "%";
                result  = x.divide(FixedArithmetic.of(100));
                break;
            default:
                throw new ArithmeticException("Unknown function: " + fn);
        }

        historyLabel.setText(histStr + " =");
        setDisplay(result);
        startNewInput = true;
        justCalc      = true;

        // If there's a pending binary op, update the accumulator
        if (accumulator != null && pendingOp != null) {
            // Re-use the result as the new RHS seed – keep chain going
            justCalc = false;
        }
    }

    // =========================================================================
    // Square root via Newton–Raphson using only FixedArithmetic
    // =========================================================================

    private FixedArithmetic fixedSqrt(FixedArithmetic x) {
        if (x.rawScaled() == 0) return FixedArithmetic.of(0);

        // Seed: use integer part + 1 as starting guess
        long ip = x.integerPart();
        if (ip < 1) ip = 1;
        FixedArithmetic guess = FixedArithmetic.of(ip);
        FixedArithmetic half  = FixedArithmetic.of("0.5");

        for (int i = 0; i < 20; i++) {
            guess = guess.add(x.divide(guess)).multiply(half);
        }
        return guess;
    }

    // =========================================================================
    // Angle mode helpers
    // =========================================================================

    /** Convert display value to radians if in degree mode. */
    private FixedArithmetic toRadians(FixedArithmetic deg) {
        if (!isDegrees) return deg;
        // radians = degrees * π / 180
        return deg.multiply(FA_PI).divide(FA_180);
    }

    /** Convert a radian result to degrees if in degree mode. */
    private FixedArithmetic fromRadians(FixedArithmetic rad) {
        if (!isDegrees) return rad;
        // degrees = radians * 180 / π
        return rad.multiply(FA_180).divide(FA_PI);
    }

    private void toggleAngleMode() {
        isDegrees = !isDegrees;
        degRadBtn.setText(isDegrees ? "DEG" : "RAD");
    }

    // =========================================================================
    // Clear / backspace
    // =========================================================================

    private void clearAll() {
        display.setText("0");
        historyLabel.setText(" ");
        accumulator   = null;
        pendingOp     = null;
        startNewInput = true;
        justCalc      = false;
    }

    private void backspace() {
        if (startNewInput) return;
        String cur = display.getText();
        if (cur.length() <= 1 || (cur.length() == 2 && cur.charAt(0) == '-')) {
            display.setText("0");
            startNewInput = true;
        } else {
            display.setText(cur.substring(0, cur.length() - 1));
        }
    }

    // =========================================================================
    // Display helpers
    // =========================================================================

    private void setDisplay(FixedArithmetic val) {
        display.setText(formatFixed(val));
    }

    private void displayError(String msg) {
        display.setText("Error");
        historyLabel.setText(msg != null ? msg : "Error");
        accumulator   = null;
        pendingOp     = null;
        startNewInput = true;
        justCalc      = false;
    }

    /**
     * Format a FixedArithmetic value for display.
     * Trims trailing zeros after the decimal point; shows up to 9 decimal places.
     */
    private static String formatFixed(FixedArithmetic val) {
        String s = val.toString();
        // Remove trailing zeros after decimal point
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Short representation for the history label. */
    private static String fmtHistory(FixedArithmetic val) {
        return formatFixed(val);
    }

    /**
     * Parse the current display string into a FixedArithmetic value.
     * Falls back to zero on parse failure.
     */
    private FixedArithmetic parseDisplay() {
        String text = display.getText().trim();
        if (text.isEmpty() || text.equals("Error")) return FixedArithmetic.of(0);
        try {
            return FixedArithmetic.of(text);
        } catch (Exception e) {
            return FixedArithmetic.of(0);
        }
    }

    // =========================================================================
    // main
    // =========================================================================

    public static void main(String[] args) {
        // Use system look-and-feel hints; run on the EDT
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(FixedCalculator::new);
    }
}
