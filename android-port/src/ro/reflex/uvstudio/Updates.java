package ro.reflex.uvstudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Version state and in-app update.
 *
 * A small chip next to the home title shows whether the running build is the
 * published one; when it is not, tapping it downloads the signed APK and hands
 * it to the system installer through PackageInstaller, so the update happens
 * without leaving the app.
 */
final class Updates {

    private static final String LATEST =
            "https://api.github.com/repos/SmartReFleX/UV-Studio-Mobile/releases/latest";

    private static TextView chip;
    private static String latestVersion = "";
    private static String latestApkUrl = "";
    private static String latestPageUrl = "";

    private Updates() { }

    /* ------------------------------- chip -------------------------------- */

    static TextView createChip(Activity activity) {
        TextView view = new TextView(activity);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(px(activity, 10), px(activity, 5), px(activity, 10), px(activity, 5));
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Ui.MUTED);
        view.setBackground(Ui.rounded(0x14101320, px(activity, 30), Ui.LINE, 1));
        view.setText("...");
        chip = view;
        return view;
    }

    static void refresh(final MainActivity app) {
        if (chip != null) {
            chip.setText("Checking...");
            chip.setTextColor(Ui.MUTED);
            chip.setBackground(Ui.rounded(0x14101320, px(app, 30), Ui.LINE, 1));
            chip.setOnClickListener(null);
        }
        Thread thread = new Thread(() -> {
            try {
                JSONObject json = new JSONObject(fetch(LATEST));
                String tag = json.optString("tag_name", "").trim();
                String page = json.optString("html_url", "").trim();
                String apk = "";
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.endsWith(".apk")) {
                            apk = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                if (tag.isEmpty()) return;

                final String version = tag.replaceFirst("(?i)^v", "");
                final String apkUrl = apk;
                final String pageUrl = page;
                final boolean newer = Catalog.compareVersions(version, app.versionName()) > 0;
                app.runOnUiThread(() -> apply(app, version, apkUrl, pageUrl, newer));
            } catch (Exception ignored) {
                app.runOnUiThread(() -> apply(app, "", "", "", false));
            }
        }, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    private static void apply(final MainActivity app, String version, String apkUrl,
                              String pageUrl, boolean newer) {
        latestVersion = version;
        latestApkUrl = apkUrl;
        latestPageUrl = pageUrl;
        if (chip == null) return;
        if (version.isEmpty()) {
            chip.setText("-");
            chip.setTextColor(Ui.MUTED);
            return;
        }
        if (!newer) {
            chip.setText("Up to date");
            chip.setTextColor(Ui.OK);
            chip.setBackground(Ui.rounded(0x1A34D399, px(app, 30), 0x5534D399, 1));
            chip.setOnClickListener(null);
            return;
        }
        chip.setText("Update " + version);
        chip.setTextColor(Ui.ACCENT);
        chip.setBackground(Ui.rounded(Ui.ACCENT_SOFT, px(app, 30), Ui.ACCENT_LINE, 1));
        chip.setOnClickListener(v -> ask(app));
    }

    private static void ask(final MainActivity app) {
        new AlertDialog.Builder(app)
                .setTitle("Update to " + latestVersion + "?")
                .setMessage("UV Studio Mobile " + latestVersion + " will be downloaded and "
                        + "handed to the system installer. Back up nothing: your radio data "
                        + "and settings stay untouched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Update", (dialog, which) -> {
                    if (latestApkUrl.isEmpty()) {
                        openPage(app);
                        return;
                    }
                    download(app);
                })
                .setNeutralButton("Release page", (dialog, which) -> openPage(app))
                .show();
    }

    private static void openPage(MainActivity app) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(latestPageUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } catch (Exception ignored) {
            app.toast("No browser available");
        }
    }

    /* ----------------------------- download ------------------------------ */

    private static void download(final MainActivity app) {
        app.toast("Downloading " + latestVersion + "...");
        Thread thread = new Thread(() -> {
            try {
                byte[] apk = fetchBytes(latestApkUrl);
                install(app, apk);
            } catch (Exception e) {
                app.runOnUiThread(() -> app.toast("Download failed: " + e.getMessage()));
            }
        }, "update-download");
        thread.setDaemon(true);
        thread.start();
    }

    private static void install(final MainActivity app, byte[] apk) {
        try {
            /* Write the APK where the provider can serve it, then let the system
             * installer do the work. PackageInstaller sessions were unreliable
             * here: the status callback never reached the app. */
            final String name = "update-" + latestVersion + ".apk";
            File file = new File(app.getCacheDir(), name);
            try (OutputStream out = new java.io.FileOutputStream(file)) {
                out.write(apk);
            }

            /* Check what was actually downloaded before handing it over: a stale
             * or truncated file would otherwise look like a silent failure. */
            android.content.pm.PackageInfo info = app.getPackageManager()
                    .getPackageArchiveInfo(file.getAbsolutePath(), 0);
            if (info == null) {
                app.runOnUiThread(() -> app.toast("Downloaded file is not a valid APK"));
                return;
            }
            String found = info.versionName + " (" + info.versionCode + "), "
                    + Math.round(apk.length / 1024f) + " KB";
            app.runOnUiThread(() -> app.toast("Downloaded " + found));

            Uri uri = ApkProvider.uriFor(name);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(intent);
        } catch (Exception e) {
            app.runOnUiThread(() -> app.toast("Install failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage()
                    + "\nAllow \"Install unknown apps\" for UV Studio Mobile and try again."));
        }
    }

    /* ------------------------------ helpers ------------------------------ */

    private static int px(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static String fetch(String url) throws Exception {
        return new String(fetchBytes(url), "UTF-8");
    }

    private static byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "UV-Studio-Mobile");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }
}
