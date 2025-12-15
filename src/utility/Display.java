package utility;

import javax.swing.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Utility class for displaying a {@code BufferedImage} in a {@code JFrame}
 * whilst keeping its aspect ratio.
 * 
 * @implSpec Use {@code System.setProperty("sun.java2d.uiScale", "1.0");} as the
 *           very first command in your {@code main()} method if you want to
 *           remove uiScaling by the operating system
 * 
 * @since 2024
 * @author Lennart A. Conrad
 */
public class Display extends JFrame {

	/**
	 * Default serialVersionUID
	 */
	private static final long serialVersionUID = 1L;

	/**
	 * Panel responsible for rendering the buffered image.
	 */
	private DisplayPanel imageDisplay = null;
	
	/**
	 * Constructor method for {@code Display}
	 * 
	 * @param image  - the {@code BufferedImage} that is to be displayed
	 * @param width  - the width of the {@code JFrame} upon execution
	 * @param height - the height of the {@code JFrame} upon execution
	 * @param light  - if the window will be displayed in light mode
	 */
	public Display(BufferedImage image, int width, int height, boolean light) {
		image = requireImage(image);
		imageDisplay = new DisplayPanel(image, this, light);
		this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		this.setSize(width, height);
		this.add(imageDisplay);
		this.setVisible(true);
		this.setIconImages(buildIconVariants(image));
	}
	
	/**
	 * Constructor method for {@code Display}
	 * 
	 * @param image  - the {@code BufferedImage} that is to be displayed
	 * @param width  - the width of the {@code JFrame} upon execution
	 * @param height - the height of the {@code JFrame} upon execution
	 */
	public Display(BufferedImage image, int width, int height) {
		image = requireImage(image);
		imageDisplay = new DisplayPanel(image, this, true);
		this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		this.setSize(width, height);
		this.add(imageDisplay);
		this.setVisible(true);
		this.setIconImages(buildIconVariants(image));
	}
	
	/**
	 * Used for displaying an image in a 400x400 {@code JFrame} with optional light/dark theme.
	 *
	 * @param image the {@code BufferedImage} to display
	 * @param light whether to display the frame in light mode
	 */
	public Display(BufferedImage image, boolean light) {
		image = requireImage(image);
		imageDisplay = new DisplayPanel(image, this, light);
		this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		this.setSize(400, 400);
		this.add(imageDisplay);
		this.setVisible(true);
		this.setIconImages(buildIconVariants(image));
	}
	
	/**
	 * Constructor method for {@code Display}
	 * 
	 * @param image  - the {@code BufferedImage} that is to be displayed
	 */
	public Display(BufferedImage image) {
		image = requireImage(image);
		imageDisplay = new DisplayPanel(image, this, true);
		this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		this.setSize(400, 400);
		this.add(imageDisplay);
		this.setVisible(true);
		this.setIconImages(buildIconVariants(image));
	}

	/**
	 * Used for updating the displayed image after construction.
	 *
	 * @param image the new {@code BufferedImage} to display
	 * @return this {@code Display} instance for method chaining
	 */
	public Display setImage(BufferedImage image) {
		imageDisplay.setImage(requireImage(image));
		repaint();
		return this;
	}

	/**
	 * Validate that the provided image is non-null, mirroring the null checks used across the constructors.
	 *
	 * @param image image to validate
	 * @return the same image when non-null
	 * @throws NullPointerException if {@code image} is {@code null}
	 */
	private static BufferedImage requireImage(BufferedImage image) {
		return Objects.requireNonNull(image, "image must not be null");
	}
	
	/**
	 * Build multiple high-quality scaled variants of the icon to avoid blurry scaling in the system tray.
	 *
	 * @param image the source icon
	 * @return list of scaled icons from small tray size up to the original dimensions
	 */
	private static List<Image> buildIconVariants(BufferedImage image) {
		List<Image> icons = new ArrayList<>();
		if (image == null) {
			return icons;
		}
		int[] targetSizes = {16, 24, 32, 48, 64, 128};
		for (int size : targetSizes) {
			icons.add(scaleImage(image, size, size));
		}
		icons.add(image);
		return icons;
	}

