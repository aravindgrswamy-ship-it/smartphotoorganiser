package com.example.smartgallery;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Remembers what the scanner found, so photos are only analysed once. */
public class Db extends SQLiteOpenHelper {
    private static Db inst;

    public static synchronized Db get(Context c) {
        if (inst == null) inst = new Db(c.getApplicationContext());
        return inst;
    }

    private final File facesDir;

    private Db(Context c) {
        super(c, "gallery.db", null, 1);
        facesDir = new File(c.getFilesDir(), "faces");
        facesDir.mkdirs();
    }

    public File faceFile(long faceId) {
        return new File(facesDir, faceId + ".jpg");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE scan (image_id INTEGER PRIMARY KEY, text TEXT, text_len INTEGER, "
                + "face_count INTEGER, faces_done INTEGER)");
        db.execSQL("CREATE TABLE face (id INTEGER PRIMARY KEY AUTOINCREMENT, image_id INTEGER, "
                + "emb BLOB, person_id INTEGER)");
        db.execSQL("CREATE INDEX face_img ON face(image_id)");
        db.execSQL("CREATE INDEX face_person ON face(person_id)");
        db.execSQL("CREATE TABLE person (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, sum BLOB, n INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Nothing yet.
    }

    /** Loads everything previously scanned into the in-memory Store. */
    public void loadScans() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT image_id, text, text_len, face_count, faces_done FROM scan", null)) {
            while (c.moveToNext()) {
                String t = c.getString(1);
                Store.putScan(c.getLong(0), t == null ? "" : t, c.getInt(2), c.getInt(3), c.getInt(4) == 1);
            }
        }
        Store.imagePersons = imagePersons();
    }

    public void saveScan(long imageId, String text, int textLen, int faceCount, boolean facesDone,
                         List<FaceEngine.FaceData> faces) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            deleteFacesOf(db, imageId);
            ContentValues v = new ContentValues();
            v.put("image_id", imageId);
            v.put("text", text);
            v.put("text_len", textLen);
            v.put("face_count", faceCount);
            v.put("faces_done", facesDone ? 1 : 0);
            db.insertWithOnConflict("scan", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            for (FaceEngine.FaceData f : faces) {
                ContentValues fv = new ContentValues();
                fv.put("image_id", imageId);
                fv.put("emb", ClusterMath.toBytes(f.emb));
                long fid = db.insert("face", null, fv);
                saveThumb(fid, f.thumb);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void saveThumb(long faceId, Bitmap b) {
        if (b == null) return;
        try (FileOutputStream os = new FileOutputStream(faceFile(faceId))) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, os);
        } catch (Exception e) {
            // Avatar is cosmetic; ignore.
        }
    }

    private void deleteFacesOf(SQLiteDatabase db, long imageId) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id FROM face WHERE image_id = ?", new String[]{String.valueOf(imageId)})) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        for (Long id : ids) faceFile(id).delete();
        db.delete("face", "image_id = ?", new String[]{String.valueOf(imageId)});
    }

    public void deleteImages(Collection<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                deleteFacesOf(db, id);
                db.delete("scan", "image_id = ?", new String[]{String.valueOf(id)});
            }
            db.execSQL("DELETE FROM person WHERE id NOT IN "
                    + "(SELECT DISTINCT person_id FROM face WHERE person_id IS NOT NULL)");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Forget scan results for photos that no longer exist on the phone. */
    public void deleteMissing(Set<Long> existing) {
        List<Long> gone = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT image_id FROM scan", null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                if (!existing.contains(id)) gone.add(id);
            }
        }
        if (!gone.isEmpty()) deleteImages(gone);
    }

    public String getText(long imageId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT text FROM scan WHERE image_id = ?",
                new String[]{String.valueOf(imageId)})) {
            if (c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    public List<PersonInfo> people(boolean includeSingles) {
        List<PersonInfo> out = new ArrayList<>();
        String sql = "SELECT p.id, p.name, COUNT(DISTINCT f.image_id) AS c, MIN(f.id) "
                + "FROM person p JOIN face f ON f.person_id = p.id GROUP BY p.id "
                + (includeSingles ? "" : "HAVING c >= 2 ")
                + "ORDER BY c DESC, p.id";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                out.add(new PersonInfo(c.getInt(0), c.getString(1), c.getInt(2), c.getLong(3)));
            }
        }
        return out;
    }

    public Map<Long, Set<Integer>> imagePersons() {
        Map<Long, Set<Integer>> m = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT DISTINCT image_id, person_id FROM face WHERE person_id IS NOT NULL", null)) {
            while (c.moveToNext()) {
                long img = c.getLong(0);
                Set<Integer> s = m.get(img);
                if (s == null) {
                    s = new HashSet<>();
                    m.put(img, s);
                }
                s.add(c.getInt(1));
            }
        }
        return m;
    }

    public void renamePerson(int id, String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        getWritableDatabase().update("person", v, "id = ?", new String[]{String.valueOf(id)});
    }
}
