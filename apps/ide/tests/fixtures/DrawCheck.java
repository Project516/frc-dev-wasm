import java.awt.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.zip.CRC32;

public class DrawCheck {
  static long crc(BufferedImage img) {
    CRC32 c = new CRC32();
    for (int y = 0; y < img.getHeight(); y++)
      for (int x = 0; x < img.getWidth(); x++) {
        int p = img.getRGB(x, y);
        c.update(p >>> 24); c.update(p >>> 16); c.update(p >>> 8); c.update(p);
      }
    return c.getValue();
  }

  static void scene(Graphics2D g) {
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 120, 80);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setPaint(new GradientPaint(0, 0, Color.RED, 120, 80, Color.BLUE));
    g.fill(new Ellipse2D.Double(10, 5, 70, 50));
    g.setPaint(new Color(0, 160, 0, 128));
    g.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g.draw(new QuadCurve2D.Double(5, 75, 60, -20, 115, 70));
    g.rotate(0.3, 60, 40);
    g.setColor(Color.ORANGE);
    g.fillRect(70, 30, 30, 20);
  }

  public static void main(String[] args) {
    // must match HotSpot exactly
    int[] types = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_BYTE_INDEXED};
    String[] names = {"INT_ARGB", "INT_RGB", "3BYTE_BGR", "BYTE_GRAY", "BYTE_INDEXED"};
    for (int i = 0; i < types.length; i++) {
      BufferedImage img = new BufferedImage(120, 80, types[i]);
      Graphics2D g = img.createGraphics();
      scene(g);
      g.dispose();
      System.out.println("shapes " + names[i] + " " + Long.toHexString(crc(img)));
    }
    // must match HotSpot exactly: metrics with fractional metrics on
    BufferedImage img = new BufferedImage(200, 60, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 20);
    FontRenderContext frc = g.getFontRenderContext();
    LineMetrics lm = f.getLineMetrics("Hello", frc);
    System.out.println("font " + f.getFontName() + " ascent " + lm.getAscent() + " descent " + lm.getDescent() + " width " + f.getStringBounds("Hello, robot", frc).getWidth());
    // java-web's own text pixels (they differ from HotSpot's by design)
    g.setColor(Color.BLACK);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setFont(f);
    g.drawString("Hello, robot", 10, 35);
    g.dispose();
    System.out.println("text " + Long.toHexString(crc(img)));
  }
}
