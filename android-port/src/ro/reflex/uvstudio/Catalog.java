package ro.reflex.uvstudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Online catalogs, the same sources the web client uses:
 *
 *   firmware: archive/*.bin             in armel/uv-k1-k5v3-firmware-custom
 *   apps:     archive/apps/v<fw>/*.app  in the same repository
 *
 * Both are read through the GitHub Contents API (JSON) and downloaded from the
 * raw URLs it returns. Nothing is trusted blindly: the firmware package trailer
 * and the app FAP1 header are validated again before anything is written.
 */
final class Catalog {

    private static final String API = "https://api.github.com/repos/armel/uv-k1-k5v3-firmware-custom/contents";
    private static final String COMMUNITY = "https://raw.githubusercontent.com/armel/fusion-community/main";
    private static final Pattern FIRMWARE = Pattern.compile(
            "(?i)^f4hwn[._-](?:(k1|k5v3)[._-])?([a-z][a-z0-9-]*)(?:[._-].*)?\\.bin$");
    private static final Pattern VERSION = Pattern.compile("(?i)[._-]v(\\d+(?:\\.\\d+)*)");

    static final class Entry {
        final String name;
        final String version;
        final String edition;
        final int size;
        final String url;
        final String sha256;
        final boolean community;
        final String author;
        final String description;
        final String screen;

        Entry(String name, String version, String edition, int size, String url) {
            this(name, version, edition, size, url, null, false, "", "", "");
        }

        Entry(String name, String version, String edition, int size, String url,
              String sha256, boolean community) {
            this(name, version, edition, size, url, sha256, community, "", "", "");
        }

        Entry(String name, String version, String edition, int size, String url,
              String sha256, boolean community, String author, String description,
              String screen) {
            this.name = name;
            this.version = version;
            this.edition = edition;
            this.size = size;
            this.url = url;
            this.sha256 = sha256;
            this.community = community;
            this.author = author;
            this.description = description;
            this.screen = screen;
        }

        int major() {
            try {
                return Integer.parseInt(version.split("\\.")[0]);
            } catch (Exception e) {
                return -1;
            }
        }

        String label() {
            String v = version.isEmpty() ? name : "v" + version;
            String sizeLabel = size > 0 ? " · " + Math.round(size / 1024f) + " KB" : "";
            return edition + " · " + v + sizeLabel;
        }
    }

    private Catalog() { }

    /* ---------------------------- firmware ------------------------------- */

    static List<Entry> firmware() throws Exception {
        JSONArray files = getArray(API + "/archive?ref=main");
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            String name = file.optString("name", "");
            Matcher m = FIRMWARE.matcher(name);
            if (!m.matches()) continue;
            String lower = name.toLowerCase();
            if (lower.contains(".beta") || lower.contains(".sa818")) continue;

            Matcher v = VERSION.matcher(name);
            String version = v.find() ? v.group(1) : "";
            if (version.isEmpty()) continue;

            String model = m.group(1) == null ? "" : m.group(1).toLowerCase();
            String editionToken = m.group(2).toLowerCase();
            String edition = model.isEmpty() ? editionToken
                    : (editionToken.equals("fusion") ? "fusion " + model.toUpperCase() : editionToken + " " + model.toUpperCase());

            Entry entry = new Entry(name, version, edition,
                    file.optInt("size", 0), file.optString("download_url", ""));
            if (entry.major() < 5) continue;
            if (entry.url.isEmpty()) continue;
            out.add(entry);
        }

