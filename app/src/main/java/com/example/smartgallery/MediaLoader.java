package com.example.smartgallery;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

public final class MediaLoader {
    private MediaLoader() {}

    /** Reads every image the app is allowed to see. Call from a background thread. */
    public static List<Photo> load(ContentResolver cr) {
        List<Photo> out = new ArrayList<>();
        String[] proj = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        };
        try (Cursor c = cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String name = c.getString(1);
                long size = c.getLong(2);
                long taken = c.getLong(3);
                long added = c.getLong(4);
                String folder = c.getString(5);
                if (size <= 0) continue;
                long date = taken > 0 ? taken : added * 1000L;
                out.add(new Photo(id,
                        name == null ? "image" : name,
                        size,
                        date,
                        folder == null ? "Unknown" : folder));
            }
        } catch (Exception e) {
            // Permission revoked or provider hiccup: return what we have.
        }
        return out;
    }
}
