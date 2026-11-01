package com.example.smartgallery;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Pure maths for grouping face fingerprints into people (no Android classes, so it is testable). */
public final class ClusterMath {
    private ClusterMath() {}

    /** Running total of all faces assigned to one person. */
    public static final class Acc {
        public long id;
        public float[] sum;
        public int n;
        public double norm;

        public static Acc of(long id, float[] sum, int n) {
            Acc a = new Acc();
            a.id = id;
            a.sum = sum;
            a.n = n;
            a.norm = Math.sqrt(dot(sum, sum));
            return a;
        }

        public static Acc empty(int dim) {
            return of(-1, new float[dim], 0);
        }

        /** Cosine similarity between this person's average face and a new (unit length) face. */
        public double sim(float[] e) {
            if (norm <= 0) return -1;
            return dot(sum, e) / norm;
        }

        public void add(float[] e) {
            for (int i = 0; i < sum.length; i++) sum[i] += e[i];
            n++;
            norm = Math.sqrt(dot(sum, sum));
        }
    }

    public static float dot(float[] a, float[] b) {
        float s = 0f;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    public static void normalize(float[] v) {
        double s = 0;
        for (float x : v) s += x * x;
        s = Math.sqrt(s);
        if (s > 0) for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / s);
    }

    /** Best matching person for a face, or null if there are no people yet. */
    public static Acc best(List<Acc> people, float[] e) {
        Acc best = null;
        double bs = -2;
        for (Acc a : people) {
            double s = a.sim(e);
            if (s > bs) {
                bs = s;
                best = a;
            }
        }
        return best;
    }

    public static byte[] toBytes(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(v);
        return bb.array();
    }

    public static float[] fromBytes(byte[] b) {
        float[] v = new float[b.length / 4];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
        return v;
    }
}