	/**
	 * Scale an image with high quality interpolation.
	 *
	 * @param source the image to scale
	 * @param width  target width
	 * @param height target height
	 * @return a new {@link BufferedImage} of the desired size
	 */
	private static BufferedImage scaleImage(BufferedImage source, int width, int height) {
		BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = scaled.createGraphics();
		g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2d.drawImage(source, 0, 0, width, height, null);
		g2d.dispose();
		return scaled;
	}
	
	/**
	 * New {@code JPanel} class that is only used for drawing the centered
	 * {@code BufferedImage}.
	 */
	private class DisplayPanel extends JPanel {

		/**
		 * Used for defining light mode background color.
		 */
		private static final Color LIGHT_BACKGROUND = new Color(255, 255, 255);
		
		/**
		 * Used for defining light mode border color.
		 */
		private static final Color LIGHT_BORDER = new Color(192, 192, 192);
		
		/**
		 * Used for defining light mode transparent tile color 1.
		 */
		private static final Color LIGHT_TRANSPARENT_1 = new Color(230, 230, 230);
		
		/**
		 * Used for defining light mode transparent tile color 2.
		 */
		private static final Color LIGHT_TRANSPARENT_2 = new Color(245, 245, 245);
		
		/**
		 * Used for defining dark mode background color.
		 */
		private static final Color DARK_BACKGROUND = new Color(25, 25, 25);
		
		/**
		 * Used for defining dark mode border color.
		 */
		private static final Color DARK_BORDER = new Color(75, 75, 75);
		
		/**
		 * Used for defining dark mode transparent tile color 1.
		 */
		private static final Color DARK_TRANSPARENT_1 = new Color(100, 100, 100);
		
		/**
		 * Used for defining dark mode transparent tile color 2.
		 */
		private static final Color DARK_TRANSPARENT_2 = new Color(150, 150, 150);
		
		/**
		 * Pixel size for the transparent background tiles
		 */
		private static final int TILESIZE = 5;
		
		/**
		 * A timestamp indicating when the application was launched
		 */
		private String timestamp = Dates.getTimestamp();

		/**
		 * Default serialVersionUID
		 */
		private static final long serialVersionUID = 1L;

		/**
		 * The image to be displayed
		 */
		private transient BufferedImage image;

		/**
		 * The {@code JFrame} that the image is going to be displayed in
		 */
		private JFrame jframe;

		/**
		 * The width of our image that is to be rendered
		 */
		private int imageWidth = 0;

		/**
		 * The height of our image that is to be rendered
		 */
		private int imageHeight = 0;
		
		/**
		 * Cached scaled image to avoid resampling on every repaint.
		 */
		private transient BufferedImage scaledImageCache = null;

		/**
		 * Cached scaled image width.
		 */
		private int scaledWidth = -1;

		/**
		 * Cached scaled image height.
		 */
		private int scaledHeight = -1;

		/**
		 * Reusable checkerboard texture for transparent background.
		 */
		private transient TexturePaint checkerPaint;
		
		/**
		 * Used for holding the background color depending on light/dark mode.
		 */
		private Color backgroundColor = null;

		/**
		 * Used for holding the border color depending on light/dark mode.
		 */
		private Color borderColor = null;

		/**
		 * Used for holding the first transparent tile color depending on light/dark mode.
		 */
		private Color transparentColor1 = null;

		/**
		 * Used for holding the second transparent tile color depending on light/dark mode.
		 */
		private Color transparentColor2 = null;
		
