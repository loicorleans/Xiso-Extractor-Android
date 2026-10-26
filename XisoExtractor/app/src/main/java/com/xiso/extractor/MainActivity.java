package com.xiso.extractor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

public class MainActivity extends Activity {

    // theme: deep blue background, purple accents, white file icons
    private static final int C_BG = 0xFF141A2E;
    private static final int C_SURFACE = 0xFF1E2640;
    private static final int C_PURPLE = 0xFF8B5CF6;
    private static final int C_PURPLE_DARK = 0xFF6D43D6;
    private static final int C_PURPLE_LIGHT = 0xFFB79CFF;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_TEXT2 = 0xFFB8C0D9;
    private static final int C_SELECTED = 0xFF2B2F63;
    private static final int C_DISABLED = 0xFF2A3350;
    private static final int C_DISABLED_TEXT = 0xFF6F7896;

    private static final int REQ_ISO = 1;
    private static final int REQ_DIR = 2;
    private static final Set<String> IMAGE_EXT =
            new HashSet<>(Arrays.asList("png", "jpg", "jpeg", "bmp", "gif", "webp"));

    private static final class Row {
        final XisoReader.Entry e;   // null for the ".." row
        Row(XisoReader.Entry e) { this.e = e; }
    }

    private static final class Job {
        final XisoReader.Entry e;
        final String rel;           // path relative to the destination
        Job(XisoReader.Entry e, String rel) { this.e = e; this.rel = rel; }
    }

    private static final class DirCtx {
        final Uri uri;
        final boolean fresh;        // created by us in this run: known to be empty
        Map<String, Uri> files, dirs;
        DirCtx(Uri uri, boolean fresh) { this.uri = uri; this.fresh = fresh; }

