package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.RectF;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Displays and rotates a theme's local wallpaper images while Home is visible. */
final class WallpaperPlayer {
    private final FrameLayout root;
    /**
     * Motion (this fork): two layers that crossfade, each slowly zooming and drifting (Ken Burns) inside a stage that
     * shifts with dock focus (parallax). The dock's frost is live: a tiny blurred copy of each picture, in a twin
     * layer clipped to the band, with the same motion, so it always matches the picture behind the band.
     */
    private final FrameLayout stage, frostClip, frostStage;
    private final ImageView[] layers = new ImageView[2], frosts = new ImageView[2];
    private final Bitmap[] blurOf = new Bitmap[2];
    private final Map<Bitmap, Bitmap> blurs = new ConcurrentHashMap<>();
    private int front = -1;
    private final RectF frostRect = new RectF();
    private float frostRadius, parallax;
    private static final float BASE_SCALE = 1.06f, END_SCALE = 1.14f;
    private static final long FADE_MS = 1600;
    private final int backgroundColor;
    private final List<File> files;
    private final long interval;
    private final String order;
    private final SharedPreferences preferences;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final int width;
    /** config wallpaper_true_color: ARGB_8888 instead of RGB_565. */
    private final Bitmap.Config bitmapConfig;
    private final int height;
    /** wallpaper_dim as an ARGB black, painted into each picture once instead of a full-screen shade view. */
    private final int dimColor;
    /** The picture on screen, released once the next one has been drawn. */
    private Bitmap current;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private String displayed = "";
    /** wallpaper_captions: the shown picture's name from wallpapers/captions.txt; null when off or there are none. */
    private final TextView caption;
    private final Map<String, String> names;
    /** wallpaper_moods: the collection's groups by time of day; empty rotates through all of it. */
    private final Map<String, Set<String>> moods;
    /** config.xml latitude and longitude for the sun's times, and today's {sunrise, sunset} once worked out. */
    private final String latitude, longitude;
    private LocalDate sunDay;
    private int[] sun;

