package com.example.smartgallery;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory copy of everything the screens need, shared by all activities. */
public final class Store {
    private Store() {}

    /** A photo counts as "has text" when OCR found at least this many letters or digits. */
    public static final int MIN_TEXT = 12;

    public static volatile boolean loaded = false;
    public static volatile List<Photo> photos = new ArrayList<>();       // newest first
    public static volatile List<Photo> viewerList = new ArrayList<>();   // what the viewer pages through

    private static final Map<Long, Photo> byId = new ConcurrentHashMap<>();
    public static final Map<Long, String> text = new ConcurrentHashMap<>();      // lower-case OCR text
    public static final Map<Long, Integer> textLen = new ConcurrentHashMap<>();
    public static final Map<Long, Integer> faceCount = new ConcurrentHashMap<>();
    public static final Set<Long> scanned = ConcurrentHashMap.newKeySet();
    public static final Set<Long> facesDone = ConcurrentHashMap.newKeySet();
    public static volatile Map<Long, Set<Integer>> imagePersons = new HashMap<>();

    public static void setPhotos(List<Photo> list) {
        photos = list;
        byId.clear();
        for (Photo p : list) byId.put(p.id, p);
    }

    public static Photo get(long id) {
        return byId.get(id);
    }

    public static void putScan(long id, String lowerText, int tLen, int faces, boolean embedded) {
        text.put(id, lowerText);
        textLen.put(id, tLen);
        faceCount.put(id, faces);
        scanned.add(id);
        if (embedded) facesDone.add(id);
    }

    public static boolean hasText(long id) {
        Integer n = textLen.get(id);
        return n != null && n >= MIN_TEXT;
    }

    public static int faces(long id) {
        Integer n = faceCount.get(id);
        return n == null ? 0 : n;
    }

    public static void remove(Collection<Long> ids) {
        Set<Long> gone = new HashSet<>(ids);
        List<Photo> keep = new ArrayList<>();
        for (Photo p : photos) if (!gone.contains(p.id)) keep.add(p);
        photos = keep;
        for (Long id : gone) {
            byId.remove(id);
            text.remove(id);
            textLen.remove(id);
            faceCount.remove(id);
            scanned.remove(id);
            facesDone.remove(id);
        }
    }

    /** Call after photos were really deleted from the phone. */
    public static void afterDeleted(final Context ctx, List<Photo> deleted) {
        final List<Long> ids = new ArrayList<>();
        for (Photo p : deleted) ids.add(p.id);
        remove(ids);
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                Db.get(app).deleteImages(ids);
            }
        }).start();
    }
}
