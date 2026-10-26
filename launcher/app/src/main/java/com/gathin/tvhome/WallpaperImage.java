package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Turns an uploaded photo into a 1920x1080 wallpaper and makes it Home's wallpaper choice. A landscape photo
 * fills the screen; a portrait one is centred over a soft, darkened copy of itself. The top and the left are
 * darkened a little so the clock, the date and the dock title stay readable.
 */
final class WallpaperImage {
    static final int WIDTH = 1920;
    static final int HEIGHT = 1080;
    /** Uploaded pictures in the wallpapers/ collection start with this; only the latest is kept. */
    private static final String PREFIX = "upload-";

    private WallpaperImage() {}

    /** Decode, compose, save and select. Returns the saved file; throws when the bytes are not an image. */
    static File install(Context context, byte[] data) throws IOException {
        Bitmap photo = decode(data);
        if (photo == null) throw new IOException("not an image");
        Bitmap out = compose(photo);
        photo.recycle();
        File dir = Config.dir(context);
        if (dir == null) throw new IOException("no storage");
        File wallpapers = new File(dir, ThemeFiles.WALLPAPERS);
        if (!wallpapers.isDirectory() && !wallpapers.mkdirs()) throw new IOException("cannot create " + wallpapers);
        String name = PREFIX + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".jpg";
        File file = new File(wallpapers, name);
        try (OutputStream os = new FileOutputStream(file)) {
            out.compress(Bitmap.CompressFormat.JPEG, 90, os);
        } finally {
            out.recycle();
        }
        File[] old = wallpapers.listFiles((d, n) -> n.startsWith(PREFIX) && !n.equals(name));
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        SharedPreferences choices = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name)
                .putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .commit();
        return file;
    }

    /** Decoded at the smallest power-of-two sample that still covers the screen, turned upright. */
    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        // A portrait photo is drawn only 1080 high, so it needs the height alone. EXIF rotation can swap the two,
        // so judge by the shorter and longer sides: never sample below 1080 on the short side.
        int shortSide = Math.min(bounds.outWidth, bounds.outHeight);
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (shortSide / (sample * 2) >= HEIGHT && longSide / (sample * 2) >= HEIGHT) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bitmap == null) return null;
        int degrees = 0;
        try {
            int orientation = new ExifInterface(new ByteArrayInputStream(data))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
        } catch (IOException | RuntimeException ignored) {
            // No EXIF (a PNG, or a picture the phone's browser already turned upright).
        }
        if (degrees == 0) return bitmap;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        if (upright != bitmap) bitmap.recycle();
        return upright;
    }

    private static Bitmap compose(Bitmap photo) {
        Bitmap out = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        float aspect = photo.getWidth() / (float) photo.getHeight();
        if (aspect >= 1.3f) {
            canvas.drawBitmap(photo, cover(photo.getWidth(), photo.getHeight()), new Rect(0, 0, WIDTH, HEIGHT), paint);
        } else {
            // A tiny copy stretched over the screen reads as a blur, at no real cost.
            Bitmap tiny = Bitmap.createScaledBitmap(photo, 32, Math.max(1, Math.round(32 / aspect)), true);
            canvas.drawBitmap(tiny, cover(tiny.getWidth(), tiny.getHeight()), new Rect(0, 0, WIDTH, HEIGHT), paint);
            tiny.recycle();
            canvas.drawColor(0x73000000);
            float w = HEIGHT * aspect;
            float left = (WIDTH - w) / 2f;
            canvas.drawBitmap(photo, null, new RectF(left, 0, left + w, HEIGHT), paint);
        }
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, 0, 0, 380, 0xBF000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, 380, shade);
        shade.setShader(new LinearGradient(0, 0, 760, 0, 0x59000000, 0x00000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 760, HEIGHT, shade);
        return out;
    }

    /** The centred part of a w x h picture with the screen's aspect ratio. */
    private static Rect cover(int w, int h) {
        float screen = WIDTH / (float) HEIGHT;
        if (w / (float) h > screen) {
            int cw = Math.round(h * screen);
            int x = (w - cw) / 2;
            return new Rect(x, 0, x + cw, h);
        }
        int ch = Math.round(w / screen);
        int y = (h - ch) / 2;
        return new Rect(0, y, w, y + ch);
    }
}
