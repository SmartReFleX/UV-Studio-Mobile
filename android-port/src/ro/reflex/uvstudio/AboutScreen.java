package ro.reflex.uvstudio;

import android.content.Intent;
import android.graphics.Paint;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Static information about the app, its author, the protocol and the legal notice. */
final class AboutScreen {

    private static final String GITHUB = "https://github.com/SmartReFleX";
    private static final String SUPPORT = "https://revolut.me/smartreflex";

    private final MainActivity app;

    AboutScreen(MainActivity app) {
        this.app = app;
    }

    View create() {
        LinearLayout column = Ui.column(app, 0);
        column.setPadding(Ui.dp(app, 16), Ui.dp(app, 14), Ui.dp(app, 16), Ui.dp(app, 26));

        TextView heading = Ui.title(app, "UV Studio Mobile");
        column.addView(heading);
        TextView sub = Ui.body(app, "Native Android client");
        sub.setPadding(0, Ui.dp(app, 4), 0, Ui.dp(app, 12));
        column.addView(sub);

        column.addView(Ui.label(app, "DETAILS"));
        LinearLayout details = Ui.card(app);
        addDetail(details, "Author", "Claudiu Panga (SmartReFleX)");
        TextView github = Ui.text(app, GITHUB, 13, Ui.ACCENT, false);
        github.setPaintFlags(github.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        github.setPadding(0, 0, 0, Ui.dp(app, 12));
        github.setOnClickListener(v -> open(GITHUB));
        details.addView(github);
        addDetail(details, "Version", "v" + app.versionName());
        addDetail(details, "Operation", "Mobile");
        addDetail(details, "Compatibility", "UV-K5 V3 and UV-K1 with PY32F071 MCU");
        column.addView(details);

        column.addView(Ui.label(app, "SUPPORT"));
        LinearLayout support = Ui.card(app);
        support.addView(Ui.body(app, "If this app is useful to you, you can buy me a coffee."));
        TextView buy = Ui.primary(app, "Buy me a coffee");
        LinearLayout.LayoutParams buyParams = Ui.matchWrap();
        buyParams.topMargin = Ui.dp(app, 10);
        support.addView(buy, buyParams);
        buy.setOnClickListener(v -> open(SUPPORT));
        column.addView(support);

        column.addView(Ui.label(app, "HOW IT WORKS"));
        LinearLayout how = Ui.card(app);
        TextView howText = Ui.body(app,
                "The app talks to the radio itself: no browser and no website. "
                + "Commands travel over the USB-C OTG cable as CDC-ACM packets "
                + "(ab cd … crc … dc ba), the framing the firmware understands.\n\n"
                + "Every tool is a native Android screen: live view, firmware flashing, "
                + "multiboot slots, the app manager, calibration, boot logo, RF log and "
                + "external flash. Everything works offline; only the firmware and app "
                + "catalogs are downloaded when you ask for them.");
        howText.setPadding(0, 0, 0, 0);
        how.addView(howText);
        column.addView(how);

        column.addView(Ui.label(app, "NOTICE"));
        LinearLayout notice = Ui.card(app);
        notice.addView(Ui.body(app,
                "UV Studio Mobile © 2026 Claudiu Panga (SmartReFleX). "
                + "Licensed under the Apache License 2.0.\n\n"
                + "Independent native Android client compatible with UV Studio by Armel "
                + "FAUVEAU (F4HWN), licensed under the Apache License 2.0. The device "
                + "protocol was reimplemented in Java; changes were made.\n\n"
                + "Unofficial community project, not tested or supported by F4HWN."));
        column.addView(notice);

        column.addView(Ui.label(app, "LICENCE"));
        LinearLayout licence = Ui.card(app);
        licence.addView(Ui.body(app,
                "App source code © 2026 Claudiu Panga, licensed under the Apache "
                + "License 2.0. The full licence text and the NOTICE are bundled with "
                + "the app (assets/LICENSE.txt, assets/NOTICE.txt)."));
        column.addView(licence);

        column.addView(Ui.label(app, "LEGAL"));
        LinearLayout legal = Ui.card(app);
        legal.addView(Ui.body(app,
                "Use the app only on equipment you own. Flashing firmware, restoring "
                + "calibration and writing the external flash modify your radio; keep a "
                + "backup of the calibration data before changing anything.\n\n"
                + "Transmitting may require a licence in your country.\n\n"
                + "No warranty of any kind. The author is not liable for any damage, "
                + "data loss or malfunction of the radio or of any other equipment caused "
                + "by using this app. Use it at your own risk."));
        column.addView(legal);

        return Ui.scroll(app, column);
    }

    private void addDetail(LinearLayout card, String label, String value) {
        TextView l = Ui.text(app, label, 10, Ui.MUTED, true);
        l.setLetterSpacing(0.08f);
        card.addView(l);
        TextView v = Ui.text(app, value, 13, Ui.TEXT, false);
        v.setPadding(0, Ui.dp(app, 2), 0, Ui.dp(app, 10));
        card.addView(v);
    }

    private void open(String url) {
        try {
            app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            app.toast("No browser installed.");
        }
    }
}
