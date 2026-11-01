package com.example.smartgallery;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

/** One image from the phone's media library. */
public class Photo {
    public final long id;
    public final String name;
    public final long size;
    public final long dateMs;
    public final String folder;

    public Photo(long id, String name, long size, long dateMs, String folder) {
        this.id = id;
        this.name = name;
        this.size = size;
        this.dateMs = dateMs;
        this.folder = folder;
    }

    public Uri uri() {
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }
}
