package com.example.smartgallery;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Groups face fingerprints into people. */
public final class Clusterer {
    private Clusterer() {}

    /** Puts every not-yet-grouped face into the closest known person, or starts a new person. */
    public static synchronized void assignNew(Db helper, float threshold) {
        SQLiteDatabase db = helper.getWritableDatabase();
        List<ClusterMath.Acc> people = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT id, sum, n FROM person", null)) {
            while (c.moveToNext()) {
                byte[] blob = c.getBlob(1);
                if (blob == null) continue;
                people.add(ClusterMath.Acc.of(c.getLong(0), ClusterMath.fromBytes(blob), c.getInt(2)));
            }
        }

        long last = -1;
        while (true) {
            List<Long> ids = new ArrayList<>();
            List<float[]> embs = new ArrayList<>();
            try (Cursor c = db.rawQuery(
                    "SELECT id, emb FROM face WHERE person_id IS NULL AND id > ? ORDER BY id LIMIT 500",
                    new String[]{String.valueOf(last)})) {
                while (c.moveToNext()) {
                    byte[] blob = c.getBlob(1);
                    if (blob == null) continue;
                    ids.add(c.getLong(0));
                    embs.add(ClusterMath.fromBytes(blob));
                }
            }
            if (ids.isEmpty()) break;
            last = ids.get(ids.size() - 1);

            Set<ClusterMath.Acc> touched = new HashSet<>();
            db.beginTransaction();
            try {
                for (int i = 0; i < ids.size(); i++) {
                    float[] e = embs.get(i);
                    ClusterMath.Acc best = ClusterMath.best(people, e);
                    ClusterMath.Acc target;
                    if (best != null && best.sim(e) >= threshold) {
                        target = best;
                    } else {
                        target = ClusterMath.Acc.empty(e.length);
                        ContentValues pv = new ContentValues();
                        pv.put("sum", ClusterMath.toBytes(target.sum));
                        pv.put("n", 0);
                        target.id = db.insert("person", null, pv);
                        people.add(target);
                    }
                    target.add(e);
                    touched.add(target);
                    ContentValues fv = new ContentValues();
                    fv.put("person_id", target.id);
                    db.update("face", fv, "id = ?", new String[]{String.valueOf(ids.get(i))});
                }
                for (ClusterMath.Acc a : touched) {
                    ContentValues pv = new ContentValues();
                    pv.put("sum", ClusterMath.toBytes(a.sum));
                    pv.put("n", a.n);
                    db.update("person", pv, "id = ?", new String[]{String.valueOf(a.id)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    /** Throws away all grouping (and person names) and groups every face again. */
    public static synchronized void recluster(Db helper, float threshold) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.execSQL("UPDATE face SET person_id = NULL");
        db.execSQL("DELETE FROM person");
        assignNew(helper, threshold);
    }
}
