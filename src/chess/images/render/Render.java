package chess.images.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import chess.images.assets.Pictures;
import chess.core.Field;
import chess.core.Move;
import chess.core.MoveList;
import chess.core.Piece;
import chess.core.Position;
import chess.model.Game;

/**
 * Lightweight board renderer for {@link Position} objects with optional arrows
 * and circles.
 *
 * @since 2024
 * @author Lennart A. Conrad
 */
public final class Render {

	/**
	 * Default fill color for arrows.
	 */
	private static final Color DEFAULT_ARROW_FILL = new Color(1.0f, 1.0f, 1.0f, 1.0f);
	
	/**
	 * Default outline color for arrows.
	 */
	private static final Color DEFAULT_ARROW_BORDER = new Color(0.0f, 0.0f, 0.0f, 1.0f);

	/**
	 * Default stroke for arrow outlines.
	 */
	private static final Stroke DEFAULT_ARROW_STROKE = new BasicStroke(3f);
	
	/**
	 * Default fill color for hint circles.
	 */
	private static final Color DEFAULT_CIRCLE_FILL = new Color(1.0f, 1.0f, 1.0f, 0.7f);

	/**
	 * Default outline color for hint circles.
	 */
	private static final Color DEFAULT_CIRCLE_BORDER = new Color(0.0f, 0.0f, 0.0f, 1.0f);

	/**
	 * Default stroke for hint circles.
	 */
	private static final Stroke DEFAULT_CIRCLE_STROKE = new BasicStroke(2f);
	
	/**
	 * Default frame color surrounding the board. 
	 */
	private static final Color DEFAULT_FRAME = new Color(100, 100, 100);

	/**
	 * Board pixel width taken from the background asset.
	 */
	private final int boardWidth = Pictures.Board.getWidth();
	
	/**
	 * Board pixel height taken from the background asset.
	 */
	private final int boardHeight = Pictures.Board.getHeight();
	
	/**
	 * Single tile width in pixels.
	 */
	private final int tileWidth = boardWidth / 8;

	/**
	 * Single tile height in pixels.
	 */
	private final int tileHeight = boardHeight / 8;
	
	/**
	 * Thickness of the optional border in pixels.
	 */
	private final int borderThickness = Math.max(2, tileWidth / 10);

	/**
	 * Position currently being rendered.
	 */
	private Position position = new Position(Game.STANDARD_START_FEN);
	
	/**
	 * Whether White is at the bottom of the board.
	 */
	private boolean whiteSideDown = true;
	
	/**
	 * Whether a frame/border should be drawn.
	 */
	private boolean showBorder = true;

	/** 
	 * Overlay arrows to draw.
	 */
	private final List<Arrow> arrows = new ArrayList<>();
	
	/**
	 * Overlay circles to draw
	 */
	private final List<Circle> circles = new ArrayList<>();

	/**
	 * Sets the position to render.
	 *
	 * @param position position to draw
	 * @return this renderer for chaining
	 */
	public Render setPosition(Position position) {
		if (position == null) {
			throw new IllegalArgumentException("position cannot be null");
		}
		this.position = position;
		return this;
	}

	/**
	 * Controls board orientation.
	 *
	 * @param value true for White at bottom, false for Black
	 * @return this renderer for chaining
	 */
	public Render setWhiteSideDown(boolean value) {
		this.whiteSideDown = value;
		return this;
	}

	/**
	 * Toggles drawing of the surrounding frame.
	 *
	 * @param value true to draw a frame
	 * @return this renderer for chaining
	 */
	public Render setShowBorder(boolean value) {
		this.showBorder = value;
		return this;
	}

	/**
	 * Removes all arrows.
	 *
	 * @return this renderer for chaining
	 */
	public Render clearArrows() {
		arrows.clear();
		return this;
	}

	/**
	 * Removes all circles.
	 *
	 * @return this renderer for chaining
	 */
	public Render clearCircles() {
		circles.clear();
		return this;
	}

	/**
	 * Adds an arrow with default styling.
	 *
	 * @param move encoded move
	 * @return this renderer for chaining
	 */
	public Render addArrow(short move) {
		return addArrow(move, DEFAULT_ARROW_BORDER, DEFAULT_ARROW_FILL, DEFAULT_ARROW_STROKE, 0.25, 0.25, 0.4);
	}

	/**
	 * Adds an arrow with custom styling.
	 *
	 * @param move           encoded move
	 * @param border         outline color
	 * @param fill           fill color
	 * @param stroke         outline stroke
	 * @param startShortener proportion of tile width trimmed from start
	 * @param endShortener   proportion of tile width trimmed from end
	 * @param headHeight     head height in tiles
	 * @return this renderer for chaining
	 */
	public Render addArrow(short move, Color border, Color fill, Stroke stroke, double startShortener,
			double endShortener, double headHeight) {
		int head = (int) (tileWidth * headHeight);
		int start = (int) (tileWidth * startShortener);
		int end = (int) (tileWidth * endShortener);
		arrows.add(new Arrow(move, head, start, end, border, fill, stroke));
		return this;
	}

	/**
	 * Adds a circle with default styling.
	 *
	 * @param index target square index
	 * @return this renderer for chaining
	 */
	public Render addCircle(byte index) {
		return addCircle(index, 0.5, DEFAULT_CIRCLE_BORDER, DEFAULT_CIRCLE_FILL, DEFAULT_CIRCLE_STROKE);
	}