        void load(ContentResolver cr, Uri tree) {
            if (files != null) return;
            files = new HashMap<>();
            dirs = new HashMap<>();
            if (fresh) return;
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(uri));
            try (Cursor c = cr.query(kids, new String[]{Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (c == null) return;
                while (c.moveToNext()) {
                    Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(1));
                    if (Document.MIME_TYPE_DIR.equals(c.getString(2))) dirs.put(c.getString(0), u);
                    else files.put(c.getString(0), u);
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ---- ISO session
    private ParcelFileDescriptor.AutoCloseInputStream isoStream;
    private XisoReader reader;
    private String isoName = "";
    private List<XisoReader.Entry> all = new ArrayList<>();
    private final Map<String, XisoReader.Entry> byPath = new HashMap<>();
    private final Map<String, List<XisoReader.Entry>> children = new HashMap<>();
    private final Map<String, long[]> dirStats = new HashMap<>();   // path -> {files, bytes}
    private final Set<String> selected = new HashSet<>();
    private String currentPath = "";
    private String query = "";
    private final List<Row> rows = new ArrayList<>();

    // ---- destination
    private Uri treeUri;
    private Runnable afterDestPick;

    private volatile boolean cancelled;
    private volatile boolean running;

    // ---- views
    private TextView infoText, pathText, statusText, currentText;
    private EditText searchBox;
    private ListView listView;
    private ProgressBar bar;
    private LinearLayout progressPanel;
    private Button btnSelectAll, btnExtractSel, btnExtractAll;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();

        String saved = getSharedPreferences("xiso", MODE_PRIVATE).getString("tree", null);
        if (saved != null) {
            Uri u = Uri.parse(saved);
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(u) && p.isWritePermission()) {
                    treeUri = u;
                    break;
                }
            }
        }
        refreshInfo();
        rebuildRows();

        Intent in = getIntent();
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) {
            openIso(in.getData());
        }
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null && !running) {
            openIso(in.getData());
        }
    }

    @Override
    protected void onDestroy() {
        cancelled = true;
        closeIso();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (!query.isEmpty()) {
            searchBox.setText("");
        } else if (!currentPath.isEmpty()) {
            navigate(parentOf(currentPath));
        } else {
            super.onBackPressed();
        }
    }

    // ================================================================ UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button smallButton(String text, View.OnClickListener l, float weight) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(l);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{-android.R.attr.state_enabled}, roundRect(C_DISABLED, 10));
        bg.addState(new int[]{android.R.attr.state_pressed}, roundRect(C_PURPLE_DARK, 10));
        bg.addState(new int[]{}, roundRect(C_PURPLE, 10));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{C_DISABLED_TEXT, C_TEXT}));
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                weight > 0 ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(8), dp(24), dp(8), dp(8));

        // toolbar
        LinearLayout bar1 = new LinearLayout(this);
        bar1.setOrientation(LinearLayout.HORIZONTAL);
        bar1.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Xiso Extractor");
        title.setTextSize(20);
        title.setTextColor(C_TEXT);
        title.setPadding(dp(8), 0, 0, 0);
        bar1.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar1.addView(smallButton("Buka ISO", v -> pickIso(), 0));
        bar1.addView(smallButton("Tujuan", v -> pickDir(), 0));
        root.addView(bar1);

        infoText = new TextView(this);
        infoText.setTextSize(12);
        infoText.setTextColor(C_TEXT2);
        infoText.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(infoText);

        searchBox = new EditText(this);
        searchBox.setHint("Cari file di dalam ISO…");
        searchBox.setSingleLine(true);
        searchBox.setTextSize(14);
        searchBox.setTextColor(C_TEXT);
        searchBox.setHintTextColor(C_TEXT2);
        searchBox.setBackground(roundRect(C_SURFACE, 10));
        searchBox.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbp.setMargins(dp(3), dp(4), dp(3), dp(4));
        searchBox.setLayoutParams(sbp);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                rebuildRows();
            }
        });
        root.addView(searchBox);

        pathText = new TextView(this);
        pathText.setTextSize(13);
        pathText.setTypeface(Typeface.MONOSPACE);
        pathText.setPadding(dp(8), dp(6), dp(8), dp(6));
        pathText.setBackgroundColor(C_SURFACE);
        pathText.setTextColor(C_PURPLE_LIGHT);
        pathText.setEllipsize(TextUtils.TruncateAt.START);
        pathText.setSingleLine(true);
        root.addView(pathText);

        FrameLayout listFrame = new FrameLayout(this);
        listView = new ListView(this);
        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setDivider(new ColorDrawable(0x22FFFFFF));
        listView.setDividerHeight(1);
        listView.setOnItemClickListener((p, v, pos, id) -> onRowClick(rows.get(pos)));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            Row r = rows.get(pos);
            if (r.e != null) showMenu(r.e);
            return true;
        });
        listFrame.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView empty = new TextView(this);
        empty.setText("Belum ada ISO.\nTekan \"Buka ISO\" untuk memulai.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(C_TEXT2);
        listFrame.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView.setEmptyView(empty);
        root.addView(listFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // progress panel
        progressPanel = new LinearLayout(this);
        progressPanel.setOrientation(LinearLayout.VERTICAL);
        progressPanel.setVisibility(View.GONE);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(C_PURPLE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(C_DISABLED));
        progressPanel.addView(bar);
        currentText = new TextView(this);
        currentText.setTextSize(12);
        currentText.setTextColor(C_TEXT);
        progressPanel.addView(currentText);
        Button cancel = smallButton("Batal", v -> cancelled = true, 0);
        progressPanel.addView(cancel);
        root.addView(progressPanel);

        statusText = new TextView(this);
        statusText.setTextSize(12);
        statusText.setTextColor(C_PURPLE_LIGHT);
        statusText.setMaxLines(3);
        statusText.setPadding(dp(8), dp(2), dp(8), dp(2));
        root.addView(statusText);

        // bottom actions
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        btnSelectAll = smallButton("Pilih semua", v -> toggleSelectAll(), 1f);
        btnExtractSel = smallButton("Ekstrak (0)", v -> extractSelected(), 1f);
        btnExtractAll = smallButton("Ekstrak semua", v -> extractAll(), 1f);
        actions.addView(btnSelectAll);
        actions.addView(btnExtractSel);
        actions.addView(btnExtractAll);
        root.addView(actions);

        setContentView(root);
    }

    private final class RowHolder {
        ImageView icon;
        TextView name, sub;
        CheckBox cb;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            RowHolder h;
            if (convert == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(8), dp(4), dp(8));
                h = new RowHolder();
                h.icon = new ImageView(MainActivity.this);
                row.addView(h.icon, new LinearLayout.LayoutParams(dp(40), dp(28)));
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                h.name = new TextView(MainActivity.this);
                h.name.setTextSize(16);
                h.name.setTextColor(C_TEXT);
                h.name.setMaxLines(2);
                h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                h.sub = new TextView(MainActivity.this);
                h.sub.setTextSize(12);
                h.sub.setTextColor(C_TEXT2);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.START);
                col.addView(h.name);
                col.addView(h.sub);
                row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.cb = new CheckBox(MainActivity.this);
                h.cb.setFocusable(false);
                h.cb.setButtonTintList(new ColorStateList(
                        new int[][]{{android.R.attr.state_checked}, {}},
                        new int[]{C_PURPLE_LIGHT, C_TEXT2}));
                row.addView(h.cb);
                row.setTag(h);
                convert = row;
            } else {
                h = (RowHolder) convert.getTag();
            }

            final Row r = rows.get(pos);
            if (r.e == null) {
                h.icon.setImageResource(R.drawable.ic_up);
                convert.setBackgroundColor(Color.TRANSPARENT);
                h.name.setText("..");
                h.sub.setText("Folder atas");
                h.cb.setVisibility(View.GONE);
                return convert;
            }
            final XisoReader.Entry e = r.e;
            h.icon.setImageResource(e.dir ? R.drawable.ic_folder : R.drawable.ic_file);
            h.name.setText(e.name());
            String sub;
            if (e.dir) {
                long[] st = dirStats.get(e.path);
                sub = st == null ? "kosong" : st[0] + " file • " + fmt(st[1]);
            } else {
                sub = fmt(e.size);
            }
            if (!query.isEmpty()) sub += " • /" + e.parent();
            h.sub.setText(sub);
            h.cb.setVisibility(View.VISIBLE);
            boolean sel = selected.contains(e.path);
            h.cb.setChecked(sel);
            convert.setBackgroundColor(sel ? C_SELECTED : Color.TRANSPARENT);
            final View rowView = convert;
            h.cb.setOnClickListener(v -> {
                boolean on = ((CheckBox) v).isChecked();
                if (on) selected.add(e.path);
                else selected.remove(e.path);
                rowView.setBackgroundColor(on ? C_SELECTED : Color.TRANSPARENT);
                updateButtons();
            });
            return convert;
        }
    }

    private void status(final String s) {
        runOnUiThread(() -> statusText.setText(s));
    }

    private void toast(final String s) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show());
    }

    private void refreshInfo() {
        StringBuilder sb = new StringBuilder();
        if (reader != null) {
            long[] st = dirStats.get("");
            sb.append("ISO: ").append(isoName).append(" • ")
                    .append(st == null ? 0 : st[0]).append(" file • ")
                    .append(fmt(st == null ? 0 : st[1]));
        } else {
            sb.append("ISO: -");
        }
        sb.append("\nTujuan: ").append(treeUri == null ? "belum dipilih"
                : Uri.decode(DocumentsContract.getTreeDocumentId(treeUri)));
        infoText.setText(sb.toString());
    }

    private void updateButtons() {
        int n = selected.size();
        btnExtractSel.setText("Ekstrak (" + n + ")");
        btnExtractSel.setEnabled(!running && n > 0);
        btnExtractAll.setEnabled(!running && reader != null);
        btnSelectAll.setEnabled(reader != null);
    }

    private void setRunning(final boolean on) {
        running = on;
        runOnUiThread(() -> {
            progressPanel.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            updateButtons();
        });
    }

    // ================================================================ browsing

    private void rebuildRows() {
        rows.clear();
        if (reader != null) {
            if (!query.isEmpty()) {
                String q = query.toLowerCase(Locale.ROOT);
                int n = 0;
                for (XisoReader.Entry e : all) {
                    if (e.name().toLowerCase(Locale.ROOT).contains(q)) {
                        rows.add(new Row(e));
                        if (++n >= 1000) break;
                    }
                }
                pathText.setText("Hasil pencarian: " + rows.size() + (rows.size() >= 1000 ? "+" : ""));
            } else {
                if (!currentPath.isEmpty()) rows.add(new Row(null));
                List<XisoReader.Entry> c = children.get(currentPath);
                if (c != null) for (XisoReader.Entry e : c) rows.add(new Row(e));
                pathText.setText("/" + currentPath);
            }
        } else {
            pathText.setText("/");
        }
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private void navigate(String path) {
        currentPath = path;
        if (!query.isEmpty()) searchBox.setText("");   // its watcher rebuilds the list
        else rebuildRows();
        listView.setSelection(0);
    }

    private void onRowClick(Row r) {
        if (r.e == null) navigate(parentOf(currentPath));
        else if (r.e.dir) navigate(r.e.path);
        else preview(r.e);
    }

    private void toggleSelectAll() {
        boolean allSel = true;
        for (Row r : rows) if (r.e != null && !selected.contains(r.e.path)) { allSel = false; break; }
        for (Row r : rows) {
            if (r.e == null) continue;
            if (allSel) selected.remove(r.e.path);
            else selected.add(r.e.path);
        }
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private static String parentOf(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? "" : p.substring(0, i);
    }

    private void indexEntries(List<XisoReader.Entry> list) {
        all = list;
        byPath.clear();
        children.clear();
        dirStats.clear();
        children.put("", new ArrayList<XisoReader.Entry>());
        for (XisoReader.Entry e : list) {
            byPath.put(e.path, e);
            String par = e.parent();
            List<XisoReader.Entry> c = children.get(par);
            if (c == null) {
                c = new ArrayList<>();
                children.put(par, c);
            }
            c.add(e);
            if (e.dir && !children.containsKey(e.path)) children.put(e.path, new ArrayList<XisoReader.Entry>());
            if (!e.dir) {
                String p = par;
                while (true) {
                    long[] st = dirStats.get(p);
                    if (st == null) {
                        st = new long[2];
                        dirStats.put(p, st);
                    }
                    st[0]++;
                    st[1] += e.size;
                    if (p.isEmpty()) break;
                    p = parentOf(p);
                }
            }
        }
        Comparator<XisoReader.Entry> cmp = new Comparator<XisoReader.Entry>() {
            @Override public int compare(XisoReader.Entry a, XisoReader.Entry b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name().compareToIgnoreCase(b.name());
            }
        };
        for (List<XisoReader.Entry> c : children.values()) Collections.sort(c, cmp);
    }

    // ================================================================ opening an ISO

    private void pickIso() {
        if (running) {
            Toast.makeText(this, "Tunggu ekstrak selesai dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_ISO);
    }

    private void pickDir() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DIR);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_DIR && result != RESULT_OK) afterDestPick = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_ISO) {
            openIso(uri);
        } else if (req == REQ_DIR) {
            treeUri = uri;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getSharedPreferences("xiso", MODE_PRIVATE).edit().putString("tree", uri.toString()).apply();
            } catch (Exception ignored) {
                // permission for this session is enough
            }
            refreshInfo();
            Runnable r = afterDestPick;
            afterDestPick = null;
            if (r != null) r.run();
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null) {
            String last = uri.getLastPathSegment();
            if (last != null) name = last.substring(last.lastIndexOf('/') + 1);
        }
        return name != null ? name : "game.iso";
    }

    private void openIso(final Uri uri) {
        final String name = displayName(uri);
        status("Membuka " + name + "…");
        new Thread(() -> {
            ParcelFileDescriptor.AutoCloseInputStream in = null;
            try {
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                if (pfd == null) throw new IOException("Tidak bisa membuka file ISO");
                in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                final XisoReader r = new XisoReader(in.getChannel());
                final List<XisoReader.Entry> list = r.list(false);
                final ParcelFileDescriptor.AutoCloseInputStream stream = in;
                runOnUiThread(() -> setIso(stream, r, name, list));
            } catch (Throwable t) {
                if (in != null) try { in.close(); } catch (IOException ignored) { }
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                status("GAGAL membuka ISO: " + msg);
            }
        }, "xiso-open").start();
    }

    private void setIso(ParcelFileDescriptor.AutoCloseInputStream stream, XisoReader r, String name,
                        List<XisoReader.Entry> list) {
        closeIso();
        isoStream = stream;
        reader = r;
        isoName = name;
        selected.clear();
        currentPath = "";
        indexEntries(list);
        if (!query.isEmpty()) searchBox.setText("");
        refreshInfo();
        rebuildRows();
        status("ISO terbuka: " + list.size() + " entri"
                + (r.discOffset() != 0 ? " (partisi XGD, offset 0x" + Long.toHexString(r.discOffset()) + ")" : ""));
    }

    private void closeIso() {
        reader = null;
        if (isoStream != null) {
            try { isoStream.close(); } catch (IOException ignored) { }
            isoStream = null;
        }
    }

    // ================================================================ peek: menu, info, preview

    private void showMenu(final XisoReader.Entry e) {
        final String[] items = e.dir
                ? new String[]{"Buka", "Info", "Salin path", "Ekstrak folder ini"}
                : new String[]{"Pratinjau", "Info", "Salin path", "Ekstrak file ini"};
        new AlertDialog.Builder(this)
                .setTitle(e.name())
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        if (e.dir) navigate(e.path); else preview(e);
                    } else if (which == 1) {
                        showInfo(e);
                    } else if (which == 2) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("path", "/" + e.path));
                        Toast.makeText(this, "Path disalin", Toast.LENGTH_SHORT).show();
                    } else {
                        extractOne(e);
                    }
                })
                .show();
    }

    private void showInfo(XisoReader.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Path: /").append(e.path).append('\n');
        if (e.dir) {
            long[] st = dirStats.get(e.path);
            sb.append("Jenis: folder\nIsi: ").append(st == null ? 0 : st[0]).append(" file, ")
                    .append(fmt(st == null ? 0 : st[1]));
        } else {
            sb.append("Jenis: file\nUkuran: ").append(fmt(e.size)).append(" (").append(e.size).append(" byte)\n")
                    .append("Posisi di ISO: 0x").append(Long.toHexString(e.position()));
        }
        new AlertDialog.Builder(this).setTitle(e.name()).setMessage(sb.toString())
                .setPositiveButton("Tutup", null).show();
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private void preview(final XisoReader.Entry e) {
        final XisoReader rd = reader;
        if (rd == null) return;
        status("Membaca " + e.name() + "…");
        new Thread(() -> {
            try {
                if (IMAGE_EXT.contains(ext(e.name())) && e.size > 0 && e.size <= 24L * 1024 * 1024) {
                    byte[] data = rd.read(e, 0, (int) e.size);
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    if (o.outWidth > 0 && o.outHeight > 0) {
                        int sample = 1;
                        while (o.outWidth / sample > 2048 || o.outHeight / sample > 2048) sample *= 2;
                        BitmapFactory.Options o2 = new BitmapFactory.Options();
                        o2.inSampleSize = sample;
                        final Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o2);
                        if (bmp != null) {
                            runOnUiThread(() -> showImage(e, bmp));
                            return;
                        }
                    }
                }
                byte[] head = rd.read(e, 0, 64 * 1024);
                final String body;
                if (head.length == 0) {
                    body = "(file kosong)";
                } else if (looksLikeText(head)) {
                    body = decodeText(head) + (e.size > head.length ? "\n\n… (hanya 64 KB pertama ditampilkan)" : "");
                } else {
                    body = "File biner, tampilan hex (2 KB pertama):\n\n" + hexDump(head, 2048);
                }
                runOnUiThread(() -> showText(e, body));
            } catch (Throwable t) {
                status("GAGAL membaca: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
            }
        }, "xiso-preview").start();
    }

    private void showText(final XisoReader.Entry e, String body) {
        status("");
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(11);
        tv.setTextColor(0xFFEAEFFF);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setText(body);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(e.name() + " (" + fmt(e.size) + ")")
                .setView(sv)
                .setPositiveButton("Ekstrak", (d, w) -> extractOne(e))
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void showImage(final XisoReader.Entry e, Bitmap bmp) {
        status("");
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setAdjustViewBounds(true);
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(iv);
        new AlertDialog.Builder(this)
                .setTitle(e.name() + " (" + bmp.getWidth() + "x" + bmp.getHeight() + ")")
                .setView(sv)
                .setPositiveButton("Ekstrak", (d, w) -> extractOne(e))
                .setNegativeButton("Tutup", null)
                .show();
    }

    private static boolean looksLikeText(byte[] b) {
        if (b.length >= 2 && (((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
                || ((b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF))) return true;   // UTF-16 BOM
        int n = Math.min(b.length, 4096);
        int bad = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) return false;
            if (c < 9 || (c > 13 && c < 32 && c != 27)) bad++;
        }
        return bad * 50 < n;   // under 2% control characters
    }

    private static String decodeText(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF)
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String hexDump(byte[] b, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i += 16) {
            sb.append(String.format(Locale.US, "%06X  ", i));
            for (int j = 0; j < 16; j++) {
                if (i + j < n) sb.append(String.format(Locale.US, "%02X ", b[i + j] & 0xFF));
                else sb.append("   ");
            }
            sb.append(' ');
            for (int j = 0; j < 16 && i + j < n; j++) {
                int c = b[i + j] & 0xFF;
                sb.append(c >= 32 && c < 127 ? (char) c : '.');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ================================================================ extraction

    /** Runs r once a destination folder is known (asks for one if needed). */
    private void ensureDest(Runnable r) {
        if (treeUri != null) {
            r.run();
        } else {
            afterDestPick = r;
            Toast.makeText(this, "Pilih folder tujuan dulu", Toast.LENGTH_SHORT).show();
            pickDir();
        }
    }

    private boolean hasSelectedAncestor(String path, Collection<String> paths) {
        String p = parentOf(path);
        while (!p.isEmpty()) {
            if (paths.contains(p)) return true;
            p = parentOf(p);
        }
        return false;
    }

    /** Selected items go straight into the destination, each under its own name. */
    private List<Job> jobsFor(Collection<String> paths) {
        List<Job> jobs = new ArrayList<>();
        for (String path : paths) {
            XisoReader.Entry e = byPath.get(path);
            if (e == null || hasSelectedAncestor(path, paths)) continue;
            int base = e.path.lastIndexOf('/') + 1;
            jobs.add(new Job(e, e.path.substring(base)));
            if (e.dir) {
                String prefix = e.path + "/";
                for (XisoReader.Entry x : all) {
                    if (x.path.startsWith(prefix)) jobs.add(new Job(x, x.path.substring(base)));
                }
            }
        }
        return jobs;
    }

    private void extractSelected() {
        if (running || reader == null || selected.isEmpty()) return;
        final List<Job> jobs = jobsFor(new ArrayList<>(selected));
        ensureDest(() -> startJobs(jobs, null));
    }

    private void extractOne(XisoReader.Entry e) {
        if (running || reader == null) {
            Toast.makeText(this, "Tunggu ekstrak yang berjalan selesai", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Job> jobs = jobsFor(Collections.singletonList(e.path));
        ensureDest(() -> startJobs(jobs, null));
    }

    private void extractAll() {
        if (running || reader == null) return;
        final boolean[] skip = {true};
        new AlertDialog.Builder(this)
                .setTitle("Ekstrak semua")
                .setMultiChoiceItems(new String[]{"Lewati folder $SystemUpdate (hemat ruang)"}, skip,
                        (d, which, checked) -> skip[0] = checked)
                .setPositiveButton("Ekstrak", (d, w) -> {
                    List<Job> jobs = new ArrayList<>();
                    for (XisoReader.Entry e : all) {
                        if (skip[0] && (e.path.equals("$SystemUpdate") || e.path.startsWith("$SystemUpdate/"))) continue;
                        jobs.add(new Job(e, e.path));
                    }
                    String base = isoName;
                    int dot = base.lastIndexOf('.');
                    if (dot > 0) base = base.substring(0, dot);
                    final String folder = base;
                    final List<Job> js = jobs;
                    ensureDest(() -> startJobs(js, folder));
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void startJobs(final List<Job> jobs, final String subfolder) {
        if (running || reader == null || treeUri == null) return;
        final XisoReader rd = reader;
        final Uri tree = treeUri;
        long t = 0;
        for (Job j : jobs) if (!j.e.dir) t += j.e.size;
        final long total = t;
        cancelled = false;
        bar.setProgress(0);
        currentText.setText(" ");
        setRunning(true);
        new Thread(() -> runJobs(rd, tree, jobs, subfolder, total), "xiso-extract").start();
    }

    private DirCtx ensureDir(ContentResolver cr, Uri tree, Map<String, DirCtx> dirs, String rel) throws IOException {
        DirCtx c = dirs.get(rel);
        if (c != null) return c;
        int slash = rel.lastIndexOf('/');
        DirCtx parent = ensureDir(cr, tree, dirs, slash < 0 ? "" : rel.substring(0, slash));
        String leaf = slash < 0 ? rel : rel.substring(slash + 1);
        parent.load(cr, tree);
        Uri existing = parent.dirs.get(leaf);
        if (existing != null) {
            c = new DirCtx(existing, false);
        } else {
            Uri d = DocumentsContract.createDocument(cr, parent.uri, Document.MIME_TYPE_DIR, leaf);
            if (d == null) throw new IOException("Gagal membuat folder " + rel);
            parent.dirs.put(leaf, d);
            c = new DirCtx(d, true);
        }
        dirs.put(rel, c);
        return c;
    }

    private void runJobs(XisoReader rd, Uri tree, List<Job> jobs, String subfolder, final long total) {
        final ContentResolver cr = getContentResolver();
        Uri currentDoc = null;
        try {
            Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            Map<String, DirCtx> dirs = new HashMap<>();
            DirCtx top = new DirCtx(rootDoc, false);
            dirs.put("", top);
            if (subfolder != null) {
                top = ensureDir(cr, tree, dirs, subfolder);
                dirs.put("", top);
            }

            final long[] done = {0};
            final long[] lastUi = {0};
            final long started = SystemClock.elapsedRealtime();
            byte[] buf = new byte[1 << 20];
            int files = 0;

            for (Job j : jobs) {
                if (cancelled) throw new CancellationException();
                String rel = j.rel;
                if (j.e.dir) {
                    ensureDir(cr, tree, dirs, rel);
                    continue;
                }
                int slash = rel.lastIndexOf('/');
                String leaf = slash < 0 ? rel : rel.substring(slash + 1);
                DirCtx ctx = ensureDir(cr, tree, dirs, slash < 0 ? "" : rel.substring(0, slash));
                ctx.load(cr, tree);
                Uri old = ctx.files.remove(leaf);
                if (old != null) deleteQuietly(cr, old);   // same name already there: replace it

                // octet-stream keeps the file name exactly as it is (no extension added)
                Uri f = DocumentsContract.createDocument(cr, ctx.uri, "application/octet-stream", leaf);
                if (f == null) throw new IOException("Gagal membuat file " + rel);
                ctx.files.put(leaf, f);
                currentDoc = f;
                final String shown = rel;
                try (OutputStream os = cr.openOutputStream(f, "w")) {
                    if (os == null) throw new IOException("Gagal menulis " + rel);
                    rd.extract(j.e, os, buf, n -> {
                        done[0] += n;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUi[0] > 250) {
                            lastUi[0] = now;
                            publish(done[0], total, shown, now - started);
                        }
                    }, () -> cancelled);
                }
                currentDoc = null;
                files++;
            }
            publish(total, total, "", SystemClock.elapsedRealtime() - started);
            status("Selesai: " + files + " file, " + fmt(total) + " diekstrak.");
            toast("Selesai! " + files + " file diekstrak");
        } catch (CancellationException ce) {
            deleteQuietly(cr, currentDoc);
            status("Dibatalkan. File yang sudah selesai tetap ada di folder tujuan.");
        } catch (Throwable t) {
            deleteQuietly(cr, currentDoc);
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            status("GAGAL: " + msg);
        } finally {
            setRunning(false);
        }
    }

    private void publish(final long done, final long total, final String file, final long elapsedMs) {
        runOnUiThread(() -> {
            int per = total > 0 ? (int) (done * 1000L / total) : 1000;
            bar.setProgress(per);
            double mbps = elapsedMs > 0 ? (done / 1048576.0) / (elapsedMs / 1000.0) : 0;
            currentText.setText(String.format(Locale.US, "%s / %s  (%.1f%%)  %.1f MB/s\n%s",
                    fmt(done), fmt(total), per / 10.0, mbps, file));
        });
    }

    private static void deleteQuietly(ContentResolver cr, Uri doc) {
        if (doc == null) return;
        try {
            DocumentsContract.deleteDocument(cr, doc);
        } catch (Exception ignored) {
        }
    }

    private static String fmt(long b) {
        if (b >= (1L << 30)) return String.format(Locale.US, "%.2f GB", b / (double) (1L << 30));
        if (b >= (1L << 20)) return String.format(Locale.US, "%.1f MB", b / (double) (1L << 20));
        if (b >= 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return b + " B";
    }
}
