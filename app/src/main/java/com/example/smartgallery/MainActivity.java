package com.example.smartgallery;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements PhotoAdapter.Host, PeopleAdapter.Host {

    enum Sort {
        NEWEST("Date: newest first"),
        OLDEST("Date: oldest first"),
        LARGEST("Size: largest first"),
        SMALLEST("Size: smallest first"),
        NAME_AZ("Name: A to Z"),
        NAME_ZA("Name: Z to A"),
        FOLDER_AZ("Folder: A to Z"),
        FOLDER_SIZE("Folder: biggest first");

        final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    private RecyclerView rv;
    private GridLayoutManager glm;
    private PhotoAdapter photoAdapter;
    private PeopleAdapter peopleAdapter;
    private EditText search;
    private Chip chipText, chipFaces, chipPeople, chipFolder, chipSort;
    private View banner, emptyBox;
    private TextView bannerText, emptyText;
    private ProgressBar bannerBar;
    private Button bannerBtn, grantBtn;

    private Sort sort = Sort.NEWEST;
    private String folderFilter = null;
    private int personFilter = -1;
    private String personName = null;
    private boolean onlyText = false;
    private boolean onlyFaces = false;
    private String query = "";
    private boolean peopleMode = false;
    private boolean showSingles = false;
    private boolean askedPermission = false;

    private final Set<Long> selected = new LinkedHashSet<>();
    private List<Photo> shown = new ArrayList<>();
    private List<PersonInfo> peopleList = new ArrayList<>();

    private DeleteHelper deleter;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable searchRunnable = new Runnable() {
        @Override public void run() {
            query = search.getText().toString();
            if (peopleMode && !query.trim().isEmpty()) peopleMode = false;
            refresh();
        }
    };

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> afterPermission());

    private final ActivityResultLauncher<Intent> viewerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                selected.clear();
                refresh();
            });

    // ------------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar((MaterialToolbar) findViewById(R.id.toolbar));

        deleter = new DeleteHelper(this);

        search = findViewById(R.id.search);
        chipText = findViewById(R.id.chipText);
        chipFaces = findViewById(R.id.chipFaces);
        chipPeople = findViewById(R.id.chipPeople);
        chipFolder = findViewById(R.id.chipFolder);
        chipSort = findViewById(R.id.chipSort);
        banner = findViewById(R.id.banner);
        bannerText = findViewById(R.id.bannerText);
        bannerBar = findViewById(R.id.bannerBar);
        bannerBtn = findViewById(R.id.bannerBtn);
        emptyBox = findViewById(R.id.emptyBox);
        emptyText = findViewById(R.id.emptyText);
        grantBtn = findViewById(R.id.grantBtn);
        rv = findViewById(R.id.list);

        photoAdapter = new PhotoAdapter(this);
        peopleAdapter = new PeopleAdapter(this, Db.get(this));
        glm = new GridLayoutManager(this, spanCount());
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                if (peopleMode) return 1;
                return photoAdapter.isHeader(position) ? glm.getSpanCount() : 1;
            }
        });
        rv.setLayoutManager(glm);
        rv.setAdapter(photoAdapter);
        rv.setHasFixedSize(false);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                main.removeCallbacks(searchRunnable);
                main.postDelayed(searchRunnable, 300);
            }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                main.removeCallbacks(searchRunnable);
                searchRunnable.run();
            }
            return false;
        });

        chipText.setOnCheckedChangeListener((b, checked) -> {
            onlyText = checked;
            peopleMode = false;
            refresh();
        });
        chipFaces.setOnCheckedChangeListener((b, checked) -> {
            onlyFaces = checked;
            peopleMode = false;
            refresh();
        });
        chipPeople.setOnClickListener(v -> {
            if (personFilter >= 0) {
                personFilter = -1;
                personName = null;
                peopleMode = false;
            } else {
                peopleMode = !peopleMode;
            }
            clearSelection(false);
            refresh();
        });
        chipFolder.setOnClickListener(v -> showFolderDialog());
        chipSort.setOnClickListener(v -> showSortDialog());

        bannerBtn.setOnClickListener(v -> {
            Indexer idx = Indexer.get(this);
            idx.setPaused(!idx.isPaused());
        });
        grantBtn.setOnClickListener(v -> requestAccess());

        Indexer.get(this).setListener(new Indexer.Listener() {
            @Override public void onProgress(int done, int total, boolean running, boolean paused) {
                updateBanner(done, total, running, paused);
            }

            @Override public void onData() {
                if (peopleMode) loadPeople();
                else if (onlyText || onlyFaces || personFilter >= 0 || !query.trim().isEmpty()) refresh();
            }
        });

        updateChips();
        updateToolbar();
        if (hasPerms()) {
            loadPhotos();
        } else {
            showNoAccess();
            askedPermission = true;
            permLauncher.launch(perms());
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (hasPerms() && Store.loaded) loadPhotos();   // pick up photos added or removed while away
    }

    @Override
    protected void onDestroy() {
        Indexer.get(this).setListener(null);
        main.removeCallbacks(searchRunnable);
        super.onDestroy();
    }

    private int spanCount() {
        float dp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        return Math.max(3, (int) (dp / 120f));
    }

    // ------------------------------------------------------------ permissions

    private String[] perms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    private boolean hasPerms() {
        for (String p : perms()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void afterPermission() {
        if (hasPerms()) loadPhotos();
        else showNoAccess();
    }

    private void requestAccess() {
        boolean canAsk = false;
        for (String p : perms()) {
            if (shouldShowRequestPermissionRationale(p)) canAsk = true;
        }
        if (!askedPermission || canAsk) {
            askedPermission = true;
            permLauncher.launch(perms());
        } else {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        }
    }

    private void showNoAccess() {
        emptyBox.setVisibility(View.VISIBLE);
        grantBtn.setVisibility(View.VISIBLE);
        emptyText.setText("Smart Gallery Organiser needs access to your photos to find, sort and delete them.\n\n"
                + "Everything is analysed on your phone. Nothing is uploaded.");
    }

    // ---------------------------------------------------------------- loading

    private void loadPhotos() {
        bg.execute(() -> {
            List<Photo> list = MediaLoader.load(getContentResolver());
            Collections.sort(list, (a, b) -> Long.compare(b.dateMs, a.dateMs));
            Db db = Db.get(this);
            if (!Store.loaded) {
                db.loadScans();
                Store.loaded = true;
            }
            if (!list.isEmpty()) {
                Set<Long> ids = new HashSet<>();
                for (Photo p : list) ids.add(p.id);
                db.deleteMissing(ids);
            }
            Store.setPhotos(list);
            Indexer.get(this).submit(list);
            main.post(() -> {
                selected.retainAll(idsOf(list));
                refresh();
            });
        });
    }

    private static Set<Long> idsOf(List<Photo> list) {
        Set<Long> s = new HashSet<>();
        for (Photo p : list) s.add(p.id);
        return s;
    }

    private void loadPeople() {
        bg.execute(() -> {
            final List<PersonInfo> l = Db.get(this).people(showSingles);
            main.post(() -> {
                if (!peopleMode) return;
                peopleList = l;
                peopleAdapter.setItems(l);
                updateEmpty();
                updateToolbar();
            });
        });
    }

    // -------------------------------------------------------- filtering/sorting

    private List<Photo> filterAndSort() {
        List<Photo> out = new ArrayList<>();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Photo p : Store.photos) {
            if (folderFilter != null && !folderFilter.equals(p.folder)) continue;
            if (onlyText && !Store.hasText(p.id)) continue;
            if (onlyFaces && Store.faces(p.id) == 0) continue;
            if (personFilter >= 0) {
                Set<Integer> s = Store.imagePersons.get(p.id);
                if (s == null || !s.contains(personFilter)) continue;
            }
            if (!q.isEmpty()) {
                String t = Store.text.get(p.id);
                boolean inText = t != null && t.contains(q);
                boolean inName = p.name.toLowerCase(Locale.ROOT).contains(q);
                if (!inText && !inName) continue;
            }
            out.add(p);
        }

        final Map<String, Long> folderBytes = new HashMap<>();
        for (Photo p : out) {
            Long b = folderBytes.get(p.folder);
            folderBytes.put(p.folder, (b == null ? 0L : b) + p.size);
        }
        Comparator<Photo> byDateDesc = (a, b) -> Long.compare(b.dateMs, a.dateMs);
        Comparator<Photo> cmp;
        switch (sort) {
            case OLDEST:
                cmp = (a, b) -> Long.compare(a.dateMs, b.dateMs);
                break;
            case LARGEST:
                cmp = (a, b) -> Long.compare(b.size, a.size);
                break;
            case SMALLEST:
                cmp = (a, b) -> Long.compare(a.size, b.size);
                break;
            case NAME_AZ:
                cmp = (a, b) -> a.name.compareToIgnoreCase(b.name);
                break;
            case NAME_ZA:
                cmp = (a, b) -> b.name.compareToIgnoreCase(a.name);
                break;
            case FOLDER_AZ:
                cmp = (a, b) -> {
                    int c = a.folder.compareToIgnoreCase(b.folder);
                    return c != 0 ? c : Long.compare(b.dateMs, a.dateMs);
                };
                break;
            case FOLDER_SIZE:
                cmp = (a, b) -> {
                    int c = Long.compare(folderBytes.get(b.folder), folderBytes.get(a.folder));
                    if (c != 0) return c;
                    c = a.folder.compareToIgnoreCase(b.folder);
                    return c != 0 ? c : Long.compare(b.dateMs, a.dateMs);
                };
                break;
            case NEWEST:
            default:
                cmp = byDateDesc;
                break;
        }
        Collections.sort(out, cmp);
        return out;
    }

    private void refresh() {
        updateChips();
        if (!hasPerms()) {
            showNoAccess();
            return;
        }
        grantBtn.setVisibility(View.GONE);
        if (peopleMode) {
            if (rv.getAdapter() != peopleAdapter) rv.setAdapter(peopleAdapter);
            loadPeople();
            updateToolbar();
            return;
        }
        shown = filterAndSort();
        List<Object> items = new ArrayList<>();
        boolean grouped = sort == Sort.FOLDER_AZ || sort == Sort.FOLDER_SIZE;
        if (grouped) {
            String lastFolder = null;
            List<Photo> group = null;
            List<List<Photo>> groups = new ArrayList<>();
            for (Photo p : shown) {
                if (!p.folder.equals(lastFolder)) {
                    group = new ArrayList<>();
                    groups.add(group);
                    lastFolder = p.folder;
                }
                group.add(p);
            }
            for (List<Photo> g : groups) {
                long bytes = 0;
                for (Photo p : g) bytes += p.size;
                String title = g.get(0).folder + "  ·  " + g.size() + (g.size() == 1 ? " photo" : " photos")
                        + "  ·  " + Fmt.size(bytes) + "   (tap to select all)";
                items.add(new PhotoAdapter.Header(title, g));
                items.addAll(g);
            }
        } else {
            items.addAll(shown);
        }
        if (rv.getAdapter() != photoAdapter) rv.setAdapter(photoAdapter);
        photoAdapter.setItems(items);
        updateEmpty();
        updateToolbar();
    }

    private void updateChips() {
        chipPeople.setChecked(peopleMode || personFilter >= 0);
        chipPeople.setText(personFilter >= 0 ? "Person: " + personName + "  ✕" : "People");
        chipFolder.setText(folderFilter == null ? "Folder: all" : "Folder: " + folderFilter);
        chipSort.setText(sort.label);
    }

    private void updateEmpty() {
        boolean empty = peopleMode ? peopleList.isEmpty() : shown.isEmpty();
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        grantBtn.setVisibility(View.GONE);
        if (!empty) return;
        Boolean model = Indexer.get(this).faceModelReady();
        if (peopleMode) {
            if (model != null && !model) {
                emptyText.setText("The face-recognition model is missing from this build, so people can't be told apart. "
                        + "Text search still works.");
            } else {
                emptyText.setText("No people yet.\n\nPhotos are scanned in the background while the app is open. "
                        + "People appear here once the same face is found in two or more photos.");
            }
        } else if (!Store.loaded || Store.photos.isEmpty()) {
            emptyText.setText("No photos found.");
        } else if (onlyText || onlyFaces || personFilter >= 0 || !query.trim().isEmpty()) {
            emptyText.setText("No matches yet.\n\nPhotos are scanned in the background while the app is open, "
                    + "so more results can appear as scanning continues.");
        } else {
            emptyText.setText("No photos to show.");
        }
    }

    private void updateBanner(int done, int total, boolean running, boolean paused) {
        if (!running || total == 0) {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
        bannerText.setText((paused ? "Scanning paused" : "Scanning photos for text and faces")
                + "  ·  " + done + " of " + total);
        bannerBar.setMax(total);
        bannerBar.setProgress(done);
        bannerBtn.setText(paused ? "Resume" : "Pause");
    }

    // -------------------------------------------------------------- selection

    private List<Photo> selectedPhotos() {
        List<Photo> l = new ArrayList<>();
        for (Long id : selected) {
            Photo p = Store.get(id);
            if (p != null) l.add(p);
        }
        return l;
    }

    private void clearSelection(boolean redraw) {
        if (selected.isEmpty()) return;
        selected.clear();
        if (redraw) photoAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    private void toggle(Photo p) {
        if (!selected.remove(p.id)) selected.add(p.id);
        photoAdapter.notifyPhoto(p.id);
        updateToolbar();
    }

    @Override
    public boolean isSelected(long id) {
        return selected.contains(id);
    }

    @Override
    public void onPhotoClick(Photo p) {
        if (!selected.isEmpty()) {
            toggle(p);
            return;
        }
        Store.viewerList = new ArrayList<>(shown);
        Intent i = new Intent(this, ViewerActivity.class);
        i.putExtra("pos", shown.indexOf(p));
        viewerLauncher.launch(i);
    }

    @Override
    public void onPhotoLong(Photo p) {
        toggle(p);
    }

    @Override
    public void onHeaderClick(PhotoAdapter.Header h) {
        boolean allSelected = true;
        for (Photo p : h.photos) {
            if (!selected.contains(p.id)) {
                allSelected = false;
                break;
            }
        }
        for (Photo p : h.photos) {
            if (allSelected) selected.remove(p.id);
            else selected.add(p.id);
        }
        photoAdapter.notifyDataSetChanged();
        updateToolbar();
    }

    // ------------------------------------------------------------------ people

    @Override
    public void onPersonClick(PersonInfo p) {
        personFilter = p.id;
        personName = p.label();
        peopleMode = false;
        refresh();
    }

    @Override
    public void onPersonLong(final PersonInfo p) {
        final EditText input = new EditText(this);
        input.setHint("Name");
        input.setText(p.name == null ? "" : p.name);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Name this person")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    final String name = input.getText().toString().trim();
                    bg.execute(() -> {
                        Db.get(this).renamePerson(p.id, name);
                        main.post(this::loadPeople);
                    });
                })
                .show();
    }

    private void showStrictnessDialog() {
        final String[] labels = {
                "Looser: groups more, may mix up similar people",
                "Normal (recommended)",
                "Stricter: splits more, may split one person in two"
        };
        final float[] values = {0.38f, 0.45f, 0.52f};
        float cur = Indexer.get(this).threshold();
        int checked = 1;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - cur) < 0.005f) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Face matching (regroups all faces, clears names)")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    final float t = values[which];
                    Indexer.get(this).setThreshold(t);
                    Toast.makeText(this, "Regrouping faces…", Toast.LENGTH_SHORT).show();
                    bg.execute(() -> {
                        Db db = Db.get(this);
                        Clusterer.recluster(db, t);
                        Store.imagePersons = db.imagePersons();
                        main.post(() -> {
                            personFilter = -1;
                            personName = null;
                            refresh();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ----------------------------------------------------------------- dialogs

    private void showSortDialog() {
        Sort[] all = Sort.values();
        String[] labels = new String[all.length];
        for (int i = 0; i < all.length; i++) labels[i] = all[i].label;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sort by")
                .setSingleChoiceItems(labels, sort.ordinal(), (d, which) -> {
                    sort = all[which];
                    d.dismiss();
                    peopleMode = false;
                    refresh();
                })
                .show();
    }

    private void showFolderDialog() {
        Map<String, long[]> stats = new HashMap<>();
        for (Photo p : Store.photos) {
            long[] s = stats.get(p.folder);
            if (s == null) {
                s = new long[2];
                stats.put(p.folder, s);
            }
            s[0]++;
            s[1] += p.size;
        }
        final List<String> names = new ArrayList<>(stats.keySet());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        final String[] labels = new String[names.size() + 1];
        labels[0] = "All folders";
        for (int i = 0; i < names.size(); i++) {
            long[] s = stats.get(names.get(i));
            labels[i + 1] = names.get(i) + "  (" + s[0] + " · " + Fmt.size(s[1]) + ")";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("Show one folder")
                .setItems(labels, (d, which) -> {
                    folderFilter = which == 0 ? null : names.get(which - 1);
                    peopleMode = false;
                    refresh();
                })
                .show();
    }

    private void confirmDelete(final List<Photo> list) {
        if (list.isEmpty()) return;
        long bytes = 0;
        for (Photo p : list) bytes += p.size;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + list.size() + (list.size() == 1 ? " photo?" : " photos?"))
                .setMessage("They will be permanently deleted from your phone, freeing " + Fmt.size(bytes)
                        + ". Android will ask you to confirm once more.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> deleter.delete(list, this::onDeleted))
                .show();
    }

    private void onDeleted(List<Photo> deleted) {
        if (deleted.isEmpty()) return;
        long bytes = 0;
        for (Photo p : deleted) {
            bytes += p.size;
            selected.remove(p.id);
        }
        Store.afterDeleted(this, deleted);
        Toast.makeText(this, "Deleted " + deleted.size() + " · freed " + Fmt.size(bytes), Toast.LENGTH_LONG).show();
        refresh();
    }

    // ------------------------------------------------------------ toolbar/menu

    private void updateToolbar() {
        if (getSupportActionBar() == null) return;
        boolean sel = !selected.isEmpty();
        getSupportActionBar().setDisplayHomeAsUpEnabled(sel);
        if (sel) {
            getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_close);
            long bytes = 0;
            for (Long id : selected) {
                Photo p = Store.get(id);
                if (p != null) bytes += p.size;
            }
            getSupportActionBar().setTitle(selected.size() + " selected");
            getSupportActionBar().setSubtitle(Fmt.size(bytes));
        } else {
            getSupportActionBar().setTitle(R.string.app_name);
            if (peopleMode) {
                getSupportActionBar().setSubtitle(peopleList.size() + " people · tap to open, hold to name");
            } else {
                long bytes = 0;
                for (Photo p : shown) bytes += p.size;
                getSupportActionBar().setSubtitle(shown.size() + " photos · " + Fmt.size(bytes));
            }
        }
        invalidateOptionsMenu();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean sel = !selected.isEmpty();
        menu.findItem(R.id.action_select_all).setVisible(sel);
        menu.findItem(R.id.action_delete).setVisible(sel);
        menu.findItem(R.id.action_singles).setVisible(!sel && peopleMode).setChecked(showSingles);
        menu.findItem(R.id.action_strict).setVisible(!sel);
        MenuItem pause = menu.findItem(R.id.action_pause);
        pause.setVisible(!sel);
        pause.setTitle(Indexer.get(this).isPaused() ? "Resume scanning" : "Pause scanning");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            clearSelection(true);
            return true;
        }
        if (id == R.id.action_select_all) {
            for (Photo p : shown) selected.add(p.id);
            photoAdapter.notifyDataSetChanged();
            updateToolbar();
            return true;
        }
        if (id == R.id.action_delete) {
            confirmDelete(selectedPhotos());
            return true;
        }
        if (id == R.id.action_singles) {
            showSingles = !showSingles;
            loadPeople();
            invalidateOptionsMenu();
            return true;
        }
        if (id == R.id.action_strict) {
            showStrictnessDialog();
            return true;
        }
        if (id == R.id.action_pause) {
            Indexer idx = Indexer.get(this);
            idx.setPaused(!idx.isPaused());
            invalidateOptionsMenu();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!selected.isEmpty()) {
            clearSelection(true);
        } else if (peopleMode) {
            peopleMode = false;
            refresh();
        } else {
            super.onBackPressed();
        }
    }
}
