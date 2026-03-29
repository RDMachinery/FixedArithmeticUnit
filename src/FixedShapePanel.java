import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

/**
 * FixedShapePanel
 *
 * A Java Swing application that renders a collection of primitive geometric
 * shapes whose geometry is computed entirely through {@link FixedArithmetic}
 * and {@link FixedTrigonometry}.  No {@code java.lang.Math} trig calls are
 * used to derive vertex positions, radii, or angles — every such value flows
 * from the fixed-point engine.
 *
 * <h2>Shapes rendered</h2>
 * <ol>
 *   <li><b>Equilateral triangle</b> — vertices at angles 90°, 210°, 330°
 *       on a circumscribed circle, computed with {@code cos} / {@code sin}.</li>
 *   <li><b>Square</b> — vertices at 45°, 135°, 225°, 315°.</li>
 *   <li><b>Regular pentagon</b> — vertices every 72° (2π/5).</li>
 *   <li><b>Regular hexagon</b> — vertices every 60° (2π/6).</li>
 *   <li><b>Circle</b> — drawn as a 72-point polygon approximation;
 *       each point uses {@code cos} and {@code sin}.</li>
 *   <li><b>Sine wave</b> — sampled across one full period using
 *       {@code FixedTrigonometry.sin}.</li>
 *   <li><b>Polar rose</b> — {@code r = cos(3θ)}, the classical
 *       three-petal rhodonea curve, traced with {@code cos} / {@code sin}.</li>
 *   <li><b>Archimedean spiral</b> — {@code r = aθ}, traced with
 *       {@code cos} / {@code sin}.</li>
 * </ol>
 *
 * <h2>Architecture</h2>
 * All geometry is pre-computed once in {@code buildShapes()} at construction
 * time and stored as {@link Shape} objects.  The EDT thread only paints;
 * no fixed-point arithmetic occurs on the paint path.
 *
 * <h2>Usage</h2>
 * Compile all three classes together and run {@code FixedShapePanel}:
 * <pre>
 *   javac FixedArithmetic.java FixedTrigonometry.java FixedShapePanel.java
 *   java  FixedShapePanel
 * </pre>
 */
public class FixedShapePanel extends JPanel {

    // ── window / layout constants ─────────────────────────────────────────────

    private static final int WINDOW_W   = 960;
    private static final int WINDOW_H   = 720;
    private static final int COLS       = 4;   // shapes per row
    private static final int CELL_W     = WINDOW_W / COLS;
    private static final int CELL_H     = WINDOW_H / 2;
    private static final int RADIUS     = (int) (Math.min(CELL_W, CELL_H) * 0.35);

    // ── palette ───────────────────────────────────────────────────────────────

    private static final Color BG          = new Color(0x1A1A2E);
    private static final Color GRID_LINE   = new Color(0x2A2A4A);
    // Bug fix: Color(int, boolean) with a 24-bit literal like 0xFF6B6B has
    // 0x00 in the high (alpha) byte, making every colour fully transparent.
    // Use Color(int rgb) — no hasAlpha flag — for fully opaque base colours.
    private static final Color[] FILL_COLORS = {
        new Color(0xFF6B6B),   // coral
        new Color(0x4ECDC4),   // teal
        new Color(0xFFE66D),   // yellow
        new Color(0xA8E6CF),   // mint
        new Color(0xFF8B94),   // pink
        new Color(0xC7CEEA),   // lavender
        new Color(0xFDDB92),   // peach
        new Color(0xB5EAD7),   // seafoam
    };
    private static final float STROKE_WIDTH = 2.0f;

    // ── pre-computed geometry ─────────────────────────────────────────────────

    /** Each entry: { shape, label, cell-column, cell-row } */
    private final List<ShapeEntry> shapes = new ArrayList<>();

    // ── inner record ─────────────────────────────────────────────────────────

    private static class ShapeEntry {
        final Shape   shape;
        final String  label;
        final int     col;
        final int     row;
        final Color   color;
        final boolean closed;   // true = closed polygon → fill; false = open curve → outline only

        ShapeEntry(Shape shape, String label, int col, int row, Color color, boolean closed) {
            this.shape  = shape;
            this.label  = label;
            this.col    = col;
            this.row    = row;
            this.color  = color;
            this.closed = closed;
        }
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    public FixedShapePanel() {
        setPreferredSize(new Dimension(WINDOW_W, WINDOW_H));
        setBackground(BG);
        buildShapes();
    }

    // =========================================================================
    // Geometry builders  (all trig via FixedTrigonometry)
    // =========================================================================