	/**
	 * Adds a circle with custom styling.
	 *
	 * @param index         target square index
	 * @param diameterTiles circle diameter in tiles
	 * @param border        outline color
	 * @param fill          fill color
	 * @param stroke        outline stroke
	 * @return this renderer for chaining
	 */
	public Render addCircle(byte index, double diameterTiles, Color border, Color fill, Stroke stroke) {
		int diameter = (int) (tileWidth * diameterTiles);
		circles.add(new Circle(index, diameter, border, fill, stroke));
		return this;
	}

	/**
	 * Adds circles for every legal move from a square.
	 *
	 * @param pos       source position
	 * @param fromIndex origin square index
	 * @return this renderer for chaining
	 */
	public Render addLegalMoves(Position pos, byte fromIndex) {
		if (pos == null) {
			return this;
		}
		MoveList list = pos.getMoves();
		for (int i = 0; i < list.size(); i++) {
			short m = list.get(i);
			if (Move.getFromIndex(m) == fromIndex) {
				addCircle(Move.getToIndex(m));
			}
		}
		return this;
	}

	/**
	 * Renders the configured position with overlays into a new image.
	 *
	 * @return rendered board image
	 */
	public BufferedImage render() {
		BufferedImage img = new BufferedImage(
				boardWidth + (showBorder ? borderThickness * 2 : 0),
				boardHeight + (showBorder ? borderThickness * 2 : 0),
				BufferedImage.TYPE_INT_ARGB);

		int boardX = showBorder ? borderThickness : 0;
		int boardY = showBorder ? borderThickness : 0;

		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		if (showBorder) {
			g.setPaint(DEFAULT_FRAME);
			g.fillRect(0, 0, img.getWidth(), img.getHeight());
		}

		g.drawImage(Pictures.Board, boardX, boardY, null);
		drawPieces(g, boardX, boardY);
		drawCircles(g, boardX, boardY);
		drawArrows(g, boardX, boardY);

		g.dispose();
		return img;
	}

	/**
	 * Draws pieces to the board.
	 *
	 * @param g      graphics context
	 * @param boardX board origin x
	 * @param boardY board origin y
	 */
	private void drawPieces(Graphics2D g, int boardX, int boardY) {
		byte[] board = position.getBoard();
		for (int i = 0; i < board.length; i++) {
			int x = whiteSideDown ? Field.getX((byte) i) : Field.getXInverted((byte) i);
			int y = whiteSideDown ? Field.getYInverted((byte) i) : Field.getY((byte) i);
			BufferedImage img = imageForPiece(board[i]);
			if (img != null) {
				g.drawImage(img, boardX + x * tileWidth, boardY + y * tileHeight, null);
			}
		}
	}

	/**
	 * Draws circles overlaying target squares.
	 *
	 * @param g      graphics context
	 * @param boardX board origin x
	 * @param boardY board origin y
	 */
	private void drawCircles(Graphics2D g, int boardX, int boardY) {
		for (Circle c : circles) {
			int x = whiteSideDown ? Field.getX(c.index) : Field.getXInverted(c.index);
			int y = whiteSideDown ? Field.getYInverted(c.index) : Field.getY(c.index);
			int px = boardX + x * tileWidth + (tileWidth - c.diameter) / 2;
			int py = boardY + y * tileHeight + (tileHeight - c.diameter) / 2;
			g.setStroke(c.stroke);
			g.setPaint(c.fill);
			g.fillOval(px, py, c.diameter, c.diameter);
			g.setPaint(c.border);
			g.drawOval(px, py, c.diameter, c.diameter);
		}
	}

	/**
	 * Draws arrows overlaying the board.
	 *
	 * @param g      graphics context
	 * @param boardX board origin x
	 * @param boardY board origin y
	 */
	private void drawArrows(Graphics2D g, int boardX, int boardY) {
		for (Arrow arrow : arrows) {
			Polygon poly = arrow.polygon(boardX, boardY, tileWidth, tileHeight, whiteSideDown);
			g.setStroke(arrow.stroke);
			g.setPaint(arrow.fillColor);
			g.fillPolygon(poly);
			g.setPaint(arrow.borderColor);
			g.drawPolygon(poly);
		}
	}

	/**
	 * Maps a piece code to its corresponding image.
	 *
	 * @param piece piece code from {@link Piece}
	 * @return image or null when empty
	 */
	private static BufferedImage imageForPiece(byte piece) {
		return switch (piece) {
			case Piece.BLACK_BISHOP -> Pictures.BlackBishop;
			case Piece.BLACK_KING -> Pictures.BlackKing;
			case Piece.BLACK_KNIGHT -> Pictures.BlackKnight;
			case Piece.BLACK_PAWN -> Pictures.BlackPawn;
			case Piece.BLACK_QUEEN -> Pictures.BlackQueen;
			case Piece.BLACK_ROOK -> Pictures.BlackRook;
			case Piece.WHITE_BISHOP -> Pictures.WhiteBishop;
			case Piece.WHITE_KING -> Pictures.WhiteKing;
			case Piece.WHITE_KNIGHT -> Pictures.WhiteKnight;
			case Piece.WHITE_PAWN -> Pictures.WhitePawn;
			case Piece.WHITE_QUEEN -> Pictures.WhiteQueen;
			case Piece.WHITE_ROOK -> Pictures.WhiteRook;
			default -> null;
		};
	}

	/**
	 * Circle overlay state.
	 *
	 * @param index    target square index
	 * @param diameter circle diameter in pixels
	 * @param border   outline color
	 * @param fill     fill color
	 * @param stroke   outline stroke
	 */
	private record Circle(byte index, int diameter, Color border, Color fill, Stroke stroke) {
	}
}
