package ro.reflex.uvstudio;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Overlay apps: each slot is an independent 8 KiB image in the external flash.
 * Slots are probed one by one (there is no bulk list command), apps are
 * installed from a local .app file (FAP1 header + code + assets).
 */
final class AppsScreen extends ToolScreen {

    private LinearLayout listBox;
    private TextView summary;
    private List<Radio.AppInfo> apps = new ArrayList<>();
    private final java.util.Map<String, String> knownScreens = new java.util.HashMap<>();
    private String firmware = "";
    private int slotCount = 16;

    AppsScreen(MainActivity app, RadioLink link) {
        super(app, link, "Install applications",
                "Install only the tools you need.");
    }

    @Override
    protected boolean progressAtTop() {
        return false;
    }

    @Override
    protected void build() {
        LinearLayout card = plain();
        summary = Ui.body(app, "Waiting for the radio…");
        card.addView(summary);

        LinearLayout row = Ui.row(app);
        row.setPadding(0, dp(10), 0, 0);
        card.addView(row);
        rowButton(row, "Read apps", 1, v -> refresh());
        TextView installButton = rowButton(row, "Install app from local", 0, v -> install());
        installButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);

        LinearLayout actions = Ui.row(app);
        actions.setPadding(dp(0), dp(8), dp(0), 0);
        card.addView(actions);
        rowButton(actions, "Renumber", 3, v -> confirmRenumber());
        rowButton(actions, "Delete all", 2, v -> confirmDeleteAll());

        LinearLayout catalogRow = Ui.row(app);
        catalogRow.setPadding(0, dp(8), 0, 0);
        card.addView(catalogRow);
        rowButton(catalogRow, "Install from catalog…", 3, v -> openCatalog());

        /* Progress bar sits between the catalog button and the app list. */
        addProgressBlock();

