package cc.ginpika.minis.ai.memory.store;

/**
 * float[] &lt;-&gt; BLOB 编解码 + 余弦相似度，BLOB 格式与 ai.knowledge.retrieval.VecMath
 * 完全一致（little-endian float32，向量表的通用格式）。
 *
 * <p>不直接复用那边的原因是依赖方向的硬约束：memory → knowledge 只允许出现在固化管道（M2），
 * 而向量编码只是存储格式细节——就地一份，两边的格式由本类注释与 chunk_embedding 语义钉死。
 */
public final class MemoryVecMath {

    private MemoryVecMath() {
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
