package chess.mlp;

import java.util.Objects;

import chess.core.Position;

/**
 * Minimal Java forward-pass for the dual-head MLP used in training.
 *
 * This class takes a 781-float feature vector (same spec as RecordDatasetExporter.java /
 * Python pipeline) and produces both a scalar evaluation and a flattened 8x8 grid.
 *
 * You must supply trained weights and biases (e.g., exported from the PyTorch checkpoint).
 * Shapes for the 1024/768/512 trunk ("wide" model):
 *   w1: [1024][781], b1: [1024]
 *   w2: [768][1024], b2: [768]
 *   w3: [512][768], b3: [512]
 *   headScalar: wS [1][512], bS [1]
 *   headGrid:   wG [64][512], bG [64]
 *
 * The grid output is a 64-length vector; reshape to 8x8 (rank 8 -> 1) as needed.
 * Typical use: {@link chess.mlp.WeightsLoader#load(java.nio.file.Path)} with
 * {@code models/mlp_wide.bin}, then call {@link #evaluate(Position)}.
 * 
 * @author Lennart A. Conrad
 */
public final class Evaluator {

    /**
     * Container for the scalar and grid outputs produced by the MLP.
     */
    public static final class Result {
        /**
         * Scalar evaluation in network output units (same as training target).
         */
        public final float scalarEval;
        
        /**
         * Flattened 8x8 grid output (length 64, A8 index 0).
         */
        public final float[] grid64; // length 64

        /**
         * Construct a result bundle.
         * @param scalarEval scalar output
         * @param grid64 flattened 8x8 grid (length 64)
         */
        public Result(float scalarEval, float[] grid64) {
            this.scalarEval = scalarEval;
            this.grid64 = grid64;
        }
    }

    /**
     * First dense layer weights [1024 x 781].
     */
    private final float[][] w1;
    
    /**
     * First dense layer biases [1024].
     */
    private final float[] b1;
    
    /**
     * Second dense layer weights [768 x 1024].
     */
    private final float[][] w2;
    
    /**
     * Second dense layer biases [768].
     */
    private final float[] b2;
    
    /**
     * Third dense layer weights [512 x 768].
     */
    private final float[][] w3;
    
    /**
     * Third dense layer biases [512].
     */
    private final float[] b3;
    
    /**
     * Scalar head weights [1 x 512].
     */
    private final float[][] wScalar;
    
    /**
     * Scalar head bias [1].
     */
    private final float[] bScalar;
    
    /**
     * Grid head weights [64 x 512].
     */
    private final float[][] wGrid;
    
    /**
     * Grid head biases [64].
     */
    private final float[] bGrid;

    /**
     * Pair of weights and bias for a dense layer.
     */
    public static final class LayerParams {

        /**
         * Weight matrix for the layer (rows correspond to output units).
         */
        public final float[][] weights;

        /**
         * Bias vector added after the matrix multiplication.
         */
        public final float[] bias;

        /**
         * Bundle weights and bias belonging to a single dense layer.
         *
         * @param weights weight matrix (rows = outputs)
         * @param bias bias vector
         */
        public LayerParams(float[][] weights, float[] bias) {
            this.weights = weights;
            this.bias = bias;
        }
    }

    /**
     * Build an evaluator with explicit weights and biases.
     *
     * @param l1 first layer parameters
     * @param l2 second layer parameters
     * @param l3 third layer parameters
     * @param scalar scalar head parameters
     * @param grid grid head parameters
     */
    public Evaluator(LayerParams l1, LayerParams l2, LayerParams l3, LayerParams scalar, LayerParams grid) {
        this.w1 = l1.weights;
        this.b1 = l1.bias;
        this.w2 = l2.weights;
        this.b2 = l2.bias;
        this.w3 = l3.weights;
        this.b3 = l3.bias;
        this.wScalar = scalar.weights;
        this.bScalar = scalar.bias;
        this.wGrid = grid.weights;
        this.bGrid = grid.bias;
    }

    /**
     * Evaluate a chess position by encoding it and running the MLP.
     *
     * @param position position to score
     * @return scalar + grid outputs
     */
    public Result evaluate(Position position) {
        Objects.requireNonNull(position, "position");
        float[] features = Encoder.encode(position);
        return evaluate(features);
    }

    /**
     * Evaluate a pre-encoded feature vector.
     *
     * @param features781 781-length input vector
     * @return scalar + grid outputs
     */
    public Result evaluate(float[] features781) {
        if (features781.length != 781) {
            throw new IllegalArgumentException("Expected 781 features, got " + features781.length);
        }
        float[] h1 = denseRelu(features781, w1, b1);
        float[] h2 = denseRelu(h1, w2, b2);
        float[] h3 = denseRelu(h2, w3, b3);
        float scalar = dense(h3, wScalar, bScalar)[0];
        float[] grid = dense(h3, wGrid, bGrid); // length 64
        return new Result(scalar, grid);
    }

    /**
     * Dense layer followed by ReLU activation.
     *
     * @param x input vector
     * @param w weight matrix
     * @param b bias vector
     * @return activated output
     */
    private static float[] denseRelu(float[] x, float[][] w, float[] b) {
        float[] out = dense(x, w, b);
        for (int i = 0; i < out.length; i++) {
            if (out[i] < 0f) out[i] = 0f;
        }
        return out;
    }

    /**
     * Plain dense layer y = Wx + b.
     *
     * @param x input vector
     * @param w weight matrix (rows = outputs)
     * @param b bias vector
     * @return output vector
     */
    private static float[] dense(float[] x, float[][] w, float[] b) {
        int outDim = w.length;
        int inDim = x.length;
        float[] out = new float[outDim];
        for (int o = 0; o < outDim; o++) {
            float sum = b[o];
            float[] row = w[o];
            for (int i = 0; i < inDim; i++) {
                sum += row[i] * x[i];
            }
            out[o] = sum;
        }
        return out;
    }
}
