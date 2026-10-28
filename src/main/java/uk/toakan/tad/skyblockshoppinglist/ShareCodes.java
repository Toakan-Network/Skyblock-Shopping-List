package uk.toakan.tad.skyblockshoppinglist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Share codes: JSON gzipped and Base64-encoded behind a prefix, for pasting into chat or Discord. Used by HUD
 * presets ({@link HudPresets}) and saved lists ({@link SavedLists}), each with its own prefix.
 */
public final class ShareCodes {
    private ShareCodes() {}

    public static String encode(String prefix, String json) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return prefix + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    /**
     * The JSON inside {@code code}, or null when it isn't a code with this prefix, is over {@code maxChars}, or
     * unpacks to more than {@code maxJsonBytes}. Oversized codes are refused before they are decoded or unpacked.
     */
    public static String decode(String prefix, String code, int maxChars, int maxJsonBytes) {
        if (code == null) return null;
        code = code.trim();
        if (!code.startsWith(prefix) || code.length() - prefix.length() > maxChars) return null;
        try {
            // MIME decoding skips line breaks and spaces, which Discord and chat put into long pasted codes.
            byte[] packed = Base64.getMimeDecoder().decode(code.substring(prefix.length()));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(packed))) {
                // One byte over the limit tells an oversized code from one that just fits.
                byte[] raw = gzip.readNBytes(maxJsonBytes + 1);
                if (raw.length > maxJsonBytes) return null;
                return new String(raw, StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }
}
