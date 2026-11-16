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
    private final java.util.Map<String, Catalog.Entry> remote = new java.util.HashMap<>();
    private String firmware = "";
    private int slotCount = 16;
    private int updateCount = 0;
    private boolean catalogChecked = false;
    private boolean catalogDone = false;
    private String officialError = null;
    private String communityError = null;

    /** Result of one catalog pass, kept whole so the UI can explain what happened. */
    private static final class CatalogData {
        final List<Catalog.Entry> entries = new ArrayList<>();
        int official;
        int community;
        String officialError;
        String communityError;
    }

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
            checkUpdates();
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

        updateCount = 0;
        for (Radio.AppInfo info : apps) {
            if (!info.installed()) continue;
            Catalog.Entry candidate = remote.get(key(info.name()));
            if (candidate != null
                    && Catalog.compareVersions(candidate.version, info.version()) > 0) {
                updateCount++;
            }
        }

        summary.setText(firmware + " · " + installed + " app" + (installed == 1 ? "" : "s")
                + " installed · " + apps.size() + " slots scanned"
                + (updateCount > 0 ? " · " + updateCount + " update"
                        + (updateCount == 1 ? "" : "s") + " available" : "")
                + ((officialError != null || communityError != null) ? " · catalog partial" : ""));

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
            Catalog.Entry candidate = info.installed()
                    ? remote.get(key(info.name())) : null;
            boolean known = candidate != null && candidate.version != null
                    && !candidate.version.isEmpty();
            boolean canUpgrade = known
                    && Catalog.compareVersions(candidate.version, info.version()) > 0;

            String state;
            if (!info.installed()) {
                state = info.incompatible() ? "incompatible with this firmware"
                        : "status " + info.status;
            } else if (canUpgrade) {
                state = info.version() + " \u2192 " + candidate.version + " available";
            } else if (known) {
                state = info.version() + " · up to date";
            } else if (candidate != null) {
                state = info.version() + " · in catalog";
            } else {
                state = info.version() + (catalogDone ? " · local build" : " · installed");
            }
            labels.addView(Ui.text(app, state, 11,
                    canUpgrade ? Ui.ACCENT : (known ? Ui.OK : Ui.MUTED), false));
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (canUpgrade) {
                TextView upgrade = Ui.text(app, "Upgrade", 11, Ui.ACCENT, true);
                upgrade.setPadding(dp(10), dp(5), dp(10), dp(5));
                upgrade.setBackground(Ui.rounded(Ui.ACCENT_SOFT, dp(30), Ui.ACCENT_LINE, 1));
                LinearLayout.LayoutParams upgradeParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                upgradeParams.rightMargin = dp(6);
                upgrade.setOnClickListener(v -> confirmUpgrade(info.slot, candidate));
                row.addView(upgrade, upgradeParams);
            }

            TextView erase = pill("Delete", Ui.DANGER, 0x1AF87171, 0x55F87171);
            erase.setOnClickListener(v -> confirmDelete(info.slot));
            row.addView(erase);

            listBox.addView(row);
            listBox.addView(Ui.divider(app));
        }
        if (!any) {
            listBox.addView(Ui.body(app, "No apps installed. Use “Install app from local” to add one."));
        }
    }

    /* ---------------------------- upgrades ------------------------------ */

    /**
     * The catalogs put the useful name in different fields: the official archive
     * fills {@code edition} with the label and {@code name} with "Beam.app", the
     * community one fills {@code edition} with the name and {@code name} with the
     * whole "apps/messenger/1.8/MESSENGER.app" path. Key on the label.
     */
    private static String entryKey(Catalog.Entry entry) {
        String label = entry.edition != null && !entry.edition.isEmpty()
                ? entry.edition : entry.name;
        return key(label);
    }

    private static String entryLabel(Catalog.Entry entry) {
        return entry.edition != null && !entry.edition.isEmpty()
                ? entry.edition : entry.name;
    }

    private static String key(String name) {
        if (name == null) return "";
        String id = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.endsWith(".app")) id = id.substring(0, id.length() - 4);
        /* File names and header names differ in spacing and punctuation:
         * "APRSRX.app" vs "APRS RX", "CWPRACTICE.app" vs "CW PRACTICE". */
        return id.replaceAll("[^a-z0-9]", "");
    }

    /** Compares the installed apps against the catalogs, once the list is known. */
    private void checkUpdates() {
        if (catalogChecked || firmware == null || firmware.isEmpty()) return;
        catalogChecked = true;
        final List<Radio.AppInfo> snapshot = new ArrayList<>(apps);
        runLocal("app-updates", progress -> {
            CatalogData data = new CatalogData();
            progress.publish(15, "Reading the official catalog…");
            try {
                List<Catalog.Entry> list = Catalog.apps(firmware);
                data.official = list.size();
                data.entries.addAll(list);
            } catch (Exception e) {
                data.officialError = e.getMessage();
            }
            progress.publish(35, "Reading the community catalog…");
            try {
                List<Catalog.Entry> list = Catalog.community(progress);
                data.community = list.size();
                data.entries.addAll(list);
            } catch (Exception e) {
                data.communityError = e.getMessage();
            }

            java.util.Map<String, Catalog.Entry> best = new java.util.HashMap<>();
            for (Catalog.Entry entry : data.entries) {
                if (entryKey(entry).isEmpty()) continue;
                String id = entryKey(entry);
                Catalog.Entry previous = best.get(id);
                if (previous == null
                        || Catalog.compareVersions(entry.version, previous.version) > 0) {
                    best.put(id, entry);
                }
            }

            /* The official archive publishes file names only, no version. Read the
             * 64-byte FAP1 header of the installed ones to learn their version. */
            int done = 0;
            int total = 0;
            for (Radio.AppInfo info : snapshot) if (info.installed()) total++;
            for (Radio.AppInfo info : snapshot) {
                if (!info.installed()) continue;
                String id = key(info.name());
                Catalog.Entry entry = best.get(id);
                if (entry == null || !entry.version.isEmpty()) continue;
                done++;
                progress.publish(35 + done * 60 / Math.max(1, total),
                        "Checking " + info.name() + "…");
                try {
                    byte[] file = Catalog.download(entry.url, null, 0, 100, entry.sha256);
                    if (file.length >= Radio.APP_HDR_SIZE
                            && UvProtocol.u32(file, 0) == Radio.APP_MAGIC) {
                        String version = Radio.ascii(file, 36, 16);
                        if (!version.isEmpty()) {
                            best.put(id, new Catalog.Entry(entry.name, version,
                                    entry.edition, entry.size, entry.url, entry.sha256,
                                    entry.community, entry.author, entry.description,
                                    entry.screen));
                        }
                    }
                } catch (Exception ignored) { /* keep going, one app at a time */ }
            }
            data.entries.clear();
            data.entries.addAll(best.values());
            return data;
        }, (value, error) -> {
            if (error != null) {
                catalogChecked = false;
                catalogDone = true;
                officialError = error.getMessage();
                render();
                return;
            }
            catalogDone = true;
            officialError = value.officialError;
            communityError = value.communityError;
            remote.clear();
            for (Catalog.Entry entry : value.entries) {
                if (entryKey(entry).isEmpty()) continue;
                remote.put(entryKey(entry), entry);
                /* Keep the published preview so installed apps show it as well,
                 * not only while the catalog dialog is open. */
                if (entry.screen != null && !entry.screen.isEmpty()) {
                    knownScreens.put(entryKey(entry), entry.screen);
                }
            }
            render();
        });
    }

    private void confirmUpgrade(final int slot, final Catalog.Entry entry) {
        new AlertDialog.Builder(app)
                .setTitle("Upgrade " + entryLabel(entry) + "?")
                .setMessage("Slot " + (slot + 1) + ": " + versionAt(slot)
                        + " → " + entry.version + "\n\n"
                        + entry.size + " bytes, checked against the catalog SHA-256 before "
                        + "anything is written. The slot is erased and rewritten.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Upgrade", (dialog, which) -> downloadInto(slot, entry))
                .show();
    }

    private String versionAt(int slot) {
        for (Radio.AppInfo info : apps) {
            if (info.slot == slot) return info.version();
        }
        return "?";
    }

    private void downloadInto(final int slot, final Catalog.Entry entry) {
        runLocal("app-upgrade",
                progress -> Catalog.download(entry.url, progress, 0, 100, entry.sha256),
                (bytes, error) -> {
                    if (error != null) {
                        app.toast("Download failed: " + error.getMessage());
                        return;
                    }
                    doInstall(slot, bytes);
                });
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

        java.util.Map<String, Radio.AppInfo> installed = new java.util.HashMap<>();
        for (Radio.AppInfo info : apps) {
            if (info.installed()) installed.put(key(info.name()), info);
        }

        for (Catalog.Entry entry : list) {
            if (entry.screen != null && !entry.screen.isEmpty()) {
                knownScreens.put(entryKey(entry), entry.screen);
            }

            String id = entryKey(entry);
            Radio.AppInfo current = installed.get(id);
            boolean canUpgrade = current != null && !entry.version.isEmpty()
                    && Catalog.compareVersions(entry.version, current.version()) > 0;

            LinearLayout row = Ui.row(app);
            row.setPadding(0, dp(6), 0, dp(6));

            Bitmap art = screenBitmap(entry.screen);
            if (art == null && !id.isEmpty()) {
                art = screenBitmap(AppScreens.get(app, id));
            }
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
            labels.addView(Ui.text(app, entryLabel(entry), 14, Ui.TEXT, true));

            StringBuilder meta = new StringBuilder();
            if (!entry.version.isEmpty()) meta.append("v").append(entry.version);
            if (entry.community) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append("community");
            }
            if (entry.size > 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(Math.round(entry.size / 1024f)).append(" KB");
            }
            if (meta.length() > 0) {
                labels.addView(Ui.text(app, meta.toString(), 11, Ui.MUTED, false));
            }
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (canUpgrade) {
                TextView action = pill("Upgrade", Ui.ACCENT, Ui.ACCENT_SOFT, Ui.ACCENT_LINE);
                action.setOnClickListener(v -> {
                    dialog.dismiss();
                    confirmUpgrade(current.slot, entry);
                });
                row.addView(action);
            } else if (current != null) {
                row.addView(pill("Installed", Ui.OK, 0x1A34D399, 0x5534D399));
            } else {
                TextView action = pill("Install", Ui.ACCENT, Ui.ACCENT_SOFT, Ui.ACCENT_LINE);
                action.setOnClickListener(v -> {
                    dialog.dismiss();
                    downloadApp(entry);
                });
                row.addView(action);
            }

            box.addView(row);
            box.addView(Ui.divider(app));
        }

        dialog.show();
    }

    /** Small pill used for actions and states alike: same padding, same shape. */
    private TextView pill(String label, int color, int fill, int stroke) {
        TextView action = Ui.text(app, label, 11, color, true);
        action.setPadding(dp(10), dp(5), dp(10), dp(5));
        action.setBackground(Ui.rounded(fill, dp(30), stroke, 1));
        return action;
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
