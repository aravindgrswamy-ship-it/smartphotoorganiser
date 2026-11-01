package com.example.smartgallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Finds faces (Google ML Kit) and turns each one into a 512-number fingerprint
 * (MobileFaceNet model bundled in assets). Everything runs on the phone.
 */
public class FaceEngine {
    private static final String MODEL = "w600k_mbf.onnx";
    private static final int MIN_FACE_PX = 40;   // ignore tiny background faces
    private static final int MAX_FACES = 12;
    private static final float MAX_YAW = 45f;    // skip faces turned too far sideways

    // Where the eyes and mouth sit in a standard 112x112 face crop (ArcFace layout).
    private static final float[] TEMPLATE = {
            38.2946f, 51.6963f,   // left eye
            73.5318f, 51.5014f,   // right eye
            56.1396f, 92.2848f    // middle of the mouth
    };

    public static class FaceData {
        public float[] emb;
        public Bitmap thumb;
    }

    public static class Out {
        public int count;
        public final List<FaceData> faces = new ArrayList<>();
    }

    private final FaceDetector detector;
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;
    private boolean embedder;

    public FaceEngine(Context ctx) {
        FaceDetectorOptions opts = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.04f)
                .build();
        detector = FaceDetection.getClient(opts);
        try (InputStream is = ctx.getAssets().open(MODEL)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(14 * 1024 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions so = new OrtSession.SessionOptions();
            so.setIntraOpNumThreads(2);
            session = env.createSession(bos.toByteArray(), so);
            inputName = session.getInputNames().iterator().next();
            embedder = true;
        } catch (Throwable t) {
            embedder = false;   // model missing: text search still works, "who is this" does not
        }
    }

    /** True when the face-recognition model loaded, so people can be told apart. */
    public boolean canEmbed() {
        return embedder;
    }

    public Out run(Bitmap bmp) throws Exception {
        Out out = new Out();
        List<Face> found = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)));
        for (Face f : found) {
            Rect r = f.getBoundingBox();
            if (Math.min(r.width(), r.height()) < MIN_FACE_PX) continue;
            if (out.count >= MAX_FACES) break;
            out.count++;
            if (!embedder) continue;
            if (Math.abs(f.getHeadEulerAngleY()) > MAX_YAW) continue;
            try {
                Bitmap aligned = align(bmp, f);
                float[] emb = embed(aligned);
                FaceData d = new FaceData();
                d.emb = emb;
                d.thumb = aligned;
                out.faces.add(d);
            } catch (Throwable t) {
                // One bad face must not stop the photo.
            }
        }
        return out;
    }

    private static PointF pt(Face f, int type) {
        FaceLandmark l = f.getLandmark(type);
        return l == null ? null : l.getPosition();
    }

    /** Rotates/scales the face so eyes and mouth land where the model expects them. */
    private Bitmap align(Bitmap src, Face f) {
        Bitmap out = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        Matrix m = new Matrix();

        PointF le = pt(f, FaceLandmark.LEFT_EYE);
        PointF re = pt(f, FaceLandmark.RIGHT_EYE);
        PointF ml = pt(f, FaceLandmark.MOUTH_LEFT);
        PointF mr = pt(f, FaceLandmark.MOUTH_RIGHT);
        boolean ok = false;
        if (le != null && re != null && ml != null && mr != null) {
            // Order by x so it does not matter which side ML Kit calls "left".
            PointF a = le.x <= re.x ? le : re;
            PointF b = le.x <= re.x ? re : le;
            float[] s = {a.x, a.y, b.x, b.y, (ml.x + mr.x) / 2f, (ml.y + mr.y) / 2f};
            ok = m.setPolyToPoly(s, 0, TEMPLATE, 0, 3);
        }
        if (!ok) {
            Rect r = f.getBoundingBox();
            float padX = r.width() * 0.15f, padY = r.height() * 0.15f;
            RectF from = new RectF(r.left - padX, r.top - padY, r.right + padX, r.bottom + padY);
            m.setRectToRect(from, new RectF(0, 0, 112, 112), Matrix.ScaleToFit.FILL);
        }
        cv.drawBitmap(src, m, paint);
        return out;
    }

    private float[] embed(Bitmap face112) throws Exception {
        int size = 112 * 112;
        int[] px = new int[size];
        face112.getPixels(px, 0, 112, 0, 0, 112, 112);
        float[] data = new float[3 * size];
        for (int i = 0; i < size; i++) {
            int c = px[i];
            data[i] = (((c >> 16) & 0xFF) - 127.5f) / 127.5f;            // R
            data[size + i] = (((c >> 8) & 0xFF) - 127.5f) / 127.5f;      // G
            data[2 * size + i] = ((c & 0xFF) - 127.5f) / 127.5f;         // B
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = direct.asFloatBuffer();
        fb.put(data);
        fb.rewind();
        try (OnnxTensor t = OnnxTensor.createTensor(env, fb, new long[]{1, 3, 112, 112});
             OrtSession.Result r = session.run(Collections.singletonMap(inputName, t))) {
            float[][] o = (float[][]) r.get(0).getValue();
            float[] v = o[0].clone();
            ClusterMath.normalize(v);
            return v;
        }
    }
}
