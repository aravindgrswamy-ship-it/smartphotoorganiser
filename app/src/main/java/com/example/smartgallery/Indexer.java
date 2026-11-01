package com.example.smartgallery;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.FutureTarget;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Background worker that reads the text and finds the faces in every photo, newest first.
 * Results are saved, so each photo is analysed only once.
 */
public final class Indexer {
    public interface Listener {
        void onProgress(int done, int total, boolean running, boolean paused);
        void onData();
    }

    public static final float DEFAULT_THRESHOLD = 0.45f;

    private static Indexer inst;

    public static synchronized Indexer get(Context c) {
        if (inst == null) inst = new Indexer(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final ArrayDeque<Photo> todo = new ArrayDeque<>();
    private boolean running;
    private volatile boolean paused;
    private volatile int total;
    private volatile int done;
    private volatile Listener listener;
    private FaceEngine engine;
    private TextRecognizer recognizer;

    private Indexer(Context c) {
        app = c;
        prefs = c.getSharedPreferences("gallery", Context.MODE_PRIVATE);
    }

    public void setListener(Listener l) {
        listener = l;
        postProgress();
    }

    public void setPaused(boolean p) {
        paused = p;
        postProgress();
    }

    public boolean isPaused() {
        return paused;
    }

    public float threshold() {
        return prefs.getFloat("threshold", DEFAULT_THRESHOLD);
    }

    public void setThreshold(float t) {
        prefs.edit().putFloat("threshold", t).apply();
    }

    /** Null until the first scan has started; then tells if people can be told apart. */
    public synchronized Boolean faceModelReady() {
        return engine == null ? null : Boolean.valueOf(engine.canEmbed());
    }

    private synchronized FaceEngine engine() {
        if (engine == null) engine = new FaceEngine(app);
        return engine;
    }

    private boolean needsWork(long id, boolean canEmbed) {
        if (!Store.scanned.contains(id)) return true;
        return canEmbed && !Store.facesDone.contains(id);
    }

    /** Queue every photo that still needs scanning. Call from a background thread. */
    public void submit(List<Photo> photos) {
        boolean canEmbed = engine().canEmbed();
        synchronized (this) {
            todo.clear();
            for (Photo p : photos) {
                if (needsWork(p.id, canEmbed)) todo.add(p);
            }
            total = todo.size();
            done = 0;
            if (!todo.isEmpty() && !running) {
                running = true;
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { loop(); }
                }, "indexer");
                t.setPriority(Thread.MIN_PRIORITY);
                t.start();
            }
        }
        postProgress();
    }

    private void loop() {
        int sinceFlush = 0;
        while (true) {
            while (paused) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
            }
            Photo p;
            synchronized (this) {
                p = todo.pollFirst();
                if (p == null) {
                    running = false;
                    break;
                }
            }
            try {
                process(p);
            } catch (Throwable t) {
                Log.w("Indexer", "Could not scan " + p.name, t);
                try {
                    // Mark it done so a broken file is not retried forever.
                    Db.get(app).saveScan(p.id, "", 0, 0, true, new ArrayList<FaceEngine.FaceData>());
                    Store.putScan(p.id, "", 0, 0, true);
                } catch (Throwable ignored) {
                }
            }
            done++;
            postProgress();
            if (++sinceFlush >= 15) {
                sinceFlush = 0;
                flush();
            }
        }
        flush();
        postProgress();
    }

    private void process(Photo p) throws Exception {
        FaceEngine eng = engine();
        FutureTarget<Bitmap> target = Glide.with(app).asBitmap()
                .load(p.uri())
                .fitCenter()
                .override(1280, 1280)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .submit();
        try {
            Bitmap bmp = target.get();

            String text = "";
            try {
                text = readText(bmp);
            } catch (Throwable ignored) {
            }
            int letters = 0;
            for (int i = 0; i < text.length(); i++) {
                if (Character.isLetterOrDigit(text.charAt(i))) letters++;
            }
            if (text.length() > 4000) text = text.substring(0, 4000);

            FaceEngine.Out faces = new FaceEngine.Out();
            try {
                faces = eng.run(bmp);
            } catch (Throwable ignored) {
            }

            Db.get(app).saveScan(p.id, text, letters, faces.count, eng.canEmbed(), faces.faces);
            for (FaceEngine.FaceData f : faces.faces) {
                if (f.thumb != null) f.thumb.recycle();
            }
            Store.putScan(p.id, text.toLowerCase(Locale.ROOT), letters, faces.count, eng.canEmbed());
        } finally {
            Glide.with(app).clear(target);
        }
    }

    private String readText(Bitmap bmp) throws Exception {
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        Text t = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)));
        return t.getText();
    }

    /** Group new faces into people and tell the screen to refresh. */
    private void flush() {
        try {
            Db db = Db.get(app);
            Clusterer.assignNew(db, threshold());
            Store.imagePersons = db.imagePersons();
        } catch (Throwable t) {
            Log.w("Indexer", "flush failed", t);
        }
        main.post(new Runnable() {
            @Override public void run() {
                Listener l = listener;
                if (l != null) l.onData();
            }
        });
    }

    private void postProgress() {
        final int d = done, t = total;
        final boolean r, p = paused;
        synchronized (this) {
            r = running;
        }
        main.post(new Runnable() {
            @Override public void run() {
                Listener l = listener;
                if (l != null) l.onProgress(d, t, r, p);
            }
        });
    }
}