		/**
		 * Constructor method for {@code DisplayPanel}
		 * 
		 * @param image - the {@code BufferedImage} that is to be displayed
		 */
		public DisplayPanel(BufferedImage image, JFrame jframe, boolean light) {
			this.image = image;
			this.jframe = jframe;
			this.imageWidth = image.getWidth();
			this.imageHeight = image.getHeight();
			if(light) {
				backgroundColor = LIGHT_BACKGROUND;
				borderColor = LIGHT_BORDER;
				transparentColor1 = LIGHT_TRANSPARENT_1;
				transparentColor2 = LIGHT_TRANSPARENT_2;
			} else {
				backgroundColor = DARK_BACKGROUND;
				borderColor = DARK_BORDER;
				transparentColor1 = DARK_TRANSPARENT_1;
				transparentColor2 = DARK_TRANSPARENT_2;
			}
			checkerPaint = buildCheckerPaint();
		}

		/**
		 * Builds a small checkerboard pattern used as the texture paint for transparent backgrounds.
		 *
		 * @return a reusable {@link TexturePaint} containing the checkerboard tiles
		 */
		private TexturePaint buildCheckerPaint() {
			BufferedImage pattern = new BufferedImage(TILESIZE * 2, TILESIZE * 2, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2d = pattern.createGraphics();
			g2d.setColor(transparentColor1);
			g2d.fillRect(0, 0, TILESIZE * 2, TILESIZE * 2);
			g2d.setColor(transparentColor2);
			g2d.fillRect(0, 0, TILESIZE, TILESIZE);
			g2d.fillRect(TILESIZE, TILESIZE, TILESIZE, TILESIZE);
			g2d.dispose();
			return new TexturePaint(pattern, new Rectangle(0, 0, TILESIZE * 2, TILESIZE * 2));
		}

		/**
		 * Updates the image reference and resets cached scaled data.
		 *
		 * @param image the new {@code BufferedImage} to display
		 */
		private void setImage(BufferedImage image) {
			this.image = image;
			this.imageWidth = image.getWidth();
			this.imageHeight = image.getHeight();
			// Invalidate cache so next paint recalculates scale.
			this.scaledWidth = -1;
			this.scaledHeight = -1;
			this.scaledImageCache = null;
		}

		/**
		 * Draws the image centered and keeps the aspect ratio
		 */
		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);
			Graphics2D graphics2d = (Graphics2D) g;
			graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			if (image != null) {
				int frameWidth = getWidth();
				int frameHeight = getHeight();
				double scaleFactor = Math.min(frameWidth / (double) imageWidth, frameHeight / (double) imageHeight);
				int newWidth = (int) (imageWidth * scaleFactor);
				int newHeight = (int) (imageHeight * scaleFactor);
				int x = (int) ((frameWidth - newWidth) / 2.0);
				int y = (int) ((frameHeight - newHeight) / 2.0);
				if (newWidth > 0 && newHeight > 0 && (newWidth != scaledWidth || newHeight != scaledHeight)) {
					BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_ARGB);
					Graphics2D ig2 = scaled.createGraphics();
					ig2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					ig2.drawImage(image, 0, 0, newWidth, newHeight, this);
					ig2.dispose();
					scaledImageCache = scaled;
					scaledWidth = newWidth;
					scaledHeight = newHeight;
					jframe.setTitle("Image Display (" + newWidth + "x" + newHeight + ") " + timestamp);
				}
				graphics2d.setColor(backgroundColor);
				graphics2d.fillRect(0, 0, frameWidth, frameHeight);
				if (checkerPaint == null) {
					checkerPaint = buildCheckerPaint();
				}
				graphics2d.setPaint(checkerPaint);
				graphics2d.fillRect(x, y, newWidth, newHeight);
				graphics2d.setPaint(null);
				graphics2d.setColor(borderColor);
				graphics2d.drawRect(x - 1, y - 1, newWidth + 2, newHeight + 2);
				if (scaledImageCache != null) {
					graphics2d.drawImage(scaledImageCache, x, y, this);
				}
			}
		}
	}
}
