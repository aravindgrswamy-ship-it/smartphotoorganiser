package com.example.smartgallery;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Deletes photos the way each Android version allows:
 * 11+ shows ONE system confirmation for the whole batch, 10 asks per photo when needed,
 * 9 and older delete directly.
 */
public class DeleteHelper {
    public interface Callback {
        void done(List<Photo> deleted);
    }

    private final AppCompatActivity act;
    private final ActivityResultLauncher<IntentSenderRequest> launcher;
    private Callback callback;
    private List<Photo> pending;
    private final ArrayDeque<Photo> queue = new ArrayDeque<>();
    private final List<Photo> deleted = new ArrayList<>();
    private Photo current;

    /** Create this in onCreate(). */
    public DeleteHelper(AppCompatActivity activity) {
        act = activity;
        launcher = activity.registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> onResult(result.getResultCode() == Activity.RESULT_OK));
    }

    public void delete(List<Photo> photos, Callback cb) {
        if (photos.isEmpty()) return;
        callback = cb;
        deleted.clear();
        if (Build.VERSION.SDK_INT >= 30) {
            pending = new ArrayList<>(photos);
            List<Uri> uris = new ArrayList<>();
            for (Photo p : photos) uris.add(p.uri());
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(act.getContentResolver(), uris);
                launcher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } catch (Exception e) {
                finish();
            }
        } else {
            queue.clear();
            queue.addAll(photos);
            next();
        }
    }

    private void onResult(boolean ok) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (ok && pending != null) deleted.addAll(pending);
            pending = null;
            finish();
            return;
        }
        if (ok && current != null) {
            Photo p = current;
            try {
                if (act.getContentResolver().delete(p.uri(), null, null) > 0) deleted.add(p);
            } catch (Exception ignored) {
            }
        }
        current = null;
        next();
    }

    private void next() {
        ContentResolver cr = act.getContentResolver();
        while (!queue.isEmpty()) {
            Photo p = queue.pollFirst();
            current = p;
            try {
                if (cr.delete(p.uri(), null, null) > 0) deleted.add(p);
                current = null;
            } catch (SecurityException e) {
                if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException) {
                    RecoverableSecurityException rse = (RecoverableSecurityException) e;
                    launcher.launch(new IntentSenderRequest.Builder(
                            rse.getUserAction().getActionIntent().getIntentSender()).build());
                    return;   // continue in onResult()
                }
                current = null;
            }
        }
        finish();
    }

    private void finish() {
        Callback cb = callback;
        callback = null;
        if (cb != null) cb.done(new ArrayList<>(deleted));
    }
}