    /**
     * Pre-computes all shapes.  Each shape is placed in a logical grid cell
     * (col, row); the cell centre is the origin for that shape.
     */
    private void buildShapes() {

        // ── 1. Equilateral triangle  (col 0, row 0) ──────────────────────────
        //   vertices at 90°, 210°, 330°  →  π/2, 7π/6, 11π/6
        shapes.add(new ShapeEntry(
            regularPolygon(3, RADIUS,
                FixedArithmetic.of("1.570796326"),   // π/2  — point up
                cellCx(0), cellCy(0)),
            "Equilateral Triangle", 0, 0, FILL_COLORS[0], true));

        // ── 2. Square  (col 1, row 0) ────────────────────────────────────────
        //   vertices at 45°, 135°, 225°, 315°  →  π/4
        shapes.add(new ShapeEntry(
            regularPolygon(4, RADIUS,
                FixedArithmetic.of("0.785398163"),   // π/4
                cellCx(1), cellCy(0)),
            "Square", 1, 0, FILL_COLORS[1], true));

        // ── 3. Regular pentagon  (col 2, row 0) ──────────────────────────────
        //   start angle π/2 so one vertex points straight up
        shapes.add(new ShapeEntry(
            regularPolygon(5, RADIUS,
                FixedArithmetic.of("1.570796326"),
                cellCx(2), cellCy(0)),
            "Pentagon", 2, 0, FILL_COLORS[2], true));

        // ── 4. Regular hexagon  (col 3, row 0) ───────────────────────────────
        shapes.add(new ShapeEntry(
            regularPolygon(6, RADIUS,
                FixedArithmetic.of("0"),             // flat top
                cellCx(3), cellCy(0)),
            "Hexagon", 3, 0, FILL_COLORS[3], true));

        // ── 5. Circle approximation  (col 0, row 1) ──────────────────────────
        //   72 vertices equally spaced around 2π
        shapes.add(new ShapeEntry(
            regularPolygon(72, RADIUS,
                FixedArithmetic.of("0"),
                cellCx(0), cellCy(1)),
            "Circle (72-gon)", 0, 1, FILL_COLORS[4], true));

        // ── 6. Sine wave  (col 1, row 1) — open path, outline only ───────────
        shapes.add(new ShapeEntry(
            sineWave(cellCx(1), cellCy(1),
                CELL_W - 30,
                (int)(RADIUS * 0.7)),
            "Sine Wave", 1, 1, FILL_COLORS[5], false));

        // ── 7. Polar rose  r = cos(3θ)  (col 2, row 1) ───────────────────────
        shapes.add(new ShapeEntry(
            polarRose(3, RADIUS, cellCx(2), cellCy(1)),
            "Polar Rose  r = cos(3θ)", 2, 1, FILL_COLORS[6], true));

        // ── 8. Archimedean spiral  (col 3, row 1) — open path, outline only ──
        shapes.add(new ShapeEntry(
            archimedeanSpiral(RADIUS, cellCx(3), cellCy(1)),
            "Archimedean Spiral", 3, 1, FILL_COLORS[7], false));
    }

    // ── helper: cell centre coordinates ──────────────────────────────────────

    private static int cellCx(int col) { return col * CELL_W + CELL_W / 2; }
    private static int cellCy(int row) { return row * CELL_H + CELL_H / 2; }

    // ── shape builders ────────────────────────────────────────────────────────

    /**
     * Builds a regular n-gon centred at (cx, cy) with circumradius {@code r}.
     * The first vertex is placed at {@code startAngle} (radians, measured
     * counter-clockwise from the positive x-axis).
     *
     * <p>Every vertex coordinate is derived from:
     * <pre>
     *   x_k = cx + r · cos(startAngle + k · 2π/n)
     *   y_k = cy − r · sin(startAngle + k · 2π/n)   (y-axis inverted in screen coords)
     * </pre>
     */
    private static Shape regularPolygon(int n, int r,
                                        FixedArithmetic startAngle,
                                        int cx, int cy) {
        // Step angle: 2π / n
        FixedArithmetic twoPi     = FixedArithmetic.of("6.283185307");
        FixedArithmetic stepAngle = twoPi.divide(FixedArithmetic.of(n));
        FixedArithmetic radius    = FixedArithmetic.of(r);

        Path2D path = new Path2D.Double();
        for (int k = 0; k < n; k++) {
            // angle_k = startAngle + k * stepAngle
            FixedArithmetic angle = startAngle.add(
                stepAngle.multiply(FixedArithmetic.of(k)));

            double px = cx + toDouble(radius.multiply(FixedTrigonometry.cos(angle)));
            double py = cy - toDouble(radius.multiply(FixedTrigonometry.sin(angle)));

            if (k == 0) path.moveTo(px, py);
            else        path.lineTo(px, py);
        }
        path.closePath();
        return path;
    }

