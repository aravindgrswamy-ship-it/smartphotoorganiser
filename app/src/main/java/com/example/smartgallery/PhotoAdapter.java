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

/** Grid of photos, optionally split into folder sections with a header row. */
public class PhotoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static class Header {
        public final String title;
        public final List<Photo> photos;

        public Header(String title, List<Photo> photos) {
            this.title = title;
            this.photos = photos;
        }
    }

    public interface Host {
        void onPhotoClick(Photo p);
        void onPhotoLong(Photo p);
        void onHeaderClick(Header h);
        boolean isSelected(long id);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PHOTO = 1;

    private final Host host;
    private List<Object> items = new ArrayList<>();

    public PhotoAdapter(Host host) {
        this.host = host;
    }

    public void setItems(List<Object> list) {
        items = list;
        notifyDataSetChanged();
    }

    public boolean isHeader(int position) {
        return position >= 0 && position < items.size() && items.get(position) instanceof Header;
    }

    public void notifyPhoto(long id) {
        for (int i = 0; i < items.size(); i++) {
            Object o = items.get(i);
            if (o instanceof Photo && ((Photo) o).id == id) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Header ? TYPE_HEADER : TYPE_PHOTO;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_header, parent, false));
        }
        return new PhotoVH(inf.inflate(R.layout.item_photo, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object o = items.get(position);
        if (holder instanceof HeaderVH) {
            final Header h = (Header) o;
            HeaderVH v = (HeaderVH) holder;
            v.title.setText(h.title);
            v.itemView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { host.onHeaderClick(h); }
            });
            return;
        }
        final Photo p = (Photo) o;
        PhotoVH v = (PhotoVH) holder;
        Glide.with(v.img).load(p.uri()).override(300, 300).centerCrop().into(v.img);
        v.size.setText(Fmt.size(p.size));
        boolean sel = host.isSelected(p.id);
        v.dim.setVisibility(sel ? View.VISIBLE : View.GONE);
        v.check.setVisibility(sel ? View.VISIBLE : View.GONE);
        v.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { host.onPhotoClick(p); }
        });
        v.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                host.onPhotoLong(p);
                return true;
            }
        });
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderVH(View v) {
            super(v);
            title = (TextView) v;
        }
    }

    static class PhotoVH extends RecyclerView.ViewHolder {
        final ImageView img;
        final View dim;
        final TextView size;
        final TextView check;

        PhotoVH(View v) {
            super(v);
            img = v.findViewById(R.id.img);
            dim = v.findViewById(R.id.dim);
            size = v.findViewById(R.id.size);
            check = v.findViewById(R.id.check);
        }
    }
}
