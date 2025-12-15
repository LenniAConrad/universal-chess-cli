package utility;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Minimal image helpers for loading from and writing to byte arrays.
 *
 * @since 2024
 * @author Lennart A. Conrad
 */
public final class Images {

	/**
	 * Hidden constructor to prevent instantiation of this utility class.
	 */
	private Images() {
		// utility holder
	}

	/**
	 * Converts encoded image bytes (PNG/JPEG/etc.) into a {@link BufferedImage}.
	 *
	 * @param array encoded image bytes
	 * @return decoded image or null on error
	 */
	public static BufferedImage bufferedImageFromByteArray(byte[] array) {
		try {
			return ImageIO.read(new ByteArrayInputStream(array));
		} catch (IOException e) {
			throw new IllegalArgumentException("Failed to decode image bytes", e);
		}
	}

	/**
	 * Encodes a {@link BufferedImage} as PNG bytes.
	 *
	 * @param bufferedimage source image
	 * @return encoded bytes or empty array on error
	 */
	public static byte[] bufferedImageToByteArray(BufferedImage bufferedimage) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			ImageIO.write(bufferedimage, "png", out);
			return out.toByteArray();
		} catch (IOException e) {
			return new byte[0];
		}
	}

	/**
	 * Adds a blurred drop shadow around an image.
	 *
	 * @param bufferedimage source image
	 * @param blur          blur kernel size (pixels)
	 * @param color         shadow color (alpha respected)
	 * @return image with shadow padding
	 */
	public static BufferedImage addDropShadow(BufferedImage bufferedimage, int blur, Color color) {
		return addDropShadow(bufferedimage, blur, blur / 2, blur / 2, color);
	}

	/**
	 * Adds a blurred drop shadow around an image with custom offsets.
	 *
	 * @param bufferedimage source image
	 * @param blur          blur kernel size (pixels)
	 * @param xOffset       x offset for the shadow
	 * @param yOffset       y offset for the shadow
	 * @param color         shadow color (alpha respected)
	 * @return image with shadow padding
	 */
	public static BufferedImage addDropShadow(BufferedImage bufferedimage, int blur, int xOffset, int yOffset,
			Color color) {
		int pad = Math.max(1, blur * 2);
		int shadowX = Math.max(0, xOffset);
		int shadowY = Math.max(0, yOffset);
		int outW = bufferedimage.getWidth() + pad + shadowX;
		int outH = bufferedimage.getHeight() + pad + shadowY;

		BufferedImage shadow = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
		int rgb = color.getRGB() & 0x00FFFFFF;
		int alphaColor = color.getAlpha();
		for (int y = 0; y < bufferedimage.getHeight(); y++) {
			for (int x = 0; x < bufferedimage.getWidth(); x++) {
				int a = (bufferedimage.getRGB(x, y) >>> 24);
				if (a == 0) {
					continue;
				}
				int shadowAlpha = (a * alphaColor) / 255;
				int sx = x + blur;
				int sy = y + blur;
				shadow.setRGB(sx, sy, (shadowAlpha << 24) | rgb);
			}
		}

		BufferedImage blurred = applyBoxBlur(shadow, Math.max(1, blur));

		BufferedImage result = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = result.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(blurred, shadowX, shadowY, null);
		g.drawImage(bufferedimage, blur, blur, null);
		g.dispose();
		return result;
	}

	/**
	 * Applies a simple square box blur to the given image.
	 *
	 * @param source source image to blur
	 * @param size   dimension of the blur kernel (pixels)
	 * @return blurred image
	 */
	private static BufferedImage applyBoxBlur(BufferedImage source, int size) {
		float v = 1f / (size * size);
		float[] data = new float[size * size];
		for (int i = 0; i < data.length; i++) {
			data[i] = v;
		}
		Kernel kernel = new Kernel(size, size, data);
		ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
		return op.filter(source, null);
	}

	/**
	 * Wraps an image with a heart-shaped frame using percentage stroke thickness.
	 *
	 * @param bufferedimage source image
	 * @param framecolor    frame color
	 * @param strokePct     stroke thickness relative to min dimension
	 * @return framed image
	 */
	public static BufferedImage heartFrame(BufferedImage bufferedimage, Color framecolor, double strokePct) {
		int stroke = (int) (Math.min(bufferedimage.getWidth(), bufferedimage.getHeight()) * strokePct);
		return heartFrame(bufferedimage, framecolor, Math.max(1, stroke));
	}

	/**
	 * Wraps an image with a heart-shaped frame.
	 *
	 * @param bufferedimage source image
	 * @param framecolor    frame color
	 * @param stroke        stroke thickness in pixels
	 * @return framed image
	 */
	public static BufferedImage heartFrame(BufferedImage bufferedimage, Color framecolor, int stroke) {
		int width = bufferedimage.getWidth();
		int height = bufferedimage.getHeight();
		int half = stroke / 2;

		BufferedImage heart = new BufferedImage(width + stroke, height + stroke, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = heart.createGraphics();
		Path2D.Double path = heartPath(width, height, half, half);

		g2d.setStroke(new java.awt.BasicStroke(
				stroke,
				java.awt.BasicStroke.CAP_BUTT,
				java.awt.BasicStroke.JOIN_MITER));
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2d.setColor(framecolor);
		g2d.setClip(new Area(path));
		g2d.drawImage(bufferedimage, half, half, null);
		g2d.draw(path);
		g2d.dispose();
		return heart;
	}

	/**
	 * Masks an image with a heart-shaped cutout.
	 *
	 * @param bufferedImage source image
	 * @return heart-masked image
	 */
	public static BufferedImage heartCutout(BufferedImage bufferedImage) {
		int width = bufferedImage.getWidth();
		int height = bufferedImage.getHeight();

		BufferedImage heartImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = heartImage.createGraphics();
		Path2D.Double heart = heartPath(width, height, 0, 0);

		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2d.setClip(new Area(heart));
		g2d.drawImage(bufferedImage, 0, 0, null);
		g2d.dispose();
		return heartImage;
	}

	/**
	 * Builds a heart-shaped {@link Path2D} scaled to the given dimensions.
	 *
	 * @param width   target width
	 * @param height  target height
	 * @param xOffset x offset applied to the path
	 * @param yOffset y offset applied to the path
	 * @return heart-shaped path
	 */
	private static Path2D.Double heartPath(int width, int height, int xOffset, int yOffset) {
		Path2D.Double heart = new Path2D.Double();
		double cx = width / 2.0;
		double cy = height / 2.0;
		double offsetY = -(cy * 0.55);

		double left = xOffset + cx - cx;
		double right = xOffset + cx + cx;
		double top = yOffset + cy - (cy * 0.60) + offsetY;
		double midTop = yOffset + cy + offsetY;
		double midBottom = yOffset + cy + (cy * 0.70) + offsetY + (cy * 0.20);
		double bottom = yOffset + cy + (cy * 1.20) - (cy * 0.20);
		double lowerTop = yOffset + cy + (cy * 0.60) + offsetY;

		heart.moveTo(xOffset + cx, midTop);
		heart.curveTo(xOffset + cx, top, left, top, left, midTop);
		heart.curveTo(left, lowerTop, xOffset + cx, midBottom, xOffset + cx, bottom);
		heart.curveTo(xOffset + cx, midBottom, right, lowerTop, right, midTop);
		heart.curveTo(right, top, xOffset + cx, top, xOffset + cx, midTop);

		heart.closePath();
		return heart;
	}
}
