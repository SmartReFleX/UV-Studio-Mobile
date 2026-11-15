package ro.reflex.uvstudio;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;

/**
 * Boot logo: 8-byte magic + 1024-byte 128x64 bitmap at 0xC000.
 * Download renders it to PNG; upload thresholds any image and pads to 1040 bytes.
 */
final class LogoScreen extends ToolScreen {

    private ImageView preview;
    private TextView statusLine;
    private SeekBar threshold;
    private TextView thresholdLabel;
    private CheckBox invert;
    private byte[] sourceImage;
    private byte[] encoded;
    private byte[] dumped;
    private Bitmap dumpedBitmap;

    LogoScreen(MainActivity app, RadioLink link) {
        super(app, link, "Boot logo",
                "Read the current boot image, or upload any picture: it is fitted to "
                + "128×64, thresholded and written to the radio's logo sector.");
    }

    @Override
    protected void build() {
        LinearLayout card = plain();
        card.addView(Ui.label(app, "PREVIEW"));

        preview = new ImageView(app);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(Ui.rounded(0xFF060B12, dp(8), Ui.LINE, 1));
        preview.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
        card.addView(preview, previewParams);

        statusLine = Ui.body(app, "No logo loaded.");
        statusLine.setPadding(0, dp(8), 0, 0);
        card.addView(statusLine);

        LinearLayout row = Ui.row(app);
        row.setPadding(0, dp(10), 0, 0);
        card.addView(row);
        rowButton(row, "Read from radio", 1, v -> readLogo());
        rowButton(row, "Save PNG", 0, v -> savePng());

        LinearLayout upload = plain();
        upload.addView(Ui.label(app, "UPLOAD IMAGE"));

        row = Ui.row(app);
        upload.addView(row);
        rowButton(row, "Choose image…", 0, v -> chooseImage());
        rowButton(row, "Write to radio", 1, v -> writeLogo());

        thresholdLabel = Ui.body(app, "Threshold: 128");
        thresholdLabel.setPadding(0, dp(12), 0, 0);
        upload.addView(thresholdLabel);

        threshold = new SeekBar(app);
        threshold.setMax(255);
        threshold.setProgress(128);
        threshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                thresholdLabel.setText("Threshold: " + value);
                if (fromUser) encodePreview();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        upload.addView(threshold);

        invert = new CheckBox(app);
        invert.setText("Invert colours");
        invert.setTextColor(Ui.TEXT);
        invert.setOnCheckedChangeListener((buttonView, checked) -> encodePreview());
        upload.addView(invert);
    }

    private void readLogo() {
        withRadio("logo-dump", progress -> {
            progress.publish(2, "Opening session…");
            Radio.sleep(500);
            app.radio.deviceInfo(1500);
            return app.radio.readLogo(progress);
        }, (value, error) -> {
            if (error != null) return;
            dumped = value;
            dumpedBitmap = LogoCodec.decode(value);
            preview.setImageBitmap(dumpedBitmap);
            statusLine.setTextColor(Ui.MUTED);
            statusLine.setText(LogoCodec.hasMagic(value)
                    ? "Logo read from the radio."
                    : "Read 1040 bytes, but the logo magic is missing (blank or custom logo).");
            finishProgress("Logo read", Ui.OK);
        });
    }

    private void savePng() {
        if (dumpedBitmap == null) {
            app.toast("Read the logo first.");
            return;
        }
        Bitmap bitmap = dumpedBitmap;
        runLocal("logo-png", progress -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        }, (value, error) -> {
            if (error == null) app.saveFile("logo.png", value);
        });
    }

    private void chooseImage() {
        app.pickFile("image/*", null, (name, bytes) -> {
            if (bytes == null) return;
            sourceImage = bytes;
            encodePreview();
        });
    }

    private void encodePreview() {
        if (sourceImage == null) {
            app.toast("Choose an image first.");
            return;
        }
        final int level = threshold.getProgress();
        final boolean flip = invert.isChecked();
        runLocal("logo-encode", progress -> {
            encoded = LogoCodec.encode(sourceImage, level, flip);
            return LogoCodec.decode(encoded);
        }, (value, error) -> {
            if (error != null) {
                statusLine.setTextColor(Ui.DANGER);
                statusLine.setText("Could not read that image: " + error.getMessage());
                return;
            }
            preview.setImageBitmap(value);
            statusLine.setTextColor(Ui.MUTED);
            statusLine.setText("Preview of the 128×64 result. Write it when it looks right.");
        });
    }

    private void writeLogo() {
        if (encoded == null) {
            app.toast("Choose an image first.");
            return;
        }
        withRadio("logo-write", progress -> {
            progress.publish(2, "Opening session…");
            Radio.sleep(500);
            app.radio.deviceInfo(1500);
            app.radio.writeLogo(encoded, progress);
            return true;
        }, (value, error) -> {
            if (error != null) return;
            statusLine.setText("Logo written. The radio restarted.");
            finishProgress("Logo uploaded", Ui.OK);
        });
    }
}
