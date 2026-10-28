package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV's top shelf: the focused dock app's Continue watching episodes (WatchNext) in a row above the dock.
 * Up from the dock enters the row, Down leaves it, OK resumes the episode through the app's own intent.
 */
final class TopShelf {

    private static final int MAX = 8, VISIBLE = 4;
    private static final long MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000;

    private final Activity activity;
    private final float density;
    private final FrameLayout root;
    private final LinearLayout row;
    private final TextView heading;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService posters = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private Map<String, List<WatchNext.Item>> byPackage = Collections.emptyMap();
    private String shownPkg;
    private float dockTop;
    private View returnFocus;

    TopShelf(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        density = activity.getResources().getDisplayMetrics().density;
        heading = new TextView(activity);
        heading.setTextColor(0xDDFFFFFF);
        heading.setTextSize(13);
        heading.setLetterSpacing(0.15f);
        heading.setAllCaps(true);
        heading.setShadowLayer(4 * density, 0, density, 0xAA000000);
        heading.setAlpha(0f);
        row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setAlpha(0f);
        root.addView(heading, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Re-reads Continue watching off the main thread; the shelf on screen is rebuilt with it. */
    void refresh() {
        new Thread(() -> {
            long now = System.currentTimeMillis();
            Map<String, List<WatchNext.Item>> map = new HashMap<>();
            for (WatchNext.Item it : WatchNext.read(activity)) {
                if (it.engaged > 0 && now - it.engaged > MAX_AGE_MS) continue;
                if (it.duration > 0 && it.position * 100 / it.duration >= 95) continue;   // finished
                List<WatchNext.Item> list = map.get(it.pkg);
                if (list == null) map.put(it.pkg, list = new ArrayList<>());
                list.add(it);
            }
            for (List<WatchNext.Item> list : map.values()) {
                Collections.sort(list, (a, b) -> Long.compare(b.engaged, a.engaged));
                while (list.size() > MAX) list.remove(list.size() - 1);
            }
            main.post(() -> {
                byPackage = map;
                String pkg = shownPkg;
                shownPkg = null;
                if (pkg != null) show(pkg, dockTop);
            });
        }, "top-shelf").start();
    }

    boolean hasItems(String pkg) {
        List<WatchNext.Item> list = byPackage.get(pkg);
        return list != null && !list.isEmpty();
    }

    /** The dock tile for pkg has focus; its top edge is at dockTopInRoot. */
    void show(String pkg, float dockTopInRoot) {
        dockTop = dockTopInRoot;
        if (pkg != null && pkg.equals(shownPkg)) return;
        shownPkg = pkg;
        List<WatchNext.Item> list = pkg == null ? null : byPackage.get(pkg);
        if (list == null || list.isEmpty()) {
            fade(0f);
            return;
        }
        int width = root.getWidth();
        int side = Math.round(width * 0.06f), gap = Math.round(24 * density);
        int cardW = (width - 2 * side - (VISIBLE - 1) * gap) / VISIBLE, cardH = cardW * 9 / 16;
        row.removeAllViews();
        int room = Math.round(20 * density);   // the focused card grows 7%; the row must not clip it
        row.setPadding(side, room, side, room);
        for (int i = 0; i < list.size(); i++) {
            View card = card(list.get(i), cardW, cardH, i == 0 ? 0 : gap);
            row.addView(card);
            // Cascade: each card rises and fades in a little after the one before it.
            card.setAlpha(0f);
            card.setTranslationY(28 * density);
            card.animate().alpha(1f).translationY(0f).setStartDelay(60L * Math.min(i, VISIBLE))
                    .setDuration(320).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        }
        int labelH = Math.round(30 * density);
        float rowY = dockTopInRoot - 44 * density - cardH - labelH;
        row.setTranslationY(rowY - room);
        row.setTranslationX(0);
        heading.setText(label(pkg) + "  ·  Weiterschauen");
        heading.setTranslationX(side);
        heading.setTranslationY(rowY - 30 * density);
        fade(1f);
    }

    void hide() {
        shownPkg = null;
        fade(0f);
    }

    boolean hasFocus() {
        return row.hasFocus();
    }

    /** Up from the dock: into the row. Returns false when there is nothing to enter. */
    boolean enter(View from) {
        if (shownPkg == null || row.getChildCount() == 0 || row.getAlpha() < 0.5f) return false;
        returnFocus = from;
        setFocusable(true);
        ((ViewGroup) row.getChildAt(0)).getChildAt(0).requestFocus();
        return true;
    }

    /** Keys while a shelf card has focus; Down goes back to the dock tile it came from. */
    boolean onKey(KeyEvent e) {
        if (!row.hasFocus()) return false;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) leave();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) return true;   // the clock and buttons stay reachable from the dock
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = row.getFocusedChild();
            if (f != null && row.indexOfChild(f) == row.getChildCount() - 1) return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.getAction() == KeyEvent.ACTION_DOWN) {
            View f = row.getFocusedChild();
            if (f != null && row.indexOfChild(f) == 0) return true;
        }
        return false;
    }

    void destroy() {
        posters.shutdownNow();
    }

    private void fade(float to) {
        row.animate().cancel();
        heading.animate().cancel();
        row.animate().alpha(to).setDuration(160).start();
        heading.animate().alpha(to).setDuration(160).start();
        if (to == 0f && row.hasFocus()) leave();
    }

    private void leave() {
        if (returnFocus != null) returnFocus.requestFocus();
        setFocusable(false);
    }

    private void setFocusable(boolean on) {
        for (int i = 0; i < row.getChildCount(); i++) ((ViewGroup) row.getChildAt(i)).getChildAt(0).setFocusable(on);
    }

    private String label(String pkg) {
        try {
            return activity.getPackageManager().getApplicationLabel(
                    activity.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private View card(WatchNext.Item it, int w, int h, int marginStart) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = marginStart;
        box.setLayoutParams(blp);

        float radius = 12 * density;
        FrameLayout art = new FrameLayout(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF22252B);
        bg.setCornerRadius(radius);
        art.setBackground(bg);
        art.setClipToOutline(true);
        art.setElevation(6 * density);
        art.setFocusable(false);   // only after Up from the dock (enter), so Home never starts in the shelf
        ImageView img = new ImageView(activity);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (it.duration > 0 && it.position > 0) {
            View track = new View(activity), fill = new View(activity);
            GradientDrawable t = new GradientDrawable();
            t.setColor(0x4DFFFFFF);
            t.setCornerRadius(4 * density);
            track.setBackground(t);
            GradientDrawable f = new GradientDrawable();
            f.setColor(Color.WHITE);
            f.setCornerRadius(4 * density);
            fill.setBackground(f);
            int barW = w - Math.round(28 * density), barH = Math.round(5 * density);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(barW, barH, Gravity.BOTTOM | Gravity.START);
            tl.leftMargin = Math.round(14 * density);
            tl.bottomMargin = Math.round(12 * density);
            art.addView(track, tl);
            FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(
                    Math.max(barH, (int) (barW * it.position / it.duration)), barH, Gravity.BOTTOM | Gravity.START);
            fl.leftMargin = tl.leftMargin;
            fl.bottomMargin = tl.bottomMargin;
            art.addView(fill, fl);
        }
        View rim = new View(activity);
        GradientDrawable r = new GradientDrawable();
        r.setColor(Color.TRANSPARENT);
        r.setStroke(Math.round(3 * density), Color.WHITE);
        r.setCornerRadius(radius);
        rim.setBackground(r);
        rim.setAlpha(0f);
        art.addView(rim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(art, new LinearLayout.LayoutParams(w, h));

        TextView label = new TextView(activity);
        String se = it.season > 0 && it.episode > 0 ? "  S" + it.season + " · E" + it.episode : "";
        String title = it.title == null ? "" : it.title;
        label.setText(title + se);
        label.setTextColor(0xEEFFFFFF);
        label.setTextSize(14);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(4 * density, 0, density, 0xAA000000);
        label.setPadding(Math.round(4 * density), Math.round(8 * density), 0, 0);
        box.addView(label);

        art.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.07f : 1f;
            box.animate().scaleX(s).scaleY(s).setDuration(140).start();
            rim.animate().alpha(focused ? 1f : 0f).setDuration(140).start();
            art.setElevation((focused ? 14 : 6) * density);
            if (focused) {   // keep the focused card on screen once the row is wider than the screen
                int left = box.getLeft() + Math.round(row.getTranslationX()), side = row.getPaddingLeft();
                int right = left + box.getWidth();
                float dx = 0;
                if (right > root.getWidth() - side) dx = root.getWidth() - side - right;
                else if (left < side) dx = side - left;
                if (dx != 0) row.animate().translationXBy(dx).setDuration(180).start();
            }
        });
        art.setOnClickListener(v -> open(it));
        box.setPivotY(h);
        loadPoster(it.poster, img, w);
        return box;
    }

    private void open(WatchNext.Item it) {
        try {
            Intent i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
            if (launch == null) launch = activity.getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (launch != null) activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private void loadPoster(String uri, ImageView into, int width) {
        if (uri == null) return;
        Bitmap hit = cache.get(uri);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        into.setTag(uri);
        posters.execute(() -> {
            Bitmap bmp = null;
            try {
                byte[] data;
                if (uri.startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream()) {
                        data = readAll(in);
                    } finally {
                        c.disconnect();
                    }
                } else {
                    try (InputStream in = activity.getContentResolver().openInputStream(android.net.Uri.parse(uri))) {
                        data = in == null ? null : readAll(in);
                    }
                }
                if (data != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= width) sample *= 2;
                    o.inJustDecodeBounds = false;
                    o.inSampleSize = sample;
                    bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                }
            } catch (Exception ignored) {
                // no poster: the card keeps its dark background
            }
            Bitmap done = bmp;
            if (done == null) return;
            cache.put(uri, done);
            main.post(() -> {
                if (uri.equals(into.getTag())) into.setImageBitmap(done);
            });
        });
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