        Collections.sort(out, (a, b) -> {
            int byEdition = a.edition.compareToIgnoreCase(b.edition);
            if (byEdition != 0) return byEdition;
            return compareVersions(b.version, a.version);
        });
        return out;
    }

    /* ------------------------------ apps --------------------------------- */

    static List<Entry> apps(String firmwareName) throws Exception {
        String version = versionOf(firmwareName);
        if (version.isEmpty()) throw new Exception("The firmware version is unknown");

        String folder = null;
        String newest = null;
        JSONArray versions = getArray(API + "/archive/apps?ref=main");
        for (int i = 0; i < versions.length(); i++) {
            JSONObject item = versions.getJSONObject(i);
            String name = item.optString("name", "");
            if (!name.startsWith("v")) continue;
            if (name.equals("v" + version)) {
                folder = name;
                break;
            }
            if (newest == null
                    || compareVersions(name.substring(1), newest.substring(1)) > 0) {
                newest = name;
            }
        }
        /* The archive only publishes app folders for some firmware versions; the
         * newest published folder is compatible through the FAP1 ABI check. */
        if (folder == null) folder = newest;
        if (folder == null) throw new Exception("The app catalog is empty");

        JSONArray files = getArray(API + "/archive/apps/" + folder + "?ref=main");
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i);
            String name = file.optString("name", "");
            if (!name.toLowerCase().endsWith(".app")) continue;
            String url = file.optString("download_url", "");
            if (url.isEmpty()) continue;
            String label = name.replaceAll("(?i)\\.app$", "");
            out.add(new Entry(name, "", label, file.optInt("size", 0), url));
        }
        Collections.sort(out, (a, b) -> a.edition.compareToIgnoreCase(b.edition));
        return out;
    }

    /** Community catalog published by the radio community (apps.json, format 1). */
    static List<Entry> community() throws Exception {
        JSONObject root = new JSONObject(getText(COMMUNITY + "/apps.json"));
        if (root.optInt("format", 0) != 1) {
            throw new Exception("Unsupported community catalog format");
        }
        JSONArray apps = root.optJSONArray("apps");
        List<Entry> out = new ArrayList<>();
        if (apps == null) return out;

        for (int i = 0; i < apps.length(); i++) {
            JSONObject item = apps.getJSONObject(i);
            String file = item.optString("file", "");
            String sha = item.optString("sha256", "").toLowerCase();
            if (!file.matches("apps/[\\w.-]+/[\\w.-]+/[A-Za-z0-9]+\\.app")) continue;
            if (!sha.matches("[0-9a-f]{64}")) continue;

            String name = item.optString("name", item.optString("id", "app"));
            String version = item.optString("version", "");
            out.add(new Entry(file, version, name, item.optInt("size", 0),
                    COMMUNITY + "/" + file, sha, true,
                    item.optString("author", ""),
                    item.optString("description", ""),
                    item.optString("screen", "")));
        }
        Collections.sort(out, (a, b) -> a.edition.compareToIgnoreCase(b.edition));
        return out;
    }

    /** "fusion v7.03.01" or "fusion_k1_v7.03.01.bin" -> "7.03.01". */
    static String versionOf(String name) {
        if (name == null) return "";
        Matcher m = Pattern.compile("(?i)v(\\d+(?:\\.\\d+)+)").matcher(name);
        return m.find() ? m.group(1) : "";
    }

    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int da = i < pa.length ? parseInt(pa[i]) : 0;
            int db = i < pb.length ? parseInt(pb[i]) : 0;
            if (da != db) return da - db;
        }
        return 0;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /* ---------------------------- download ------------------------------- */

    static byte[] download(String url, Worker.Progress progress, int from, int to) throws Exception {
        return download(url, progress, from, to, null);
    }

    static byte[] download(String url, Worker.Progress progress, int from, int to,
                           String expectedSha) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "UV-Studio-Mobile");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("Download failed: HTTP " + code);
            int total = connection.getContentLength();
            ByteArrayOutputStream out = new ByteArrayOutputStream(total > 0 ? total : 1 << 16);
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int read;
                int done = 0;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (total > 0 && progress != null) {
                        progress.publish(from + (to - from) * done / total,
                                "Downloading " + done / 1024 + " / " + total / 1024 + " KB");
                    }
                }
            }
            byte[] bytes = out.toByteArray();
            if (expectedSha != null && !expectedSha.equalsIgnoreCase(sha256(bytes))) {
                throw new Exception("SHA-256 mismatch — refusing the download");
            }
            return bytes;
        } finally {
            connection.disconnect();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "UV-Studio-Mobile");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " for " + url);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    private static JSONArray getArray(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "UV-Studio-Mobile");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("GitHub API HTTP " + code
                    + (code == 403 ? " (rate limit — try again later)" : ""));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return new JSONArray(new String(out.toByteArray(), "UTF-8"));
        } finally {
            connection.disconnect();
        }
    }
}
