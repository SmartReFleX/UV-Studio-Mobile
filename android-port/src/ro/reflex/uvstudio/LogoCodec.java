package ro.reflex.uvstudio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

/** Boot logo: 8-byte magic + 1024 bytes ST7565 column-major bitmap, padded to 1040. */
final class LogoCodec {

    static final int WIDTH = 128;
    static final int HEIGHT = 64;
    private static final byte[] MAGIC = {0x46, 0x34, 0x48, 0x57, 0x4E, 0x4C, 0x47, 0x4F};
    private static final int PADDED = 1040;

    private LogoCodec() { }

    /** Decodes any image, fits it on a white 128x64 canvas and thresholds it. */
    static byte[] encode(byte[] imageBytes, int threshold, boolean invert) throws Exception {
        Bitmap source = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
        if (source == null) throw new Exception("Unsupported image format");

        Bitmap canvasBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(canvasBitmap);
        canvas.drawColor(Color.WHITE);

        float srcRatio = source.getWidth() / (float) source.getHeight();
        float dstRatio = WIDTH / (float) HEIGHT;
        int drawW, drawH, drawX, drawY;
        if (srcRatio > dstRatio) {
            drawW = WIDTH;
            drawH = Math.round(WIDTH / srcRatio);
            drawX = 0;
            drawY = Math.round((HEIGHT - drawH) / 2f);
        } else {
            drawH = HEIGHT;
            drawW = Math.round(HEIGHT * srcRatio);
            drawX = Math.round((WIDTH - drawW) / 2f);
            drawY = 0;
        }
        Paint paint = new Paint();
        paint.setFilterBitmap(true);
        paint.setAntiAlias(true);
        canvas.drawBitmap(source, new Rect(0, 0, source.getWidth(), source.getHeight()),
                new Rect(drawX, drawY, drawX + drawW, drawY + drawH), paint);

        int[] pixels = new int[WIDTH * HEIGHT];
        canvasBitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);

        byte[] bitmap = new byte[1024];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int color = pixels[y * WIDTH + x];
                float lum = 0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color);
                boolean on = lum < threshold;
                if (invert) on = !on;
                if (on) {
                    int index = (y / 8) * WIDTH + x;
                    bitmap[index] |= (byte) (1 << (y & 7));
                }
            }
        }

        byte[] out = new byte[PADDED];
        java.util.Arrays.fill(out, (byte) 0xFF);
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(bitmap, 0, out, 8, bitmap.length);
        source.recycle();
        canvasBitmap.recycle();
        return out;
    }

    /** Renders the 1040-byte logo payload as a black/white bitmap. */
    static Bitmap decode(byte[] padded) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int index = 8 + (y / 8) * WIDTH + x;
                boolean on = index < padded.length && ((padded[index] >> (y & 7)) & 1) != 0;
                pixels[y * WIDTH + x] = on ? Color.BLACK : Color.WHITE;
            }
        }
        bitmap.setPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        return bitmap;
    }

    static boolean hasMagic(byte[] padded) {
        if (padded == null || padded.length < 8) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (padded[i] != MAGIC[i]) return false;
        }
        return true;
    }
}