    /**
     * Builds one full period of a sine wave centred at (cx, cy).
     *
     * <p>Samples 200 points across θ ∈ [0, 2π]:
     * <pre>
     *   x = cx − halfWidth + (i / samples) · width
     *   y = cy − amplitude · sin(2π · i / samples)
     * </pre>
     */
    private static Shape sineWave(int cx, int cy, int width, int amplitude) {
        int samples = 200;
        FixedArithmetic twoPi     = FixedArithmetic.of("6.283185307");
        FixedArithmetic fSamples  = FixedArithmetic.of(samples);
        FixedArithmetic fAmp      = FixedArithmetic.of(amplitude);
        FixedArithmetic fWidth    = FixedArithmetic.of(width);
        FixedArithmetic halfWidth = fWidth.divide(FixedArithmetic.of(2));

        // Divide FIRST to produce a small per-step increment, then multiply by i.
        // The original order (large * i) / N causes the intermediate product's
        // remainder × SCALE to overflow a long inside FixedArithmetic.divide.
        FixedArithmetic stepTheta = twoPi.divide(fSamples);   // 2π / 200
        FixedArithmetic stepX     = fWidth.divide(fSamples);  // width / 200

        Path2D path = new Path2D.Double();
        for (int i = 0; i <= samples; i++) {
            FixedArithmetic fi    = FixedArithmetic.of(i);
            FixedArithmetic theta = stepTheta.multiply(fi);
            FixedArithmetic xOff  = stepX.multiply(fi);

            double px = cx - toDouble(halfWidth) + toDouble(xOff);
            double py = cy - toDouble(fAmp.multiply(FixedTrigonometry.sin(theta)));

            if (i == 0) path.moveTo(px, py);
            else        path.lineTo(px, py);
        }
        return path;
    }

    /**
     * Builds a polar rose r = cos(k·θ) for the given petal count k.
     *
     * <p>Traced over θ ∈ [0, π] for odd k (one full trace closes the curve),
     * using 360 samples:
     * <pre>
     *   r     = maxR · cos(k · θ)
     *   x     = cx + r · cos(θ)
     *   y     = cy − r · sin(θ)
     * </pre>
     * Negative r values are handled naturally — the point reflects through
     * the origin, producing the correct petal on the opposite side.
     */
    private static Shape polarRose(int k, int maxR, int cx, int cy) {
        int samples = 720;
        FixedArithmetic twoPi     = FixedArithmetic.of("6.283185307");
        FixedArithmetic fK        = FixedArithmetic.of(k);
        FixedArithmetic fMaxR     = FixedArithmetic.of(maxR);
        FixedArithmetic fSamps    = FixedArithmetic.of(samples);

        // Divide first: stepTheta = 2π/720 is small, so stepTheta*i never overflows.
        FixedArithmetic stepTheta = twoPi.divide(fSamps);

        Path2D path = new Path2D.Double();
        for (int i = 0; i <= samples; i++) {
            FixedArithmetic fi    = FixedArithmetic.of(i);
            FixedArithmetic theta = stepTheta.multiply(fi);
            FixedArithmetic r     = fMaxR.multiply(FixedTrigonometry.cos(fK.multiply(theta)));

            double px = cx + toDouble(r.multiply(FixedTrigonometry.cos(theta)));
            double py = cy - toDouble(r.multiply(FixedTrigonometry.sin(theta)));

            if (i == 0) path.moveTo(px, py);
            else        path.lineTo(px, py);
        }
        path.closePath();
        return path;
    }

