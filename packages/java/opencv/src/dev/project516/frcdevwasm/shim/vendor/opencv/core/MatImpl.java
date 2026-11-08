package dev.project516.frcdevwasm.shim.vendor.opencv.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * org.opencv.core.Mat without the native library: two-dimensional matrices in Java arrays. A Mat
 * handle is a key into a table. Views (submat, row, col, ranges) are copies, so writes to them do
 * not reach the parent.
 */
public final class MatImpl {
  private MatImpl() {}

  public static final int CV_8U = 0;
  public static final int CV_8S = 1;
  public static final int CV_16U = 2;
  public static final int CV_16S = 3;
  public static final int CV_32S = 4;
  public static final int CV_32F = 5;
  public static final int CV_64F = 6;

  private static final int[] DEPTH_BYTES = {1, 1, 2, 2, 4, 4, 8};

  /** One matrix: rows x cols elements of {@code type}, row-major, little endian. */
  public static final class Data {
    public int rows;
    public int cols;
    public int type;
    private Store store = new Store(0);
    private ByteBuffer buffer;

    /**
     * The bytes that Mats sharing data have in common. The array is made on first write: CheerpJ runs no
     * finalizers, so a Mat is never deleted there, and a zero Mat that nothing writes then costs no memory.
     */
    private static final class Store {
      final int length;
      byte[] bytes;

      Store(int length) {
        this.length = length;
      }
    }

    public int channels() {
      return ((type >> 3) & 63) + 1;
    }

    public int depth() {
      return type & 7;
    }

    public int elemSize1() {
      return DEPTH_BYTES[depth()];
    }

    public int elemSize() {
      return elemSize1() * channels();
    }

    public boolean empty() {
      return rows == 0 || cols == 0;
    }

    /** Elements over all channels. */
    public int size() {
      return rows * cols * channels();
    }

    public void alloc(int newRows, int newCols, int newType) {
      if (newRows < 0 || newCols < 0) {
        throw new IllegalArgumentException("negative Mat size");
      }
      if (rows == newRows && cols == newCols && type == newType) {
        return;
      }
      rows = newRows;
      cols = newCols;
      type = newType;
      store = new Store(Math.multiplyExact(Math.multiplyExact(rows, cols), elemSize()));
      buffer = null;
    }

    /** True while every byte is zero and no array is allocated. */
    public boolean zero() {
      return store.bytes == null;
    }

    private byte[] bytes() {
      if (store.bytes == null) {
        store.bytes = new byte[store.length];
      }
      return store.bytes;
    }

    private void setBytes(byte[] bytes) {
      store = new Store(bytes.length);
      store.bytes = bytes;
      buffer = null;
    }

    public void share(Data other) {
      rows = other.rows;
      cols = other.cols;
      type = other.type;
      store = other.store;
      buffer = null;
    }

    private ByteBuffer buffer() {
      if (buffer == null) {
        buffer = ByteBuffer.wrap(bytes()).order(ByteOrder.LITTLE_ENDIAN);
      }
      return buffer;
    }

    /** Element {@code index} (row-major over all channels) as a double. */
    public double get(int index) {
      if (zero()) {
        return 0;
      }
      ByteBuffer b = buffer();
      byte[] bytes = store.bytes;
      int at = index * elemSize1();
      return switch (depth()) {
        case CV_8U -> bytes[at] & 0xff;
        case CV_8S -> bytes[at];
        case CV_16U -> b.getShort(at) & 0xffff;
        case CV_16S -> b.getShort(at);
        case CV_32S -> b.getInt(at);
        case CV_32F -> b.getFloat(at);
        default -> b.getDouble(at);
      };
    }

    /** Stores like saturate_cast: rounded and clamped for integer types. */
    public void set(int index, double value) {
      ByteBuffer b = buffer();
      byte[] bytes = store.bytes;
      int at = index * elemSize1();
      switch (depth()) {
        case CV_8U -> bytes[at] = (byte) clamp(value, 0, 255);
        case CV_8S -> bytes[at] = (byte) clamp(value, -128, 127);
        case CV_16U -> b.putShort(at, (short) clamp(value, 0, 65535));
        case CV_16S -> b.putShort(at, (short) clamp(value, -32768, 32767));
        case CV_32S -> b.putInt(at, (int) clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE));
        case CV_32F -> b.putFloat(at, (float) value);
        default -> b.putDouble(at, value);
      }
    }

