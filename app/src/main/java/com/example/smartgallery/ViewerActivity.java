package com.example.smartgallery;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full-screen photo with swipe, info, "show text" and delete. */
public class ViewerActivity extends AppCompatActivity {
    private ViewPager2 pager;
    private TextView info;
    private final List<Photo> list = new ArrayList<>();
    private Pages adapter;
    private DeleteHelper deleter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_viewer);
        list.addAll(Store.viewerList);
        if (list.isEmpty()) {
            finish();
            return;
        }
        deleter = new DeleteHelper(this);
        pager = findViewById(R.id.pager);
        info = findViewById(R.id.info);
        adapter = new Pages();
        pager.setAdapter(adapter);
        int pos = Math.max(0, Math.min(getIntent().getIntExtra("pos", 0), list.size() - 1));
        pager.setCurrentItem(pos, false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) { updateInfo(); }
        });
        updateInfo();

        Button btnText = findViewById(R.id.btnText);
        btnText.setOnClickListener(v -> showText());
        Button btnDelete = findViewById(R.id.btnDelete);
        btnDelete.setOnClickListener(v -> confirmDelete());
    }

    private Photo current() {
        int i = pager.getCurrentItem();
        return (i >= 0 && i < list.size()) ? list.get(i) : null;
    }

    private void updateInfo() {
        Photo p = current();
        if (p == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(p.name).append("\n");
        sb.append(Fmt.size(p.size)).append("  ·  ").append(Fmt.date(p.dateMs)).append("  ·  ").append(p.folder).append("\n");
        sb.append(pager.getCurrentItem() + 1).append(" of ").append(list.size());
        if (Store.scanned.contains(p.id)) {
            if (Store.hasText(p.id)) sb.append("  ·  has text");
            int f = Store.faces(p.id);
            if (f > 0) sb.append("  ·  ").append(f).append(f == 1 ? " face" : " faces");
        }
        info.setText(sb.toString());
    }

    private void showText() {
        final Photo p = current();
        if (p == null) return;
        new Thread(() -> {
            final String t = Db.get(this).getText(p.id);
            runOnUiThread(() -> {
                String msg;
                if (t == null) msg = "This photo has not been scanned yet. Scanning runs while the app is open.";
                else if (t.trim().isEmpty()) msg = "No text found in this photo.";
                else msg = t;
                TextView tv = new TextView(this);
                tv.setText(msg);
                tv.setTextIsSelectable(true);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                int pad = (int) (20 * getResources().getDisplayMetrics().density);
                tv.setPadding(pad, pad, pad, pad);
                ScrollView sv = new ScrollView(this);
                sv.addView(tv);
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Text in this photo")
                        .setView(sv)
                        .setPositiveButton("Close", null)
                        .show();
            });
        }).start();
    }

    private void confirmDelete() {
        final Photo p = current();
        if (p == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this photo?")
                .setMessage(p.name + " (" + Fmt.size(p.size) + ") will be permanently deleted from your phone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) ->
                        deleter.delete(Collections.singletonList(p), deleted -> onDeleted(deleted)))
                .show();
    }

    private void onDeleted(List<Photo> deleted) {
        if (deleted.isEmpty()) return;
        Store.afterDeleted(this, deleted);
        setResult(Activity.RESULT_OK);
        for (Photo d : deleted) {
            int idx = list.indexOf(d);
            if (idx >= 0) {
                list.remove(idx);
                adapter.notifyItemRemoved(idx);
            }
        }
        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
        if (list.isEmpty()) {
            finish();
        } else {
            pager.post(this::updateInfo);
        }
    }

    private class Pages extends RecyclerView.Adapter<Pages.VH> {
        class VH extends RecyclerView.ViewHolder {
            final ImageView img;

            VH(ImageView v) {
                super(v);
                img = v;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView iv = new ImageView(parent.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return new VH(iv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Glide.with(holder.img).load(list.get(position).uri()).fitCenter().into(holder.img);
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }
}
