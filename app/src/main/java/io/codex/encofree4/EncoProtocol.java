package io.codex.encofree4;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class EncoProtocol {
    static final int CMD_PROTOCOL_HELLO = 0x0100;
    static final int CMD_QUERY_NOTIFICATION_CAPABILITY = 0x0200;
    static final int CMD_REGISTER_NOTIFICATIONS = 0x0205;
    static final int CMD_SET_NOISE_MODE = 0x0404;
    static final int CMD_GET_CURRENT_NOISE_MODE = 0x010C;
    static final int CMD_REQUEST_BATTERY = 0x0106;
    static final int CMD_MULTI_SPP = 0x012F;
    static final int MULTI_SPP_RESPONSE = 0x812F;
    static final int NOTIFICATION_EVENT = 0x0204;
    static final int NOISE_MODE_RESPONSE = 0x810C;
    static final int DIRECT_BATTERY_RESPONSE = 0x0617;

    static final int MODE_OFF = 0;
    static final int MODE_TRANSPARENCY = 1;
    static final int MODE_ANC = 2;
    static final int MODE_ADAPTIVE = 3;
    private static final int WIRE_MODE_OFF = 0x01;
    private static final int WIRE_MODE_ANC = 0x02;
    private static final int WIRE_MODE_TRANSPARENCY = 0x04;

    private EncoProtocol() {
    }

    static byte[] setNoiseModeFrame(int mode, int sequence) {
        if (mode == MODE_ADAPTIVE) {
            // Adaptive sits at bitmap bit 12, so the mode field carries a little-endian
            // 16-bit bitmap instead of the single-byte enum the older modes use.
            return singleFrame(
                    CMD_SET_NOISE_MODE,
                    new byte[]{0x01, 0x01, 0x00, 0x10},
                    sequence
            );
        }
        int mask;
        switch (mode) {
            case MODE_OFF:
                mask = WIRE_MODE_OFF;
                break;
            case MODE_TRANSPARENCY:
                // Enco Free4 labels are reversed relative to the earlier capture.
                mask = WIRE_MODE_TRANSPARENCY;
                break;
            case MODE_ANC:
                mask = WIRE_MODE_ANC;
                break;
            default:
                throw new IllegalArgumentException("Unsupported noise mode: " + mode);
        }
        return singleFrame(
                CMD_SET_NOISE_MODE,
                new byte[]{0x01, 0x01, (byte) mask},
                sequence
        );
    }

    static byte[] queryNoiseModeFrame(int sequence) {
        return singleFrame(
                CMD_GET_CURRENT_NOISE_MODE,
                new byte[]{0x01, 0x01},
                sequence
        );
    }

    static byte[] legacyQueryNoiseModeFrame(int sequence) {
        // Preserve the request used by 1.8.0 as a compatibility fallback.
        return singleFrame(CMD_GET_CURRENT_NOISE_MODE, new byte[]{0x02, 0x01}, sequence);
    }

    static byte[] requestBatteryFrame(int sequence) {
        return singleFrame(CMD_REQUEST_BATTERY, new byte[0], sequence);
    }

    static byte[] multiBatteryRequestFrame(int sequence) {
        return singleFrame(
                CMD_MULTI_SPP,
                new byte[]{0x01, 0x06, 0x01, 0x00, 0x00},
                sequence
        );
    }

    static byte[] protocolHelloFrame(int sequence) {
        return singleFrame(CMD_PROTOCOL_HELLO, new byte[0], sequence);
    }

    static byte[] queryNotificationCapabilityFrame(int sequence) {
        return singleFrame(CMD_QUERY_NOTIFICATION_CAPABILITY, new byte[0], sequence);
    }

    static byte[] registerNotificationsFrame(int sequence) {
        // Same registration set observed in the Enco Free4 firmware capture.
        // Event 1: battery, Event 2: earbud/case state, Event 3: noise mode.
        return singleFrame(
                CMD_REGISTER_NOTIFICATIONS,
                new byte[]{
                        0x09,
                        (byte) 0xF1,
                        (byte) 0xF2,
                        (byte) 0xF3,
                        0x01,
                        0x02,
                        0x03,
                        0x04,
                        0x08,
                        0x0B
                },
                sequence
        );
    }

    static byte[] singleFrame(int command, byte[] payload, int sequence) {
        if (payload == null) {
            payload = new byte[0];
        }
        byte[] tlv = new byte[5 + payload.length];
        tlv[0] = (byte) command;
        tlv[1] = (byte) (command >>> 8);
        tlv[2] = (byte) sequence;
        tlv[3] = (byte) payload.length;
        tlv[4] = (byte) (payload.length >>> 8);
        System.arraycopy(payload, 0, tlv, 5, payload.length);

        byte[] lengthBytes = encodeVarint(tlv.length + 2);
        byte[] frame = new byte[1 + lengthBytes.length + 2 + tlv.length];
        frame[0] = (byte) 0xAA;
        System.arraycopy(lengthBytes, 0, frame, 1, lengthBytes.length);
        frame[1 + lengthBytes.length] = 0;
        frame[2 + lengthBytes.length] = 0;
        System.arraycopy(tlv, 0, frame, 3 + lengthBytes.length, tlv.length);
        return frame;
    }

    static Battery parseBattery(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }

        Battery best = null;
        for (int offset = 0; offset + 7 < data.length; offset++) {
            int command = littleEndian16(data, offset);
            if (command != NOTIFICATION_EVENT
                    && command != DIRECT_BATTERY_RESPONSE
                    && command != MULTI_SPP_RESPONSE) {
                continue;
            }
            int payloadLength = littleEndian16(data, offset + 3);
            if (payloadLength < 4 || offset + 5 + payloadLength > data.length) {
                continue;
            }
            byte[] payload = Arrays.copyOfRange(
                    data,
                    offset + 5,
                    offset + 5 + payloadLength
            );
            Battery candidate;
            if (command == NOTIFICATION_EVENT) {
                candidate = parseNotificationBattery(payload);
            } else if (command == MULTI_SPP_RESPONSE) {
                candidate = parseMultiSppBattery(payload);
            } else {
                candidate = parseDirectBattery(payload);
            }
            if (candidate != null && candidate.count > 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Returns the raw noise-state bitmap carried by {@code 0x0204} or {@code 0x810C},
     * or {@code -1} when the frame carries none.
     *
     * <p>Higher layers need the bitmap itself, not only the folded three-state mode,
     * so the panel can report which cancellation step is active.
     */
    static int parseNoiseMask(byte[] data) {
        if (data == null || data.length < 9) {
            return -1;
        }

        for (int offset = 0; offset + 7 < data.length; offset++) {
            int command = littleEndian16(data, offset);
            if (command != NOTIFICATION_EVENT && command != NOISE_MODE_RESPONSE) {
                continue;
            }
            int payloadLength = littleEndian16(data, offset + 3);
            if (payloadLength < 3 || offset + 5 + payloadLength > data.length) {
                continue;
            }
            byte[] payload = Arrays.copyOfRange(
                    data,
                    offset + 5,
                    offset + 5 + payloadLength
            );
            // Push notification: 03 01 01 MASK...
            // Query response: 01 01 MASK... (sometimes with a leading status byte).
            // The query reply is essential when the mode changed while the panel was closed.
            if (command == NOTIFICATION_EVENT && payload.length >= 4
                    && payload[0] == 0x03 && payload[1] == 0x01 && payload[2] == 0x01) {
                int mask = 0;
                for (int index = 3; index < payload.length && index < 7; index++) {
                    mask |= (payload[index] & 0xFF) << ((index - 3) * 8);
                }
                if (mask != 0) {
                    return mask;
                }
            } else if (command == NOISE_MODE_RESPONSE) {
                // Accept the response marker at the start or after one zero status byte.
                // A loose scan can mistake an unrelated field for the current mode.
                for (int marker = 0; marker <= 1 && marker + 2 < payload.length; marker++) {
                    if (marker == 1 && payload[0] != 0x00) {
                        continue;
                    }
                    if (payload[marker] != 0x01 || payload[marker + 1] != 0x01) {
                        continue;
                    }
                    int mask = 0;
                    for (int index = marker + 2;
                            index < payload.length && index < marker + 6; index++) {
                        mask |= (payload[index] & 0xFF) << ((index - marker - 2) * 8);
                    }
                    if (mask != 0) {
                        return mask;
                    }
                }
            }
        }
        return -1;
    }

    static int parseNoiseMode(byte[] data) {
        int mask = parseNoiseMask(data);
        if (mask <= 0) {
            return -1;
        }
        return modeFromMask(mask);
    }

    static int modeFromMask(int mask) {
        if (mask <= 0) {
            return -1;
        }
        return modeFromProtocolIndex(Integer.numberOfTrailingZeros(mask));
    }

    /** 1..4 for cancellation bitmap bits 4..7, otherwise {@code -1}. */
    static int depthFromMask(int mask) {
        int bits = mask & 0xF0;
        return bits == 0 ? -1 : Integer.numberOfTrailingZeros(bits) - 3;
    }

    static EarStatus parseEarStatus(byte[] data) {
        if (data == null || data.length < 8) {
            return null;
        }

        for (int offset = 0; offset + 7 < data.length; offset++) {
            if (littleEndian16(data, offset) != NOTIFICATION_EVENT) {
                continue;
            }
            int payloadLength = littleEndian16(data, offset + 3);
            if (payloadLength < 6 || offset + 5 + payloadLength > data.length) {
                continue;
            }
            byte[] payload = Arrays.copyOfRange(
                    data,
                    offset + 5,
                    offset + 5 + payloadLength
            );
            if (payload[0] != 0x02) {
                continue;
            }

            int count = payload[1] & 0xFF;
            if (count < 1 || count > 4 || payload.length < 2 + count * 2) {
                continue;
            }

            EarStatus status = new EarStatus();
            int cursor = 2;
            for (int i = 0; i < count; i++) {
                int type = payload[cursor] & 0xFF;
                int flags = payload[cursor + 1] & 0xFF;
                boolean inBox = (flags & 0x01) == 0;
                if (type == 1) {
                    status.leftInBox = inBox;
                    status.hasLeft = true;
                } else if (type == 2) {
                    status.rightInBox = inBox;
                    status.hasRight = true;
                }
                cursor += 2;
            }
            if (status.hasLeft && status.hasRight) {
                return status;
            }
        }
        return null;
    }

    private static int modeFromProtocolIndex(int index) {
        switch (index) {
            case 0:
            case 3:
                return MODE_OFF;
            case 1:
            case 4:
            case 5:
            case 6:
            case 7:
            case 11:
                // Free4 ANC depth/adaptive variants use these bitmap positions.
                // The panel has one ANC button, so show their shared major mode.
                return MODE_ANC;
            case 2:
            case 8:
                return MODE_TRANSPARENCY;
            case 12:
                // Adaptive: a major mode of its own, not one of the cancellation steps.
                return MODE_ADAPTIVE;
            default:
                // Unknown bitmap position: do not guess between the three panel modes,
                // so the panel keeps showing "not read" instead of a wrong mode. The
                // raw frame is visible in the EncoProbe log for mapping a new model.
                return -1;
        }
    }

    private static Battery parseNotificationBattery(byte[] payload) {
        // 01 COUNT (TYPE LEVEL) * COUNT
        if (payload[0] != 0x01) {
            return null;
        }
        int count = payload[1] & 0xFF;
        if (count < 1 || count > 4 || payload.length < 2 + count * 2) {
            return null;
        }

        Battery battery = new Battery();
        int offset = 2;
        for (int i = 0; i < count; i++) {
            int type = payload[offset] & 0xFF;
            int raw = payload[offset + 1] & 0xFF;
            battery.put(type, raw & 0x7F, (raw & 0x80) != 0);
            offset += 2;
        }
        return battery;
    }

    private static Battery parseDirectBattery(byte[] payload) {
        // 00 COUNT (TYPE LEVEL) * COUNT
        if (payload.length < 4 || (payload[0] != 0 && payload[0] != 0x01)) {
            return null;
        }
        int count = payload[1] & 0xFF;
        if (count < 1 || count > 4 || payload.length < 2 + count * 2) {
            return null;
        }

        Battery battery = new Battery();
        int offset = 2;
        for (int i = 0; i < count; i++) {
            int type = payload[offset] & 0xFF;
            int raw = payload[offset + 1] & 0xFF;
            battery.put(type, raw & 0x7F, (raw & 0x80) != 0);
            offset += 2;
        }
        return battery;
    }

    private static Battery parseMultiSppBattery(byte[] payload) {
        if (payload.length < 2 || payload[0] != 0x00) {
            return null;
        }
        int commandCount = payload[1] & 0xFF;
        if (commandCount < 1) {
            return null;
        }

        int cursor = 2;
        for (int i = 0; i < commandCount; i++) {
            if (cursor + 4 > payload.length) {
                return null;
            }
            int command = littleEndian16(payload, cursor);
            int length = littleEndian16(payload, cursor + 2);
            cursor += 4;
            if (cursor + length > payload.length) {
                return null;
            }
            if (command == CMD_REQUEST_BATTERY) {
                byte[] data = Arrays.copyOfRange(payload, cursor, cursor + length);
                return parseDirectBattery(data);
            }
            cursor += length;
        }
        return null;
    }

    private static byte[] encodeVarint(int value) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(2);
        while (value >= 0x80) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
        return output.toByteArray();
    }

    private static int littleEndian16(byte[] data, int offset) {
        if (offset + 1 >= data.length) {
            return -1;
        }
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    static final class Battery {
        int left = -1;
        int right = -1;
        int caseBattery = -1;
        boolean leftCharging;
        boolean rightCharging;
        boolean caseCharging;
        int count;

        private void put(int type, int level, boolean charging) {
            int safeLevel = Math.max(0, Math.min(100, level));
            switch (type) {
                case 1:
                    left = safeLevel;
                    leftCharging = charging;
                    count++;
                    break;
                case 2:
                    right = safeLevel;
                    rightCharging = charging;
                    count++;
                    break;
                case 3:
                    caseBattery = safeLevel;
                    caseCharging = charging;
                    count++;
                    break;
                default:
                    break;
            }
        }
    }

    static final class EarStatus {
        boolean hasLeft;
        boolean hasRight;
        boolean leftInBox;
        boolean rightInBox;

        boolean bothInCase() {
            return hasLeft && hasRight && leftInBox && rightInBox;
        }
    }
}