    public double get(int row, int col, int channel) {
      return get((row * cols + col) * channels() + channel);
    }

    public void set(int row, int col, int channel, double value) {
      set((row * cols + col) * channels() + channel, value);
    }

    private static double clamp(double value, double low, double high) {
      if (Double.isNaN(value)) {
        return 0;
      }
      return Math.max(low, Math.min(high, Math.rint(value)));
    }

    public Data copy() {
      Data d = new Data();
      d.rows = rows;
      d.cols = cols;
      d.type = type;
      d.store = new Store(store.length);
      if (!zero()) {
        d.store.bytes = store.bytes.clone();
      }
      return d;
    }
  }

  public static final Map<Long, Data> MATS = new ConcurrentHashMap<>();
  public static final AtomicLong NEXT = new AtomicLong(1);

  public static int type(int depth, int channels) {
    return depth + ((channels - 1) << 3);
  }

  /** The matrix behind a handle, or a fresh empty one for a handle that is gone. */
  public static Data data(long handle) {
    Data d = MATS.get(handle);
    return d != null ? d : new Data();
  }

  public static long register(Data data) {
    long handle = NEXT.getAndIncrement();
    MATS.put(handle, data);
    return handle;
  }

  /** The elements of a Mat as points of {@code dims} coordinates each. */
  public static double[][] points(Data d, int dims) {
    double[][] out = new double[d.size() / dims][dims];
    for (int i = 0; i < out.length; i++) {
      for (int k = 0; k < dims; k++) {
        out[i][k] = d.get(i * dims + k);
      }
    }
    return out;
  }

  /** A column of points, as OpenCV makes a point vector: one row each, {@code dims} channels of {@code depth}. */
  public static Data pointColumn(double[][] points, int depth, int dims) {
    Data d = new Data();
    d.alloc(points.length, 1, type(depth, dims));
    for (int i = 0; i < points.length; i++) {
      for (int k = 0; k < dims; k++) {
        d.set(i * dims + k, points[i][k]);
      }
    }
    return d;
  }

  public static long create(int rows, int cols, int type) {
    Data d = new Data();
    d.alloc(rows, cols, type);
    return register(d);
  }

  public static Data filled(int rows, int cols, int type, double... scalar) {
    Data d = new Data();
    d.alloc(rows, cols, type);
    int channels = d.channels();
    for (int i = 0; i < d.size(); i++) {
      double v = scalar[i % channels < scalar.length ? i % channels : 0];
      if (v != 0) {
        d.set(i, v);
      }
    }
    return d;
  }

  public static long viewOf(Data source, int rowStart, int rowEnd, int colStart, int colEnd) {
    // Range.all() is MIN_VALUE to MAX_VALUE
    rowStart = Math.max(0, rowStart);
    colStart = Math.max(0, colStart);
    int rows = Math.max(0, Math.min(rowEnd, source.rows) - rowStart);
    int cols = Math.max(0, Math.min(colEnd, source.cols) - colStart);
    Data d = new Data();
    d.alloc(rows, cols, source.type);
    int rowBytes = cols * source.elemSize();
    for (int r = 0; r < rows && !source.zero(); r++) {
      System.arraycopy(source.bytes(), ((rowStart + r) * source.cols + colStart) * source.elemSize(), d.bytes(), r * rowBytes, rowBytes);
    }
    return register(d);
  }

  // Constructors

  public static long n_Mat() {
    return register(new Data());
  }

  public static long n_Mat(int rows, int cols, int type) {
    return create(rows, cols, type);
  }

  public static long n_Mat(double width, double height, int type) {
    return create((int) height, (int) width, type);
  }

  public static long n_Mat(int rows, int cols, int type, double v0, double v1, double v2, double v3) {
    return register(filled(rows, cols, type, v0, v1, v2, v3));
  }

  public static long n_Mat(double width, double height, int type, double v0, double v1, double v2, double v3) {
    return register(filled((int) height, (int) width, type, v0, v1, v2, v3));
  }

  public static long n_Mat(int rows, int cols, int type, ByteBuffer data) {
    Data d = new Data();
    d.alloc(rows, cols, type);
    ByteBuffer copy = data.duplicate();
    byte[] bytes = d.bytes();
    copy.get(bytes, 0, Math.min(bytes.length, copy.remaining()));
    return register(d);
  }

  public static long n_Mat(long m, int rowStart, int rowEnd, int colStart, int colEnd) {
    return viewOf(data(m), rowStart, rowEnd, colStart, colEnd);
  }

  public static long n_Mat(long m, int rowStart, int rowEnd) {
    Data source = data(m);
    return viewOf(source, rowStart, rowEnd, 0, source.cols);
  }

  public static void n_delete(long handle) {
    MATS.remove(handle);
  }

  public static void n_release(long handle) {
    Data d = data(handle);
    d.rows = 0;
    d.cols = 0;
    d.setBytes(new byte[0]);
  }

  // Header

  public static int n_rows(long m) {
    return data(m).rows;
  }

  public static int n_cols(long m) {
    return data(m).cols;
  }

  public static int n_type(long m) {
    return data(m).type;
  }

  public static int n_channels(long m) {
    return data(m).channels();
  }

  public static int n_depth(long m) {
    return data(m).depth();
  }

  public static int n_dims(long m) {
    return data(m).empty() ? 0 : 2;
  }

  public static boolean n_empty(long m) {
    return data(m).empty();
  }

  public static long n_total(long m) {
    Data d = data(m);
    return (long) d.rows * d.cols;
  }

  public static long n_elemSize(long m) {
    return data(m).elemSize();
  }

  public static long n_elemSize1(long m) {
    return data(m).elemSize1();
  }

  public static double[] n_size(long m) {
    Data d = data(m);
    return new double[] {d.cols, d.rows};
  }

  public static int n_size_i(long m, int i) {
    Data d = data(m);
    return i == 0 ? d.rows : i == 1 ? d.cols : 0;
  }

  public static long n_step1(long m) {
    Data d = data(m);
    return (long) d.cols * d.channels();
  }

  public static long n_step1(long m, int i) {
    Data d = data(m);
    return i == 0 ? (long) d.cols * d.channels() : i == 1 ? d.channels() : 0;
  }

  public static boolean n_isContinuous(long m) {
    return true;
  }

  public static boolean n_isSubmatrix(long m) {
    return false;
  }

  public static int n_checkVector(long m, int elemChannels, int depth, boolean requireContinuous) {
    Data d = data(m);
    if (d.empty() || (depth > 0 && d.depth() != depth)) {
      return -1;
    }
    boolean vector = (d.rows == 1 || d.cols == 1) && d.channels() == elemChannels;
    boolean rowsOfElements = d.cols == elemChannels && d.channels() == 1;
    return vector || rowsOfElements ? (int) ((long) d.rows * d.cols * d.channels() / elemChannels) : -1;
  }

  public static int n_checkVector(long m, int elemChannels, int depth) {
    return n_checkVector(m, elemChannels, depth, true);
  }

  public static int n_checkVector(long m, int elemChannels) {
    return n_checkVector(m, elemChannels, -1, true);
  }

  // Allocation

  public static void n_create(long m, int rows, int cols, int type) {
    data(m).alloc(rows, cols, type);
  }

  public static void n_create(long m, double width, double height, int type) {
    data(m).alloc((int) height, (int) width, type);
  }

  public static void n_copySize(long m, long other) {
    Data source = data(other);
    data(m).alloc(source.rows, source.cols, data(m).type);
  }

  public static long n_zeros(int rows, int cols, int type) {
    return create(rows, cols, type);
  }

  public static long n_zeros(double width, double height, int type) {
    return create((int) height, (int) width, type);
  }

  public static long n_ones(int rows, int cols, int type) {
    return register(filled(rows, cols, type, 1, 0, 0, 0));
  }

  public static long n_ones(double width, double height, int type) {
    return register(filled((int) height, (int) width, type, 1, 0, 0, 0));
  }

  public static long n_eye(int rows, int cols, int type) {
    Data d = new Data();
    d.alloc(rows, cols, type);
    for (int i = 0; i < Math.min(rows, cols); i++) {
      d.set(i, i, 0, 1);
    }
    return register(d);
  }

  public static long n_eye(double width, double height, int type) {
    return n_eye((int) height, (int) width, type);
  }

  // Copies and conversions

  public static long n_clone(long m) {
    return register(data(m).copy());
  }

  public static void n_assignTo(long m, long dst) {
    data(dst).share(data(m));
  }

  public static void n_assignTo(long m, long dst, int type) {
    n_convertTo(m, dst, type, 1, 0);
  }

  public static void n_copyTo(long m, long dst) {
    Data source = data(m);
    Data target = data(dst);
    if (target == source) {
      return;
    }
    target.share(source.copy());
  }

  public static void n_copyTo(long m, long dst, long mask) {
    Data source = data(m);
    Data target = data(dst);
    Data keep = data(mask);
    if (target.rows != source.rows || target.cols != source.cols || target.type != source.type) {
      target.alloc(source.rows, source.cols, source.type);
    }
    for (int r = 0; r < source.rows; r++) {
      for (int c = 0; c < source.cols; c++) {
        if (keep.get(r, c, 0) != 0) {
          for (int ch = 0; ch < source.channels(); ch++) {
            target.set(r, c, ch, source.get(r, c, ch));
          }
        }
      }
    }
  }

  public static void n_convertTo(long m, long dst, int rtype, double alpha, double beta) {
    Data source = data(m);
    int depth = rtype < 0 ? source.depth() : rtype & 7;
    Data converted = new Data();
    converted.alloc(source.rows, source.cols, type(depth, source.channels()));
    for (int i = 0; i < source.size(); i++) {
      converted.set(i, source.get(i) * alpha + beta);
    }
    data(dst).share(converted);
  }

  public static void n_convertTo(long m, long dst, int rtype, double alpha) {
    n_convertTo(m, dst, rtype, alpha, 0);
  }

  public static void n_convertTo(long m, long dst, int rtype) {
    n_convertTo(m, dst, rtype, 1, 0);
  }

  public static long n_setTo(long m, double v0, double v1, double v2, double v3) {
    Data d = data(m);
    double[] scalar = {v0, v1, v2, v3};
    for (int i = 0; i < d.size(); i++) {
      d.set(i, scalar[i % d.channels() % 4]);
    }
    return shared(d);
  }

  public static long n_setTo(long m, double v0, double v1, double v2, double v3, long mask) {
    Data d = data(m);
    Data keep = data(mask);
    double[] scalar = {v0, v1, v2, v3};
    for (int r = 0; r < d.rows; r++) {
      for (int c = 0; c < d.cols; c++) {
        if (keep.get(r, c, 0) != 0) {
          for (int ch = 0; ch < d.channels(); ch++) {
            d.set(r, c, ch, scalar[ch % 4]);
          }
        }
      }
    }
    return shared(d);
  }

  public static long shared(Data d) {
    Data view = new Data();
    view.share(d);
    return register(view);
  }

  // Parts and shapes

  public static long n_submat(long m, int x, int y, int width, int height) {
    return viewOf(data(m), y, y + height, x, x + width);
  }

  public static long n_submat_rr(long m, int rowStart, int rowEnd, int colStart, int colEnd) {
    return viewOf(data(m), rowStart, rowEnd, colStart, colEnd);
  }

  public static long n_row(long m, int y) {
    Data d = data(m);
    return viewOf(d, y, y + 1, 0, d.cols);
  }

  public static long n_col(long m, int x) {
    Data d = data(m);
    return viewOf(d, 0, d.rows, x, x + 1);
  }

  public static long n_rowRange(long m, int start, int end) {
    Data d = data(m);
    return viewOf(d, start, end, 0, d.cols);
  }

  public static long n_colRange(long m, int start, int end) {
    Data d = data(m);
    return viewOf(d, 0, d.rows, start, end);
  }

  public static long n_t(long m) {
    Data d = data(m);
    Data t = new Data();
    t.alloc(d.cols, d.rows, d.type);
    for (int r = 0; r < d.rows; r++) {
      for (int c = 0; c < d.cols; c++) {
        for (int ch = 0; ch < d.channels(); ch++) {
          t.set(c, r, ch, d.get(r, c, ch));
        }
      }
    }
    return register(t);
  }

  public static long n_reshape(long m, int channels, int rows) {
    Data d = data(m);
    int newChannels = channels == 0 ? d.channels() : channels;
    int newRows = rows == 0 ? d.rows : rows;
    int elements = d.size();
    if (newRows <= 0 || elements % (newChannels * newRows) != 0) {
      throw new IllegalArgumentException("the number of elements does not fit the new shape");
    }
    Data view = new Data();
    view.share(d);
    view.rows = newRows;
    view.cols = elements / (newChannels * newRows);
    view.type = type(d.depth(), newChannels);
    return register(view);
  }

  public static long n_reshape(long m, int channels) {
    return n_reshape(m, channels, 0);
  }

  public static void n_push_back(long m, long other) {
    Data d = data(m);
    Data extra = data(other);
    if (d.empty()) {
      d.share(extra.copy());
      return;
    }
    if (d.cols != extra.cols || d.type != extra.type) {
      throw new IllegalArgumentException("push_back needs the same columns and type");
    }
    byte[] joined = new byte[d.bytes().length + extra.bytes().length];
    System.arraycopy(d.bytes(), 0, joined, 0, d.bytes().length);
    System.arraycopy(extra.bytes(), 0, joined, d.bytes().length, extra.bytes().length);
    d.rows += extra.rows;
    d.setBytes(joined);
  }

  public static double n_dot(long m, long other) {
    Data a = data(m);
    Data b = data(other);
    double sum = 0;
    for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
      sum += a.get(i) * b.get(i);
    }
    return sum;
  }

  /** Not an address, but nonzero when the Mat has data, which is all cscore's putFrame checks. */
  public static long n_dataAddr(long m) {
    return data(m).empty() ? 0 : m;
  }

  // Elements: Java arrays in, bytes copied out, as the native copy does

  public static int putBytes(long m, int row, int col, int count, int elementBytes, byte[] source, int offset) {
    Data d = data(m);
    if (row < 0 || col < 0 || row >= d.rows || col >= d.cols) {
      return 0;
    }
    int start = (row * d.cols + col) * d.elemSize();
    int bytes = Math.min(count * elementBytes, d.bytes().length - start);
    System.arraycopy(source, offset, d.bytes(), start, bytes);
    return bytes;
  }

  public static int getBytes(long m, int row, int col, int count, int elementBytes, byte[] target) {
    Data d = data(m);
    if (row < 0 || col < 0 || row >= d.rows || col >= d.cols) {
      return 0;
    }
    int start = (row * d.cols + col) * d.elemSize();
    int bytes = Math.min(count * elementBytes, d.bytes().length - start);
    System.arraycopy(d.bytes(), start, target, 0, bytes);
    return bytes;
  }

  public static int nPutB(long m, int row, int col, int count, byte[] values) {
    return putBytes(m, row, col, count, 1, values, 0);
  }

  public static int nPutBwOffset(long m, int row, int col, int count, int offset, byte[] values) {
    return putBytes(m, row, col, count, 1, values, offset);
  }

  public static int nPutS(long m, int row, int col, int count, short[] values) {
    ByteBuffer b = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
    b.asShortBuffer().put(values, 0, count);
    return putBytes(m, row, col, count, 2, b.array(), 0);
  }

  public static int nPutI(long m, int row, int col, int count, int[] values) {
    ByteBuffer b = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
    b.asIntBuffer().put(values, 0, count);
    return putBytes(m, row, col, count, 4, b.array(), 0);
  }

  public static int nPutF(long m, int row, int col, int count, float[] values) {
    ByteBuffer b = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
    b.asFloatBuffer().put(values, 0, count);
    return putBytes(m, row, col, count, 4, b.array(), 0);
  }

  /** Unlike the typed puts, this one converts the values to the type of the Mat. */
  public static int nPutD(long m, int row, int col, int count, double[] values) {
    Data d = data(m);
    if (row < 0 || col < 0 || row >= d.rows || col >= d.cols) {
      return 0;
    }
    int start = (row * d.cols + col) * d.channels();
    int copied = Math.min(count, d.size() - start);
    for (int i = 0; i < copied; i++) {
      d.set(start + i, values[i]);
    }
    return copied * 8;
  }

  public static int nGetB(long m, int row, int col, int count, byte[] values) {
    return getBytes(m, row, col, count, 1, values);
  }

  public static int nGetS(long m, int row, int col, int count, short[] values) {
    byte[] raw = new byte[count * 2];
    int bytes = getBytes(m, row, col, count, 2, raw);
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(values, 0, count);
    return bytes;
  }

  public static int nGetI(long m, int row, int col, int count, int[] values) {
    byte[] raw = new byte[count * 4];
    int bytes = getBytes(m, row, col, count, 4, raw);
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(values, 0, count);
    return bytes;
  }

  public static int nGetF(long m, int row, int col, int count, float[] values) {
    byte[] raw = new byte[count * 4];
    int bytes = getBytes(m, row, col, count, 4, raw);
    ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values, 0, count);
    return bytes;
  }

  public static int nGetD(long m, int row, int col, int count, double[] values) {
    Data d = data(m);
    if (row < 0 || col < 0 || row >= d.rows || col >= d.cols) {
      return 0;
    }
    int start = (row * d.cols + col) * d.channels();
    int copied = Math.min(count, d.size() - start);
    for (int i = 0; i < copied; i++) {
      values[i] = d.get(start + i);
    }
    return copied * 8;
  }

  public static double[] nGet(long m, int row, int col) {
    Data d = data(m);
    if (row < 0 || col < 0 || row >= d.rows || col >= d.cols) {
      return null;
    }
    double[] pixel = new double[d.channels()];
    for (int ch = 0; ch < pixel.length; ch++) {
      pixel[ch] = d.get(row, col, ch);
    }
    return pixel;
  }

  // The same by index vector, for two dimensions

  public static int nPutBIdx(long m, int[] idx, int count, byte[] values) {
    return nPutB(m, idx[0], idx[1], count, values);
  }

  public static int nPutBwIdxOffset(long m, int[] idx, int count, int offset, byte[] values) {
    return nPutBwOffset(m, idx[0], idx[1], count, offset, values);
  }

  public static int nPutSIdx(long m, int[] idx, int count, short[] values) {
    return nPutS(m, idx[0], idx[1], count, values);
  }

  public static int nPutIIdx(long m, int[] idx, int count, int[] values) {
    return nPutI(m, idx[0], idx[1], count, values);
  }

  public static int nPutFIdx(long m, int[] idx, int count, float[] values) {
    return nPutF(m, idx[0], idx[1], count, values);
  }

  public static int nPutDIdx(long m, int[] idx, int count, double[] values) {
    return nPutD(m, idx[0], idx[1], count, values);
  }

  public static int nGetBIdx(long m, int[] idx, int count, byte[] values) {
    return nGetB(m, idx[0], idx[1], count, values);
  }

  public static int nGetSIdx(long m, int[] idx, int count, short[] values) {
    return nGetS(m, idx[0], idx[1], count, values);
  }

  public static int nGetIIdx(long m, int[] idx, int count, int[] values) {
    return nGetI(m, idx[0], idx[1], count, values);
  }

  public static int nGetFIdx(long m, int[] idx, int count, float[] values) {
    return nGetF(m, idx[0], idx[1], count, values);
  }

  public static int nGetDIdx(long m, int[] idx, int count, double[] values) {
    return nGetD(m, idx[0], idx[1], count, values);
  }

  public static double[] nGetIdx(long m, int[] idx) {
    return nGet(m, idx[0], idx[1]);
  }

  public static String nDump(long m) {
    Data d = data(m);
    boolean integral = d.depth() < CV_32F;
    StringBuilder out = new StringBuilder("[");
    for (int r = 0; r < d.rows; r++) {
      out.append(r == 0 ? "" : ";\n ");
      for (int i = 0; i < d.cols * d.channels(); i++) {
        double v = d.get(r * d.cols * d.channels() + i);
        out.append(i == 0 ? "" : ", ").append(integral ? String.valueOf((long) v) : String.valueOf(v));
      }
    }
    return out.append("]").toString();
  }
}
