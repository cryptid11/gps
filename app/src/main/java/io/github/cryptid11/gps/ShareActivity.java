package io.github.cryptid11.gps;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Builds a shareable image for a stretch of the track: pick the time range, add photos, save or send. */
public final class ShareActivity extends Activity {
    static final String EXTRA_FROM = "from";
    static final String EXTRA_TO = "to";

    private static final int REQUEST_PHOTOS = 1;
    private static final int REQUEST_SAVE = 2;
    private static final int MAX_PHOTOS = 6;
    private static final int PHOTO_MAX_SIDE = 1080;
    private static final long RENDER_DELAY_MS = 300;
    private static final String SHARED_NAME = "gps-log.jpg";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable render = this::render;
    private final List<Bitmap> photos = new ArrayList<>();

    private SeekBar from;
    private SeekBar to;
    private TextView fromLabel;
    private TextView toLabel;
    private TextView legend;
    private ModeStrip fromStrip;
    private ModeStrip toStrip;
    private ImageView preview;
    private View busy;
    private Button clearPhotos;
    private Button addPhotos;
    private Button save;
    private Button send;

    private long[] t = {};
    private int[] tz = {};
    private double[] lat = {};
    private double[] lon = {};
    private double[] alt = {};
    private double[] spd = {};
    /** Guessed or hand-set mode of each stretch, as on the map. */
    private int[] modes = {};
    /** Bumped on every change, so a render that finishes after a newer change is discarded. */
    private int version;
    private Bitmap image;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.share);
        from = findViewById(R.id.from);
        to = findViewById(R.id.to);
        fromLabel = findViewById(R.id.from_label);
        toLabel = findViewById(R.id.to_label);
        legend = findViewById(R.id.legend);
        preview = findViewById(R.id.preview);
        busy = findViewById(R.id.busy);
        addPhotos = findViewById(R.id.add_photos);
        clearPhotos = findViewById(R.id.clear_photos);
        save = findViewById(R.id.save);
        send = findViewById(R.id.send);

        load(getIntent().getLongExtra(EXTRA_FROM, 0), getIntent().getLongExtra(EXTRA_TO, Long.MAX_VALUE));
        if (t.length < 2) {
            Toast.makeText(this, R.string.share_too_few, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        fromStrip = new ModeStrip(this, modes);
        toStrip = new ModeStrip(this, modes);
        from.setProgressDrawable(fromStrip);
        to.setProgressDrawable(toStrip);
        legend.setText(legend());
        from.setMax(t.length - 1);
        to.setMax(t.length - 1);
        from.setProgress(0);
        to.setProgress(t.length - 1);
        SeekBar.OnSeekBarChangeListener changed = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean byUser) {
                // Keep at least two fixes in the range.
                if (bar == from && value >= to.getProgress()) from.setProgress(to.getProgress() - 1);
                if (bar == to && value <= from.getProgress()) to.setProgress(from.getProgress() + 1);
                changed();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        };
        from.setOnSeekBarChangeListener(changed);
        to.setOnSeekBarChangeListener(changed);

        addPhotos.setOnClickListener(v -> pickPhotos());
        clearPhotos.setOnClickListener(v -> {
            photos.clear();
            changed();
        });
        save.setOnClickListener(v -> pickSaveFile());
        send.setOnClickListener(v -> send());
        changed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(render);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void load(long start, long end) {
        List<double[]> rows = new ArrayList<>();
        List<long[]> stamps = new ArrayList<>();
        try {
            Db.get(this).range(start, end, (time, la, lo, acc, al, sp, src, zone) -> {
                stamps.add(new long[] {time, zone});
                rows.add(new double[] {la, lo, al, sp, acc});
            });
        } catch (IOException ignored) {
        }
        int n = rows.size();
        t = new long[n];
        tz = new int[n];
        lat = new double[n];
        lon = new double[n];
        alt = new double[n];
        spd = new double[n];
        double[] acc = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = stamps.get(i)[0];
            tz[i] = (int) stamps.get(i)[1];
            lat[i] = rows.get(i)[0];
            lon[i] = rows.get(i)[1];
            alt[i] = rows.get(i)[2];
            spd[i] = rows.get(i)[3];
            acc[i] = rows.get(i)[4];
        }
        modes = Modes.classify(t, Modes.speeds(t, lat, lon, spd, acc));
        Modes.apply(modes, t, Db.get(this).chosenModes(start, end));
    }

    /** A coloured dot and the name of each mode on the bars. */
    private CharSequence legend() {
        boolean[] present = new boolean[Modes.COUNT];
        for (int mode : modes) present[mode] = true;
        String[] names = getResources().getStringArray(R.array.mode_names);
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int mode : Modes.ORDER) {
            if (!present[mode]) continue;
            if (text.length() > 0) text.append("   ");
            int dot = text.length();
            text.append("●");
            text.setSpan(new ForegroundColorSpan(Modes.color(mode)), dot, dot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(' ').append(names[mode]);
        }
        return text;
    }

    private void changed() {
        int a = from.getProgress(), b = to.getProgress();
        fromLabel.setText(getString(R.string.share_from, when(a)));
        toLabel.setText(getString(R.string.share_to, when(b)));
        fromStrip.select(a, b);
        toStrip.select(a, b);
        addPhotos.setText(photos.isEmpty() ? getString(R.string.share_add_photos)
                : getString(R.string.share_add_more_photos, photos.size(), MAX_PHOTOS));
        addPhotos.setEnabled(photos.size() < MAX_PHOTOS);
        clearPhotos.setVisibility(photos.isEmpty() ? View.GONE : View.VISIBLE);
        version++;
        save.setEnabled(false);
        send.setEnabled(false);
        busy.setVisibility(View.VISIBLE);
        handler.removeCallbacks(render);
        handler.postDelayed(render, RENDER_DELAY_MS);
    }

    private String when(int i) {
        return Instant.ofEpochMilli(t[i]).atOffset(ZoneOffset.ofTotalSeconds(tz[i] * 60))
                .format(DateTimeFormatter.ofPattern("d MMM HH:mm"));
    }

    private void render() {
        int a = from.getProgress(), b = to.getProgress(), mine = version;
        Card.Route route = new Card.Route();
        route.t = Arrays.copyOfRange(t, a, b + 1);
        route.tz = Arrays.copyOfRange(tz, a, b + 1);
        route.lat = Arrays.copyOfRange(lat, a, b + 1);
        route.lon = Arrays.copyOfRange(lon, a, b + 1);
        route.alt = Arrays.copyOfRange(alt, a, b + 1);
        route.spd = Arrays.copyOfRange(spd, a, b + 1);
        List<Bitmap> chosen = new ArrayList<>(photos);
        worker.execute(() -> {
            Bitmap result = Card.render(this, route, chosen);
            runOnUiThread(() -> {
                if (mine != version || isDestroyed()) return;
                image = result;
                preview.setImageBitmap(result);
                busy.setVisibility(View.GONE);
                save.setEnabled(true);
                send.setEnabled(true);
            });
        });
    }

    private void pickPhotos() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, REQUEST_PHOTOS);
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pickSaveFile() {
        Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_TITLE, "gps-log-" + Instant.ofEpochMilli(t[from.getProgress()])
                        .atOffset(ZoneOffset.ofTotalSeconds(tz[from.getProgress()] * 60))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")) + ".jpg");
        try {
            startActivityForResult(pick, REQUEST_SAVE);
        } catch (RuntimeException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (result != RESULT_OK || data == null) return;
        if (request == REQUEST_PHOTOS) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            ContentResolver resolver = getContentResolver();
            worker.execute(() -> {
                List<Bitmap> decoded = new ArrayList<>();
                for (Uri uri : uris) {
                    Bitmap photo = decodePhoto(resolver, uri);
                    if (photo != null) decoded.add(photo);
                }
                runOnUiThread(() -> {
                    for (Bitmap photo : decoded) {
                        if (photos.size() < MAX_PHOTOS) photos.add(photo);
                    }
                    changed();
                });
            });
        } else if (request == REQUEST_SAVE && data.getData() != null && image != null) {
            Bitmap toSave = image;
            Uri target = data.getData();
            worker.execute(() -> {
                boolean ok;
                try (OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                    ok = out != null && toSave.compress(Bitmap.CompressFormat.JPEG, 92, out);
                } catch (IOException | RuntimeException e) {
                    ok = false;
                }
                boolean saved = ok;
                runOnUiThread(() -> Toast.makeText(this,
                        saved ? R.string.share_saved : R.string.share_failed, Toast.LENGTH_SHORT).show());
            });
        }
    }

    private void send() {
        if (image == null) return;
        Bitmap toSend = image;
        worker.execute(() -> {
            File f = ShareProvider.file(this, SHARED_NAME);
            boolean ok;
            f.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(f)) {
                ok = toSend.compress(Bitmap.CompressFormat.JPEG, 92, out);
            } catch (IOException e) {
                ok = false;
            }
            boolean written = ok;
            runOnUiThread(() -> {
                if (!written) {
                    Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                Uri uri = ShareProvider.uri(SHARED_NAME);
                Intent intent = new Intent(Intent.ACTION_SEND)
                        .setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri(null, uri));
                startActivity(Intent.createChooser(intent, getString(R.string.share_send)));
            });
        });
    }

    /** Decodes a photo at most {@link #PHOTO_MAX_SIDE} on its long side, upright. */
    private static Bitmap decodePhoto(ContentResolver resolver, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int side = Math.max(bounds.outWidth, bounds.outHeight);
            if (side <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (side / (options.inSampleSize * 2) >= PHOTO_MAX_SIDE) {
                options.inSampleSize *= 2;
            }
            Bitmap bitmap;
            try (InputStream in = resolver.openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) return null;
            int orientation;
            try (InputStream in = resolver.openInputStream(uri)) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } catch (IOException | RuntimeException e) {
                orientation = ExifInterface.ORIENTATION_NORMAL;
            }
            float shrink = Math.min(1f, (float) PHOTO_MAX_SIDE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
            Matrix m = new Matrix();
            m.postScale(shrink, shrink);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
            else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
            if (m.isIdentity()) return bitmap;
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }
}
