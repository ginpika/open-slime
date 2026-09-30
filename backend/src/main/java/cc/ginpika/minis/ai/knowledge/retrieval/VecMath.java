package cc.ginpika.minis.ai.knowledge.retrieval;

/** float[] <-> BLOB 编解码 + 余弦相似度（M0 暴力 top-k 用） */
public final class VecMath {

    private VecMath() {
    }

    public static byte[] encode(float[] v) {
        java.nio.ByteBuffer buf =
                java.nio.ByteBuffer.allocate(4 * v.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.asFloatBuffer().put(v);
        return buf.array();
    }

    public static float[] decode(byte[] bytes, int dim) {
        int n = Math.min(dim, bytes.length / 4);
        float[] out = new float[n];
        java.nio.ByteBuffer.wrap(bytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .get(out);
        return out;
    }

    /** 全零向量或维度不一致视为无相似度（返回 0） */
    public static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0f;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0f;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }
}
