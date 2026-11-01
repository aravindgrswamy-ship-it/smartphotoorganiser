package com.example.smartgallery;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/** Grid of people (one round face each). */
public class PeopleAdapter extends RecyclerView.Adapter<PeopleAdapter.VH> {

    public interface Host {
        void onPersonClick(PersonInfo p);
        void onPersonLong(PersonInfo p);
    }

    private final Host host;
    private final Db db;
    private List<PersonInfo> items = new ArrayList<>();

    public PeopleAdapter(Host host, Db db) {
        this.host = host;
        this.db = db;
    }

    public void setItems(List<PersonInfo> list) {
        items = list;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_person, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH v, int position) {
        final PersonInfo p = items.get(position);
        Glide.with(v.face).load(db.faceFile(p.faceId)).circleCrop().into(v.face);
        v.name.setText(p.label());
        v.count.setText(p.photoCount + (p.photoCount == 1 ? " photo" : " photos"));
        v.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { host.onPersonClick(p); }
        });
        v.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                host.onPersonLong(p);
                return true;
            }
        });
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView face;
        final TextView name;
        final TextView count;

        VH(View v) {
            super(v);
            face = v.findViewById(R.id.face);
            name = v.findViewById(R.id.name);
            count = v.findViewById(R.id.count);
        }
    }
}
