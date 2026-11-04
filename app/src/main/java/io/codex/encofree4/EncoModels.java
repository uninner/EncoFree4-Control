package io.codex.encofree4;

import java.util.Locale;

/**
 * Supported headset models.
 *
 * <p>All entries currently share the same Classic Bluetooth SPP transport and the
 * same TLV command set documented in {@code docs/PROTOCOL.md}. Add a model by
 * appending its normalized name token; if a model turns out to need different
 * command parameters or noise bitmaps, branch on the parsed values instead of
 * adding another hard-coded name check in the callers.
 */
final class EncoModels {

    /**
     * Normalized (lowercase, whitespace removed) name fragments.
     *
     * <p>Free4 is the model the original implementation was validated against.
     * X4 is expected to answer the same commands; it is pending on-device
     * verification with firmware 116.116.100.
     */
    private static final String[] NAMES = {
            "encofree4",
            "encox4",
            "encoclip",
    };

    private EncoModels() {
    }

    static String normalize(String deviceName) {
        return deviceName == null
                ? ""
                : deviceName.trim().toLowerCase(Locale.ROOT).replace(" ", "");
    }

    static boolean isSupported(String deviceName) {
        String normalized = normalize(deviceName);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String model : NAMES) {
            if (normalized.contains(model)) {
                return true;
            }
        }
        return false;
    }

    /** Colour keys for the X4 artwork, cycled by the panel's colour swatch. */
    static final String[] X4_COLORS = {"white", "purple", "pink"};
    /** Clip ships in two colours; it shares the swatch but not the X4 palette. */
    static final String[] CLIP_COLORS = {"grey", "sea"};

    /** Colour keys offered for a model; models without artwork return a single entry. */
    static String[] colorsFor(String deviceName) {
        String normalized = normalize(deviceName);
        if (normalized.contains("encoclip")) {
            return CLIP_COLORS;
        }
        if (normalized.contains("encox4")) {
            return X4_COLORS;
        }
        return new String[]{X4_COLORS[0]};
    }

    /**
     * Model artwork. X4 has its own drawing per colour and per state: the open case while
     * both earbuds sit inside, the bare earbuds otherwise. Colours never mix across the two
     * shapes, so a colour always maps to exactly one earbuds image and one case image.
     */
    static int deviceImage(String deviceName, String color, boolean inCase) {
        String normalized = normalize(deviceName);
        if (normalized.contains("encoclip")) {
            boolean sea = "sea".equals(color);
            if (inCase) {
                return sea ? R.drawable.enco_clip_sea_case : R.drawable.enco_clip_grey_case;
            }
            return sea ? R.drawable.enco_clip_sea_buds : R.drawable.enco_clip_grey_buds;
        }
        if (!normalized.contains("encox4")) {
            return R.drawable.enco_device;
        }
        boolean pink = "pink".equals(color);
        boolean purple = "purple".equals(color);
        if (inCase) {
            if (pink) {
                return R.drawable.enco_x4_pink_case;
            }
            if (purple) {
                return R.drawable.enco_x4_purple_case;
            }
            return R.drawable.enco_x4_white_case;
        }
        if (pink) {
            return R.drawable.enco_x4_pink_buds;
        }
        if (purple) {
            return R.drawable.enco_x4_purple_buds;
        }
        return R.drawable.enco_x4_white_buds;
    }

    /** Adaptive is a separate major mode only on the model verified to expose it. */
    static boolean hasAdaptiveMode(String deviceName) {
        return normalize(deviceName).contains("encox4");
    }

    /**
     * Clip is an open-ear clip model with no noise control at all, so the panel and the
     * widget hide that entire section. Unknown names keep the section visible.
     */
    static boolean hasNoiseControl(String deviceName) {
        return !normalize(deviceName).contains("encoclip");
    }

    /**
     * Whether the model exposes the classic SPP control channel this project talks to.
     * Clip answers on none of the known service UUIDs or RFCOMM channels at firmware
     * {@code 116.116.100}, so it is recognised and drawn but not controllable; skipping it
     * up front avoids making the user wait through every fallback.
     */
    static boolean supportsControl(String deviceName) {
        return !normalize(deviceName).contains("encoclip");
    }
}
