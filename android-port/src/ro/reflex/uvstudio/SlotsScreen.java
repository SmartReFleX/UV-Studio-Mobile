package ro.reflex.uvstudio;

import android.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Multiboot slots: four firmware images stored next to each other in the
 * external flash, picked at boot by holding MENU. The firmware has no rename
 * command, so a new name means a full rewrite of the slot.
 */
final class SlotsScreen extends ToolScreen {

    private LinearLayout slotsBox;
    private TextView selectedLabel;
    private EditText nameInput;
    private final TextView[] slotChips = new TextView[Radio.SLOT_COUNT + 1];
    private int targetSlot = 1;
    private byte[] image;
    private String firmwareVersion = "";

    SlotsScreen(MainActivity app, RadioLink link) {
        super(app, link, "Firmware slots",
                "Store several compatible firmwares on the radio and choose one at boot "
                + "(hold MENU). Writing only touches the external flash, so the running "
                + "firmware stays usable.");
    }

    @Override
    protected void build() {
        LinearLayout listCard = plain();
        slotsBox = new LinearLayout(app);
        slotsBox.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(slotsBox);
        slotsBox.addView(Ui.body(app, "No slot table yet."));

        button("Refresh slots", 1, v -> refresh());

        LinearLayout writeCard = plain();
        writeCard.addView(Ui.label(app, "WRITE FIRMWARE INTO A SLOT"));

        selectedLabel = Ui.body(app, "No file selected.");
        writeCard.addView(selectedLabel);

        LinearLayout row = Ui.row(app);
        row.setPadding(0, dp(8), 0, 0);
        writeCard.addView(row);
        rowButton(row, "Choose .bin…", 0, v -> chooseFirmware());
        rowButton(row, "Catalog…", 3, v -> openCatalog());

        addProgressBlock();

        LinearLayout chips = Ui.row(app);
        chips.setPadding(0, dp(10), 0, 0);
        writeCard.addView(chips);
        for (int slot = 1; slot <= Radio.SLOT_COUNT; slot++) {
            final int value = slot;
            TextView chip = Ui.chip(app, String.valueOf(slot), slot == targetSlot);
            chip.setOnClickListener(v -> selectSlot(value));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            params.rightMargin = dp(6);
            chips.addView(chip, params);
            slotChips[slot] = chip;
        }
        selectSlot(1);

        nameInput = Ui.input(app, "Display name (max 15)", "");
        nameInput.setMaxLines(1);
        LinearLayout.LayoutParams inputParams = Ui.matchWrap();
        inputParams.topMargin = dp(10);
        writeCard.addView(nameInput, inputParams);

        button("Write to slot", 1, v -> writeSlot());
    }

    @Override
    protected boolean progressAtTop() {
        return false;
    }

    private void selectSlot(int slot) {
        targetSlot = slot;
        for (int i = 1; i < slotChips.length; i++) {
            TextView chip = slotChips[i];
            if (chip == null) continue;
            boolean active = i == slot;
            chip.setBackground(Ui.rounded(active ? Ui.ACCENT : Ui.CARD, dp(12), active ? 0 : Ui.LINE, active ? 0 : 1));
            chip.setTextColor(active ? Ui.ACCENT_INK : Ui.MUTED);
        }
    }

    private void refresh() {
        withRadio("slots-refresh", progress -> {
            Radio.sleep(400);
            app.radio.deviceInfo(1500);
            Radio.SlotInfo[] slots = new Radio.SlotInfo[Radio.SLOT_COUNT + 1];
            for (int slot = 1; slot <= Radio.SLOT_COUNT; slot++) {
                progress.publish(slot * 25, "Reading slot " + slot + "…");
                try {
                    slots[slot] = app.radio.slotInfo(slot);
                } catch (Exception e) {
                    Radio.SlotInfo info = new Radio.SlotInfo();
                    info.slot = slot;
                    info.status = 6;
                    slots[slot] = info;
                }
            }
            return slots;
        }, (value, error) -> {
            if (error != null) return;
            render(value);
            finishProgress("Slots refreshed", Ui.OK);
        });
    }

    private void render(Radio.SlotInfo[] slots) {
        slotsBox.removeAllViews();
        for (int slot = 1; slot <= Radio.SLOT_COUNT; slot++) {
            Radio.SlotInfo info = slots[slot];
            LinearLayout row = Ui.row(app);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout labels = new LinearLayout(app);
            labels.setOrientation(LinearLayout.VERTICAL);
            String title = "#" + slot + "  " + (info.empty() ? "empty" : info.name());
            labels.addView(Ui.text(app, title, 14, Ui.ACCENT, true));

            String detail;
            if (info.empty()) {
                detail = statusText(info.status);
            } else {
                detail = info.version() + " · " + formatSize(info.imageSize()) + " · " + statusText(info.status);
            }
            labels.addView(Ui.text(app, detail, 11, Ui.MUTED, false));
            row.addView(labels, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!info.empty()) {
                final int index = slot;
                TextView erase = Ui.text(app, "Erase", 11, Ui.DANGER, true);
                erase.setPadding(dp(8), dp(6), dp(8), dp(6));
                erase.setOnClickListener(v -> confirmErase(index));
                row.addView(erase);

                TextView reset = Ui.text(app, "Reset cfg", 11, Ui.WARN, true);
                reset.setPadding(dp(8), dp(6), dp(8), dp(6));
                reset.setOnClickListener(v -> confirmProfileErase(index));
                row.addView(reset);
            }

            slotsBox.addView(row);
            if (slot < Radio.SLOT_COUNT) slotsBox.addView(Ui.divider(app));
        }
    }