        LinearLayout listCard = plain();
        listBox = new LinearLayout(app);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(listBox);
        listBox.addView(Ui.body(app, "No app table yet."));
    }

    /* ------------------------------ scan -------------------------------- */

    private void refresh() {
        withRadio("apps-refresh", progress -> {
            Radio.sleep(300);
            String name = app.radio.deviceInfo(1500);
            int slots = Radio.appSlotCount(name);
            progress.publish(5, "Probing overlay app support…");
            Radio.AppInfo first;
            try {
                first = app.radio.appInfo(0, 1200);
            } catch (Exception e) {
                throw new Exception("This firmware does not answer the app commands "
                        + "(overlay apps need a Fusion build).");
            }

            List<Radio.AppInfo> found = new ArrayList<>();
            found.add(checkCompat(first));
            for (int slot = 1; slot < slots; slot++) {
                progress.publish(5 + slot * 90 / slots, "Slot " + (slot + 1) + "/" + slots);
                try {
                    found.add(checkCompat(app.radio.appInfo(slot, 1200)));
                } catch (Exception e) {
                    Radio.AppInfo bad = new Radio.AppInfo();
                    bad.slot = slot;
                    bad.status = 6;
                    found.add(bad);
                }
            }
            firmware = name;
            slotCount = slots;
            return found;
        }, (value, error) -> {
            if (error != null) return;
            apps = value;
            render();
            finishProgress("App list read", Ui.OK);
        });
    }

    private Radio.AppInfo checkCompat(Radio.AppInfo info) {
        if (info.status != 0) return info;
        try {
            int code = app.radio.appValidate(info.slot);
            if (code == 3 || code == 9) info.status = 9;
        } catch (Exception ignored) { }
        return info;
    }

    private void render() {
        int installed = 0;
        for (Radio.AppInfo info : apps) if (info.installed()) installed++;
        summary.setText(firmware + " · " + installed + " app" + (installed == 1 ? "" : "s")
                + " installed · " + apps.size() + " slots scanned");

        listBox.removeAllViews();
        boolean any = false;
        for (Radio.AppInfo info : apps) {
            if (info.status == 2 || info.status == 6 || info.status == 7) continue;
            any = true;

            LinearLayout row = Ui.row(app);
            row.setPadding(0, dp(6), 0, dp(6));

            String screen = knownScreens.get(AppScreens.idFor(info.name()));
            if (screen == null) screen = AppScreens.get(app, AppScreens.idFor(info.name()));
            Bitmap art = screenBitmap(screen);
            if (art != null) {
                ImageView thumb = new ImageView(app);
                thumb.setImageBitmap(art);
                thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                thumb.setBackground(Ui.rounded(0xFFFFFFFF, dp(6), Ui.LINE, 1));
                thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
                LinearLayout.LayoutParams thumbParams =
                        new LinearLayout.LayoutParams(dp(76), dp(40));
                thumbParams.rightMargin = dp(10);
                row.addView(thumb, thumbParams);
            }

            LinearLayout labels = new LinearLayout(app);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(Ui.text(app, "#" + (info.slot + 1) + "  " + info.name(), 14, Ui.TEXT, true));
            String state = info.installed() ? "installed"
                    : info.incompatible() ? "incompatible with this firmware"
                    : "status " + info.status;
            labels.addView(Ui.text(app, info.version() + " · " + state, 11, Ui.MUTED, false));
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView erase = Ui.text(app, "Delete", 11, Ui.DANGER, true);
            erase.setPadding(dp(8), dp(6), dp(8), dp(6));
            erase.setOnClickListener(v -> confirmDelete(info.slot));
            row.addView(erase);

            listBox.addView(row);
            listBox.addView(Ui.divider(app));
        }
        if (!any) {
            listBox.addView(Ui.body(app, "No apps installed. Use “Install app from local” to add one."));
        }
    }

    /* ---------------------------- operations ----------------------------- */

    private int firstEmptySlot() {
        for (Radio.AppInfo info : apps) {
            if (info.status == 2) return info.slot;
        }
        return -1;
    }

    /* ------------------------------ catalog ------------------------------- */

    private void openCatalog() {
        if (apps.isEmpty()) {
            app.toast("Read the app list first.");
            return;
        }
        withRadio("app-catalog", progress -> {
            Radio.sleep(300);
            String firmware = app.radio.deviceInfo(1500);

            List<Catalog.Entry> all = new ArrayList<>();
            progress.publish(25, "Reading the official catalog…");
            try {
                all.addAll(Catalog.apps(firmware));
            } catch (Exception ignored) {
                /* no published folder for this firmware */
            }
            progress.publish(55, "Reading the community catalog…");
            try {
                all.addAll(Catalog.community());
            } catch (Exception ignored) {
                /* offline or catalog moved */
            }
            if (all.isEmpty()) throw new Exception("No apps found in the catalogs");
            return all;
        }, (value, error) -> {
            if (error != null) {
                app.toast("Catalog: " + error.getMessage());
                return;
            }
            showCatalog(value);
            finishProgress("", Ui.MUTED);
        });
    }

    /** Catalog picker with the 128x64 screen preview published by the community. */
    private void showCatalog(final List<Catalog.Entry> list) {
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(6), dp(4), dp(6));

        ScrollView scroll = new ScrollView(app);
        scroll.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(app)
                .setTitle("Apps catalog")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();

        for (Catalog.Entry entry : list) {
            if (entry.screen != null && !entry.screen.isEmpty()) {
                knownScreens.put(AppScreens.idFor(entry.name), entry.screen);
            }
            LinearLayout row = Ui.row(app);
            row.setPadding(dp(6), dp(8), dp(6), dp(8));

            Bitmap art = screenBitmap(entry.screen);
            if (art == null && !entry.name.isEmpty()) {
                art = screenBitmap(AppScreens.get(app, AppScreens.idFor(entry.name)));
            }
            if (art != null) {
                ImageView thumb = new ImageView(app);
                thumb.setImageBitmap(art);
                thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                thumb.setBackground(Ui.rounded(0xFFFFFFFF, dp(6), Ui.LINE, 1));
                thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
                LinearLayout.LayoutParams thumbParams =
                        new LinearLayout.LayoutParams(dp(88), dp(46));
                thumbParams.rightMargin = dp(10);
                row.addView(thumb, thumbParams);
            }

            LinearLayout labels = new LinearLayout(app);
            labels.setOrientation(LinearLayout.VERTICAL);

            String title = entry.edition
                    + (entry.version.isEmpty() ? "" : " v" + entry.version);
            labels.addView(Ui.text(app, title, 14, Ui.TEXT, true));

            StringBuilder sub = new StringBuilder();
            if (!entry.author.isEmpty()) sub.append(entry.author);
            if (entry.community) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append("community");
            }
            if (entry.size > 0) {
                if (sub.length() > 0) sub.append(" · ");
                sub.append(Math.round(entry.size / 1024f)).append(" KB");
            }
            if (sub.length() > 0) {
                labels.addView(Ui.text(app, sub.toString(), 10, Ui.MUTED, false));
            }
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.setOnClickListener(v -> {
                dialog.dismiss();
                downloadApp(entry);
            });
            box.addView(row);
            box.addView(Ui.divider(app));
        }

        dialog.show();
    }

    /** Community screen preview: 1024 bytes, 128x64, page format, 1 = black. */
    private static Bitmap screenBitmap(String base64) {
        if (base64 == null || base64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            if (bytes.length < 1024) return null;
            int[] pixels = new int[128 * 64];
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 128; x++) {
                    int index = (y / 8) * 128 + x;
                    int bit = (bytes[index] >> (y & 7)) & 1;
                    pixels[y * 128 + x] = bit != 0 ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, 128, 0, 0, 128, 64);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void downloadApp(Catalog.Entry entry) {
        runLocal("app-download",
                progress -> Catalog.download(entry.url, progress, 0, 100, entry.sha256),
                (bytes, error) -> {
                    if (error != null) {
                        app.toast("Download failed: " + error.getMessage());
                        return;
                    }
                    int slot = firstEmptySlot();
                    if (slot < 0) {
                        app.toast("No free app slot.");
                        return;
                    }
                    confirmInstall(slot, entry.name, bytes);
                });
    }

    private void install() {
        if (apps.isEmpty()) {
            app.toast("Read the app list first.");
            return;
        }
        int target = firstEmptySlot();
        if (target < 0) {
            app.toast("No free app slot.");
            return;
        }
        app.pickFile("application/octet-stream", new String[] {"application/octet-stream", "*/*"},
                (name, bytes) -> {
                    if (bytes == null) return;
                    confirmInstall(target, name, bytes);
                });
    }

    private void confirmInstall(int slot, String name, byte[] bytes) {
        new AlertDialog.Builder(app)
                .setTitle("Install into slot " + (slot + 1) + "?")
                .setMessage(name + ", " + bytes.length + " bytes.\n\n"
                        + "The slot is erased, the code and assets are written, then validated.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Install", (dialog, which) -> doInstall(slot, bytes))
                .show();
    }

    private void doInstall(int slot, byte[] bytes) {
        withRadio("app-install", progress -> {
            Radio.sleep(500);
            app.radio.deviceInfo(1500);
            app.radio.installApp(slot, bytes, progress);
            return true;
        }, (value, error) -> {
            if (error != null) return;
            finishProgress("App installed in slot " + (slot + 1), Ui.OK);
            refresh();
        });
    }

    private void confirmDelete(int slot) {
        new AlertDialog.Builder(app)
                .setTitle("Delete app in slot " + (slot + 1) + "?")
                .setMessage("The app is erased from the radio.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> doDelete(slot))
                .show();
    }

    private void doDelete(int slot) {
        withRadio("app-delete", progress -> {
            Radio.sleep(400);
            app.radio.deviceInfo(1500);
            app.radio.appErase(slot);
            return true;
        }, (value, error) -> {
            if (error != null) return;
            finishProgress("App deleted", Ui.OK);
            refresh();
        });
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(app)
                .setTitle("Delete every app?")
                .setMessage("All installed apps are erased. The firmware itself is not touched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete all", (dialog, which) -> doDeleteAll())
                .show();
    }

    private void doDeleteAll() {
        withRadio("app-delete-all", progress -> {
            Radio.sleep(400);
            app.radio.deviceInfo(1500);
            List<Integer> slots = new ArrayList<>();
            for (Radio.AppInfo info : apps) if (info.installed()) slots.add(info.slot);
            for (int i = 0; i < slots.size(); i++) {
                progress.publish((i + 1) * 100 / slots.size(), "Erasing " + (i + 1) + "/" + slots.size());
                app.radio.appErase(slots.get(i));
            }
            return slots.size();
        }, (value, error) -> {
            if (error != null) return;
            finishProgress(value + " apps deleted", Ui.OK);
            refresh();
        });
    }

    private void confirmRenumber() {
        if (apps.size() < 2) {
            app.toast("Nothing to renumber.");
            return;
        }
        new AlertDialog.Builder(app)
                .setTitle("Renumber apps?")
                .setMessage("Installed apps are moved down to slots 1, 2, 3… keeping their "
                        + "current order. Images are copied through the external flash; "
                        + "do not unplug the cable.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Renumber", (dialog, which) -> doRenumber())
                .show();
    }

    private void doRenumber() {
        final List<Radio.AppInfo> snapshot = apps;
        withRadio("app-renumber", progress -> {
            Radio.sleep(400);
            app.radio.deviceInfo(1500);

            List<Integer> installed = new ArrayList<>();
            for (Radio.AppInfo info : snapshot) if (info.installed()) installed.add(info.slot);
            if (installed.isEmpty()) return 0;

            int last = installed.get(installed.size() - 1);
            List<Integer> sources = new ArrayList<>(installed);
            for (int slot = 0; slot <= last; slot++) {
                if (!installed.contains(slot)) sources.add(slot);
            }

            int moved = 0;
            for (int target = 0; target <= last; target++) {
                int source = sources.get(target);
                if (source == target) continue;
                progress.publish(35 * target / Math.max(1, last + 1),
                        "Reading source slot " + (source + 1));
                byte[] image = app.radio.readAppSlotImage(source);
                app.radio.writeAppSlotImage(target, image, progress);
                moved++;
            }

            progress.publish(96, "Validating…");
            for (int target = 0; target <= last; target++) {
                try {
                    app.radio.appValidate(target);
                } catch (Exception ignored) { }
            }
            return moved;
        }, (value, error) -> {
            if (error != null) return;
            finishProgress("Renumbered " + value + " slots", Ui.OK);
            refresh();
        });
    }
}