    WallpaperPlayer(Context context, FrameLayout root, Config config) {
        this.root = root;
        backgroundColor = config.backgroundColor;
        files = new ArrayList<>(config.wallpapers);
        interval = config.wallpaperRotationMinutes * 60_000L;
        bitmapConfig = config.wallpaperTrueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        // The dim is painted into the picture (decode), so no full-screen shade layer is drawn on every frame.
        dimColor = config.wallpaperDim > 0 ? (config.wallpaperDim * 255 / 100) << 24 : 0;
        order = config.wallpaperOrder;
        preferences = context.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE);
        moods = config.wallpaperMoods;
        latitude = moods.isEmpty() ? "" : context.getString(R.string.latitude);
        longitude = moods.isEmpty() ? "" : context.getString(R.string.longitude);
        // Playback state belongs to one collection. Changing the collection starts from its first
        // picture instead of resuming a filename that belonged to the previous one.
        String collection = collectionKey(files);
        if (!collection.equals(preferences.getString("collection", ""))) {
            preferences.edit().putString("collection", collection)
                    .remove("image").remove("changed").apply();
        }
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        width = metrics.widthPixels;
        height = metrics.heightPixels;
        stage = new FrameLayout(context);
        frostStage = new FrameLayout(context);
        frostClip = new FrameLayout(context);
        frostClip.setVisibility(View.GONE);
        // The layers are zoomed past the screen; let the stages draw that overscan when parallax shifts them.
        stage.setClipChildren(false);
        frostStage.setClipChildren(false);
        root.setClipChildren(false);
        frostClip.setClipToOutline(true);
        frostClip.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), frostRadius);
            }
        });
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(1.4f);
        for (int i = 0; i < 2; i++) {
            layers[i] = new ImageView(context);
            layers[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            layers[i].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            layers[i].setAlpha(0f);
            stage.addView(layers[i], new FrameLayout.LayoutParams(width, height));
            frosts[i] = new ImageView(context);
            frosts[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            frosts[i].setColorFilter(new ColorMatrixColorFilter(saturate));
            frosts[i].setAlpha(0f);
            frostStage.addView(frosts[i], new FrameLayout.LayoutParams(width, height));
        }
        frostClip.addView(frostStage, new FrameLayout.LayoutParams(width, height));
        names = config.wallpaperCaptions && config.wallpaperEnabled && !files.isEmpty()
                ? WallpaperCaptions.read(files.get(0).getParentFile()) : Collections.emptyMap();
        caption = names.isEmpty() ? null : makeCaption(context, root);
        if (config.wallpaperEnabled && !files.isEmpty()) {
            root.addView(stage, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(frostClip, 1, new FrameLayout.LayoutParams(0, 0));
            showFirst();
        }
    }

    /**
     * The picture Home opens with is decoded right here, on the main thread, as the single wallpaper always was:
     * Home's first frame already has it instead of drawing the plain colour and the picture popping in later.
     * Only the later changes of a rotation decode in the background.
     */
    private void showFirst() {
        String previous = preferences.getString("image", "");
        List<File> playable = playable();
        File first = WallpaperRotation.find(playable, previous);
        if (first == null) first = WallpaperRotation.pick(playable, previous, order, random);
        while (first != null) {
            Bitmap bitmap = decode(first);
            if (bitmap != null) {
                show(bitmap, false);
                // The picture covers the whole screen: the root's plain colour under it is overdraw.
                root.setBackground(null);
                displayed = first.getName();
                showCaption(false);
                if (!displayed.equals(previous)) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
                return;
            }
            files.remove(first);   // unreadable or over 4K: try another, or keep the plain colour
            playable = playable();
            first = WallpaperRotation.pick(playable, previous, order, random);
        }
    }

    void start() {
        active = true;
        update();
    }

    void stop() {
        active = false;
    }

    /** Drops the wallpaper texture when Home is hidden and Android asks the process to free memory. */
    void releaseForMemoryPressure() {
        if (current == null) return;
        for (int i = 0; i < 2; i++) {
            layers[i].animate().cancel();
            layers[i].setImageDrawable(null);
            frosts[i].setImageDrawable(null);
            blurOf[i] = null;
        }
        front = -1;
        // The renderer may still have a queued frame that references this hardware bitmap. Drop our references and
        // let Android reclaim its native buffer after rendering releases it instead of recycling it mid-frame.
        current = null;
        displayed = "";
        root.setBackgroundColor(backgroundColor);
    }

    /** The pictures the rotation picks from now: wallpaper_moods' group for the time of day, or the whole collection. */
    private List<File> playable() {
        if (moods.isEmpty()) return files;
        LocalDateTime now = LocalDateTime.now();
        if (!now.toLocalDate().equals(sunDay)) {
            sunDay = now.toLocalDate();
            sun = WallpaperMood.sun(sunDay, latitude, longitude, ZoneId.systemDefault());
        }
        return WallpaperMood.select(files, moods, WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    void close() {
        active = false;
        closed = true;
        decoder.shutdownNow();
    }

    void update() {
        if (!active || closed || loading || files.isEmpty()) return;
        String previous = preferences.getString("image", "");
        // A picture outside the time of day's group (the group changed) is replaced at once, not after the interval.
        List<File> playable = playable();
        File selected = WallpaperRotation.find(playable, previous);
        long changed = preferences.getLong("changed", 0);
        if (selected == null || WallpaperRotation.due(changed, System.currentTimeMillis(), interval)) {
            selected = WallpaperRotation.pick(playable, previous, order, random);
        }
        if (selected == null || selected.getName().equals(displayed)) return;
        File next = selected;
        loading = true;
        decoder.execute(() -> {
            Bitmap bitmap = decode(next);
            root.post(() -> {
                loading = false;
                if (closed || !active) {
                    if (bitmap != null) bitmap.recycle();
                    return;
                }
                if (bitmap == null) {
                    files.remove(next);
                    Log.w("TvHome.Wallpaper", "Skipping unreadable image: " + next.getName());
                    update();
                    return;
                }
                show(bitmap, true);
                displayed = next.getName();
                showCaption(true);
                if (!displayed.equals(previous) || changed == 0) {
                    preferences.edit().putString("image", displayed)
                            .putLong("changed", System.currentTimeMillis()).apply();
                }
            });
        });
    }

    /**
     * Small light text under the top-right buttons, above everything else and never focusable. It sits where the
     * top bar ends in every layout, so it does not cover the clock, the stats line or the tiles.
     */
    private static TextView makeCaption(Context context, FrameLayout root) {
        float dp = context.getResources().getDisplayMetrics().density;
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setTextColor(0xD9FFFFFF);
        text.setShadowLayer(6 * dp, 0, dp, 0xCC000000);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setMaxWidth(Math.round(520 * dp));
        text.setFocusable(false);
        text.setAlpha(0f);
        try {
            text.setTypeface(UiFont.get(context, R.string.font_regular));
        } catch (Exception ignored) {
            // the system font is fine
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        params.topMargin = Math.round(110 * dp);
        params.setMarginEnd(Math.round(62 * dp));
        root.addView(text, params);
        return text;
    }

    /** The displayed picture's name, faded across a change; a picture without a name hides the caption. */
    private void showCaption(boolean fade) {
        if (caption == null) return;
        String name = names.get(displayed);
        caption.animate().cancel();
        if (name == null) {
            caption.setAlpha(0f);
            return;
        }
        caption.setContentDescription(name);
        if (!fade || caption.getAlpha() == 0f) {
            caption.setText(name);
            caption.setAlpha(1f);
            return;
        }
        caption.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            caption.setText(name);
            caption.animate().alpha(1f).setDuration(350).start();
        }).start();
    }

    /** Names plus sizes: enough to notice a different collection without hashing every image. */
    private static String collectionKey(List<File> images) {
        StringBuilder key = new StringBuilder();
        for (File file : images) key.append(file.getName()).append(':').append(file.length()).append(';');
        return key.toString();
    }

    /** Puts a picture on screen and releases the previous one after a frame has drawn the new one. */
    private void show(Bitmap bitmap, boolean fade) {
        Bitmap old = current;
        current = bitmap;
        // The wallpaper covers the whole root; avoid drawing the fallback colour under it.
        root.setBackground(null);
        int back = front;
        int next = front == 0 ? 1 : 0;
        front = next;
        ImageView layer = layers[next], frost = frosts[next];
        layer.setImageBitmap(bitmap);
        blurOf[next] = blurs.remove(bitmap);
        frost.setImageBitmap(blurOf[next]);
        layer.bringToFront();
        frost.bringToFront();
        kenBurns(layer, frost);
        long fadeMs = fade ? FADE_MS : 0;
        layer.setAlpha(fade ? 0f : 1f);
        frost.setAlpha(fade ? 0f : 1f);
        layer.animate().alpha(1f).setDuration(fadeMs).withLayer().start();
        frost.animate().alpha(1f).setDuration(fadeMs).start();
        if (back >= 0) {
            ImageView oldLayer = layers[back], oldFrost = frosts[back];
            Bitmap oldBlur = blurOf[back];
            layer.postDelayed(() -> {
                if (front == back) return;   // swapped again meanwhile
                oldLayer.animate().cancel();
                oldFrost.animate().cancel();
                oldLayer.setAlpha(0f);
                oldFrost.setAlpha(0f);
                oldLayer.setImageDrawable(null);
                oldFrost.setImageDrawable(null);
                if (old != null && old != bitmap) old.recycle();
                if (oldBlur != null) oldBlur.recycle();
            }, fadeMs + 500);
        } else if (old != null && old != bitmap) {
            layer.postDelayed(old::recycle, 500);
        }
    }

    /** Slow zoom and drift over the rotation interval; the frost twin gets the very same motion. */
    private void kenBurns(ImageView layer, ImageView frost) {
        float room = (END_SCALE - BASE_SCALE) * width / 2f * 0.9f;
        float dx = (random.nextBoolean() ? 1 : -1) * room * (0.4f + 0.6f * random.nextFloat());
        float dy = (random.nextBoolean() ? 1 : -1) * room * 0.5f * random.nextFloat();
        long ms = Math.max(interval, 60_000L) + FADE_MS;
        for (View v : new View[]{layer, frost}) {
            v.animate().cancel();
            v.setScaleX(BASE_SCALE);
            v.setScaleY(BASE_SCALE);
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            v.animate().scaleX(END_SCALE).scaleY(END_SCALE).translationX(dx).translationY(dy)
                    .setDuration(ms).setInterpolator(new LinearInterpolator()).start();
        }
    }

    /** Parallax: the picture shifts by px (a few dp, opposite to the dock focus); the frost follows. */
    void setParallax(float px) {
        parallax = px;
        stage.animate().translationX(px).setDuration(420).start();
        frostStage.animate().translationX(px - frostRect.left).setDuration(420).start();
    }

    /** The dock band's rectangle in root coordinates: the live frost is clipped to it. */
    void setFrostRect(RectF band, float radius) {
        if (band.equals(frostRect) && radius == frostRadius) return;
        frostRect.set(band);
        frostRadius = radius;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frostClip.getLayoutParams();
        lp.width = Math.round(band.width());
        lp.height = Math.round(band.height());
        lp.leftMargin = Math.round(band.left);
        lp.topMargin = Math.round(band.top);
        frostClip.setLayoutParams(lp);
        frostClip.invalidateOutline();
        frostStage.animate().cancel();
        frostStage.setTranslationX(stage.getTranslationX() - band.left);
        frostStage.setTranslationY(-band.top);
        frostClip.setVisibility(View.VISIBLE);
    }

    private Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, this.width, this.height);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, this.width, this.height);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = bitmapConfig;
            options.inMutable = dimColor != 0;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            if (dimColor != 0) new Canvas(decoded).drawColor(dimColor);
            Bitmap small = Bitmap.createScaledBitmap(decoded, Math.max(1, decoded.getWidth() / 8),
                    Math.max(1, decoded.getHeight() / 8), true);
            Bitmap blur = Bitmap.createScaledBitmap(small, Math.max(1, small.getWidth() / 4),
                    Math.max(1, small.getHeight() / 4), true);
            if (blur != small) small.recycle();
            // One copy in GPU memory instead of a software bitmap plus its texture; the decode stays off the UI
            // thread for rotations, and Home's return no longer uploads 4-8 MB to the GPU.
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) {
                blurs.put(decoded, blur);
                return decoded;
            }
            decoded.recycle();
            blurs.put(gpu, blur);
            return gpu;
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