    private static String statusText(int status) {
        switch (status) {
            case 0: return "valid";
            case 1: return "empty";
            case 2: return "new header";
            case 3: return "incomplete";
            case 4: return "bad size";
            case 5: return "bad CRC";
            case 6: return "SPI error / no answer";
            case 7: return "bad slot";
            default: return "status " + status;
        }
    }

    private static String formatSize(int bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f);
    }

    private void confirmErase(int slot) {
        new AlertDialog.Builder(app)
                .setTitle("Erase slot " + slot + "?")
                .setMessage("The firmware stored in this slot is deleted. The running firmware is not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Erase", (dialog, which) -> eraseSlot(slot))
                .show();
    }

    private void eraseSlot(int slot) {
        withRadio("slot-erase", progress -> {
            Radio.sleep(400);
            app.radio.deviceInfo(1500);
            app.radio.slotErase(slot);
            return app.radio.slotInfo(slot);
        }, (value, error) -> {
            if (error != null) return;
            finishProgress("Slot " + slot + " erased", Ui.OK);
            refresh();
        });
    }

    private void confirmProfileErase(int slot) {
        new AlertDialog.Builder(app)
                .setTitle("Reset slot " + slot + " settings?")
                .setMessage("The next boot on this slot re-seeds factory defaults: channels and "
                        + "settings stored by that firmware are lost. The image itself stays.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> profileErase(slot))
                .show();
    }

    private void profileErase(int slot) {
        withRadio("profile-erase", progress -> {
            Radio.sleep(400);
            app.radio.deviceInfo(1500);
            app.radio.profileErase(slot);
            return true;
        }, (value, error) -> {
            if (error != null) return;
            finishProgress("Settings of slot " + slot + " reset", Ui.OK);
        });
    }

    private void openCatalog() {
        runLocal("slot-catalog", progress -> Catalog.firmware(), (value, error) -> {
            if (error != null) {
                app.toast("Catalog: " + error.getMessage());
                return;
            }
            if (value.isEmpty()) {
                app.toast("No firmware in the catalog.");
                return;
            }
            String[] labels = new String[value.size()];
            for (int i = 0; i < value.size(); i++) labels[i] = value.get(i).label();
            new AlertDialog.Builder(app)
                    .setTitle("Firmware catalog")
                    .setItems(labels, (dialog, which) -> download(value.get(which)))
                    .show();
        });
    }

    private void download(Catalog.Entry entry) {
        runLocal("slot-download", progress -> Catalog.download(entry.url, progress, 0, 100),
                (bytes, error) -> {
                    if (error != null) {
                        app.toast("Download failed: " + error.getMessage());
                        return;
                    }
                    FirmwarePackage parsed = FirmwarePackage.parse(bytes);
                    if (parsed.error != null) {
                        selectedLabel.setTextColor(Ui.DANGER);
                        selectedLabel.setText(parsed.error);
                        return;
                    }
                    image = parsed.image;
                    firmwareVersion = "v" + entry.version;
                    selectedLabel.setTextColor(Ui.MUTED);
                    selectedLabel.setText(entry.name + " · " + formatSize(image.length));
                    String suggested = (entry.edition + " v" + entry.version).trim();
                    if (suggested.length() > 15) suggested = suggested.substring(0, 15).trim();
                    nameInput.setText(suggested);
                    finishProgress("Firmware downloaded", Ui.OK);
                });
    }

    private void chooseFirmware() {
        app.pickFile("application/octet-stream", new String[] {"application/octet-stream", "*/*"},
                (name, bytes) -> {
                    if (bytes == null) return;
                    FirmwarePackage pack = FirmwarePackage.parse(bytes);
                    if (pack.error != null) {
                        selectedLabel.setTextColor(Ui.DANGER);
                        selectedLabel.setText(pack.error);
                        return;
                    }
                    image = pack.image;
                    firmwareVersion = versionFromName(name, pack);
                    selectedLabel.setTextColor(Ui.MUTED);
                    selectedLabel.setText(name + " · " + formatSize(image == null ? 0 : image.length));
                    if (nameInput.getText().toString().trim().isEmpty()) {
                        nameInput.setText(slotName(name));
                    }
                });
    }

    private static String versionFromName(String file, FirmwarePackage pack) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("v(\\d+\\.\\d+\\.\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(file == null ? "" : file);
        if (m.find()) return "v" + m.group(1);
        return pack.buildId().isEmpty() ? "" : pack.buildId();
    }

    private static String slotName(String file) {
        String base = file == null ? "firmware" : file;
        base = base.replaceAll("(?i)\\.bin$", "");
        base = base.replaceAll("[^A-Za-z0-9 ._-]", " ").trim();
        if (base.length() > 15) base = base.substring(0, 15).trim();
        return base.isEmpty() ? "firmware" : base;
    }

    private void writeSlot() {
        if (image == null) {
            app.toast("Choose a firmware file first.");
            return;
        }
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) {
            app.toast("Give the slot a display name.");
            return;
        }
        if (name.length() > 15) name = name.substring(0, 15);
        final String displayName = name;
        final String version = firmwareVersion;

        new AlertDialog.Builder(app)
                .setTitle("Write slot " + targetSlot + "?")
                .setMessage("The slot is erased and the image is written, then validated. "
                        + "This only touches the external flash; the radio is not restarted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Write", (dialog, which) -> doWrite(displayName, version))
                .show();
    }

    private void doWrite(String name, String version) {
        withRadio("slot-write", progress -> {
            progress.publish(1, "Opening session…");
            Radio.sleep(500);
            app.radio.deviceInfo(1500);
            app.radio.writeFirmwareSlot(targetSlot, image, name, version, progress);
            return app.radio.slotInfo(targetSlot);
        }, (value, error) -> {
            if (error != null) return;
            finishProgress("Slot " + targetSlot + " written and validated", Ui.OK);
            refresh();
        });
    }
}