    /**
     * Builds an Archimedean spiral r = a·θ, winding for 3 full turns.
     *
     * <p>The growth constant {@code a} is chosen so the outermost point
     * reaches {@code maxR} at θ = 3·2π = 6π:
     * <pre>
     *   a = maxR / (3 · 2π)
     *   x = cx + a·θ · cos(θ)
     *   y = cy − a·θ · sin(θ)
     * </pre>
     * Sampled at 540 points (180 per turn) for a smooth curve.
     */
    private static Shape archimedeanSpiral(int maxR, int cx, int cy) {
        int turns          = 3;
        int samplesPerTurn = 180;
        int samples        = turns * samplesPerTurn;

        FixedArithmetic twoPi    = FixedArithmetic.of("6.283185307");
        FixedArithmetic totalAng = twoPi.multiply(FixedArithmetic.of(turns)); // 6π
        // growth constant a = maxR / totalAngle
        FixedArithmetic a        = FixedArithmetic.of(maxR).divide(totalAng);

        // stepTheta = 2π / samplesPerTurn  (≈ 0.0349 — safely < 9.22, no overflow).
        // totalAng / samples would also equal this, but totalAng ≈ 18.85 causes
        // rem × SCALE to overflow Long.MAX_VALUE inside FixedArithmetic.divide.
        FixedArithmetic stepTheta = twoPi.divide(FixedArithmetic.of(samplesPerTurn));

        Path2D path = new Path2D.Double();
        for (int i = 0; i <= samples; i++) {
            FixedArithmetic fi    = FixedArithmetic.of(i);
            FixedArithmetic theta = stepTheta.multiply(fi);  // reaches 6π at i=540
            FixedArithmetic r     = a.multiply(theta);

            double px = cx + toDouble(r.multiply(FixedTrigonometry.cos(theta)));
            double py = cy - toDouble(r.multiply(FixedTrigonometry.sin(theta)));

            if (i == 0) path.moveTo(px, py);
            else        path.lineTo(px, py);
        }
        return path;
    }

    // ── FixedArithmetic → double conversion ───────────────────────────────────

    /**
     * Converts a {@link FixedArithmetic} value to a {@code double} for use
     * with the AWT drawing API.  This is the only place a floating-point value
     * appears; the geometry itself was computed in fixed-point.
     *
     * <p><b>Sign rule:</b> {@code remainder()} always returns a non-negative
     * magnitude (it works on {@code abs(regA)}).  For values whose integer
     * part is zero — i.e. any v ∈ (−1, 0) — {@code integerPart()} also
     * returns 0, giving no signal about the sign.  We therefore read the sign
     * directly from {@code rawScaled()}, which is the unambiguous signed
     * internal register.</p>
     */
    private static double toDouble(FixedArithmetic v) {
        long ip  = v.integerPart();
        long rem = v.remainder();   // always non-negative
        double frac = rem / 1_000_000_000.0;  // PRECISION = 9
        // Use rawScaled() for the sign: integerPart() == 0 for all v in (-1, 0),
        // so "if (ip < 0)" would silently drop the minus sign for those values.
        if (v.rawScaled() < 0) frac = -frac;
        return ip + frac;
    }

    // =========================================================================
    // Painting
    // =========================================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        // ── anti-aliasing ─────────────────────────────────────────────────────
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                            RenderingHints.VALUE_STROKE_PURE);

        // ── background ────────────────────────────────────────────────────────
        g2.setColor(BG);
        g2.fillRect(0, 0, getWidth(), getHeight());

        // ── grid lines ───────────────────────────────────────────────────────
        g2.setColor(GRID_LINE);
        g2.setStroke(new BasicStroke(1.0f));
        for (int col = 1; col < COLS; col++) {
            int x = col * CELL_W;
            g2.drawLine(x, 0, x, getHeight());
        }
        g2.drawLine(0, CELL_H, getWidth(), CELL_H);   // horizontal divider

        // ── shapes ───────────────────────────────────────────────────────────
        BasicStroke stroke = new BasicStroke(STROKE_WIDTH,
            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        Font labelFont = new Font("SansSerif", Font.PLAIN, 11);
        g2.setFont(labelFont);
        FontMetrics fm = g2.getFontMetrics();

        for (ShapeEntry entry : shapes) {
            Color fill   = new Color(
                entry.color.getRed(),
                entry.color.getGreen(),
                entry.color.getBlue(), 55);          // translucent fill
            Color outline = entry.color.brighter();

            // fill
            g2.setColor(fill);
            g2.fill(entry.shape);

            // outline
            g2.setColor(outline);
            g2.setStroke(stroke);
            g2.draw(entry.shape);

            // label — centred at the bottom of each cell
            String label = entry.label;
            int lx = entry.col * CELL_W + (CELL_W  - fm.stringWidth(label)) / 2;
            int ly = entry.row * CELL_H +  CELL_H  - 12;
            g2.setColor(new Color(0xCCCCDD));
            g2.drawString(label, lx, ly);
        }

        // ── title bar ────────────────────────────────────────────────────────
        g2.setColor(new Color(0x33335A));
        g2.fillRect(0, 0, getWidth(), 24);
        g2.setColor(new Color(0xAAAACC));
        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        g2.drawString(
            "FixedShapePanel — geometry computed via FixedArithmetic & FixedTrigonometry",
            10, 16);
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Fixed-Point Shapes");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);

            FixedShapePanel panel = new FixedShapePanel();
            frame.add(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);   // centre on screen
            frame.setVisible(true);
        });
    }
}
