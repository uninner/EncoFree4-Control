# Enco Free4 local Bluetooth protocol notes

This document is a high-level interoperability note for maintaining the
project. It intentionally excludes decompiled application code, raw packet
captures, Bluetooth addresses, device identifiers, and unrelated system logs.

The notes apply to OPPO Enco Free4 and OPPO Enco X4 behavior observed on the
maintainers' devices. Other models or firmware versions may differ.

## Transport

- Bluetooth Classic SPP / RFCOMM
- Service UUID: `0000079A-D102-11E1-9B23-00025B00A5A5`
- Observed RFCOMM channel: 15
- Observed DLCI: 31

Android should normally select the channel through service discovery. The
implementation retains a direct channel fallback for compatibility with
devices whose SDP result is unavailable.

## TLV command packet

Ordinary commands use a five-byte header:

```text
Offset  Size  Meaning
0       2     Command ID, little endian
2       1     Sequence number
3       2     Payload length, little endian
5       N     Payload
```

The sequence number increments for each command and wraps at 255.

## RFCOMM application frame

```text
AA | Length varint | Control | Reserved | TLV
```

For normal short control frames:

- `AA` is the synchronization byte.
- The varint value represents the number of bytes remaining after the
  varint itself.
- The final byte of a multiline application frame must not be truncated.
- Control and Reserved are both `00` for the short commands used here.

The implementation reads `remaining = value` after decoding the varint and
continues reading until all remaining bytes arrive.

## Initialization

The current connection flow sends:

1. `0x0100`: protocol hello, empty payload.
2. `0x0200`: notification capability query, empty payload.
3. `0x0205`: register notification events. Current payload is
   `09 F1 F2 F3 01 02 03 04 08 0B` (nine event identifiers). This matches
   `EncoProtocol.registerNotificationsFrame()`; the earlier `02 01 03`
   documentation did not describe the shipping implementation.
4. `0x010C`: query the current noise mode. Primary payload `01 01`;
   legacy compatibility request `02 01` may also be sent.
5. `0x012F`: batch state request. Current payload is `01 06 01 00 00`.

Battery and noise state are then periodically refreshed.

## Noise mode

Set command: `0x0404`

```text
Payload: 01 01 MODE
```

Supported three-state values:

| Value | Mode |
|---:|---|
| `0x01` | Off |
| `0x02` | Noise cancellation |
| `0x04` | Transparency |

Query command: `0x010C`, primary payload `01 01`, legacy payload `02 01`.

The current-mode notification uses command `0x0204`, event `03`, subtype
`01`, followed by a little-endian bitmask. The `0x010C` query can return
command `0x810C` with `01 01` followed by the same mode bitmask, sometimes
after a leading status byte. Both paths are parsed so a change made while the
panel was closed is recovered on opening it. Query and notification handling
must not assume the mode bitmask is only one byte. The target-device response
shape still needs physical-device confirmation. The primary query uses
`01 01`, corroborated by independent Free4-capable implementations; the
original project's `02 01` request is preserved as a fallback pending
target-device testing.

Free4 ANC depth/adaptive bitmap indices `5`, `6`, `7`, and `11` are displayed
as the panel's ANC major mode; this three-state UI does not expose those
submodes separately.

## Battery

Direct request: `0x0106`, empty payload.

Batch request: `0x012F`

The project uses a first-connection batch request and searches the `0x812F`
response for a `0x0106` battery sub-response. The sub-response data is:

```text
00 | COUNT | (DEVICE_TYPE | BATTERY_BYTE) * COUNT
```

Battery notifications use `0x0204`, event `01`:

```text
Event | Count | (DeviceType | BatteryByte) * Count
```

Battery byte:

```text
Charging = value & 0x80
Level    = value & 0x7F
```

Device types used by the UI:

| Value | Device |
|---:|---|
| 1 | Left earbud |
| 2 | Right earbud |
| 3 | Charging case |

The case may be absent from some notifications. The UI should therefore
accept a response containing only the two earbuds.

## Earbud case status

Case status also uses `0x0204`, event `02`:

```text
Event | Count | (DeviceType | Flags) * Count
```

Observed flag interpretation:

```text
InBox      = (Flags & 0x01) == 0
InEar      = (Flags & 0x02) != 0
CoverClose = (Flags & 0x04) == 0
```

The charging-case battery is displayed only when both earbuds are marked
as being inside the case.

## Enco X4 (firmware 116.116.100)

X4 answered the same transport and command set as Free4 on the first
connection, so the three-mode panel needs no model-specific branch.

- Service UUID, framing, sequence handling and the notification capability
  reply are identical. The `0x0200` query answers `0x8200` with
  `09 01 02 03 04 08 0B F1 F2 F3`, the same nine events Free4 registers.
- Noise mode set `0x0404` with payload `01 01 MODE` works with the same values:
  `01` off, `02` cancellation, `04` transparency. The reply is `0x8404` with a
  status byte where `00` means accepted.
- The firmware answers `0x8404` with status `0x0E` while the earbuds sit in the
  charging case. Take them out and wear them before sending the command; this is
  not a protocol difference.
- Mode bitmaps observed from `0x010C` with selector `01 01`: `0x0008` (bit 3)
  off, `0x0010` (bit 4) cancellation, `0x0100` (bit 8) transparency. All three
  fall inside the existing `EncoProtocol.modeFromProtocolIndex` table.
- A mode change made with the earbud touch control is reported through
  `0x0204`, event `03`, as `03 01 01` followed by the same little-endian bitmap.
- Battery over `0x012F` → `0x0106` uses device types 1/2/3 and the same
  charging bit as Free4. The case entry disappears from the response once the
  earbuds are out of the case.
- Cancellation exposes four steps. `0x0404` accepts the state bitmap bit directly:
  `0x10`, `0x20`, `0x40` and `0x80` select bits 4, 5, 6 and 7, and the firmware
  reports the same bit back through `0x010C` (`01 01`). The legacy `0x01`/`0x02`/`0x04`
  values keep working, but `0x02` means "cancellation, keep the remembered step", so
  a panel that only sends `0x02` cannot change or show the step. The three-state
  mapping already folds bits 4..7 into cancellation.
- On X4 the four steps are, in ascending bitmap order: bit 4 deep cancellation
  (real-time), bit 5 medium, bit 6 light, bit 7 adaptive/automatic switching.
  Verified by selecting each step in the vendor app on a second phone and reading
  the bitmap back, so these are the vendor's own labels.
- Adaptive is a fourth major mode, not a cancellation step. It sits at bitmap bit
  12 (`0x1000`): the `0x010C` reply carries `00 10` and the set command carries
  the little-endian 16-bit bitmap as `01 01 00 10`. The earbud touch gesture
  cycles off (bit 3) → cancellation (bit 4..7) → adaptive (bit 12) →
  transparency (bit 8). Values `05`/`06`/`07`/`09` in the mode field are accepted
  but land on other modes, so this field is a bitmap rather than an enum.
- The legacy `02 01` selector of `0x010C` returns a constant `0x0006`
  (bits 1 and 2) on X4 and does not describe the three-state mode. It most
  likely carries ANC depth/adaptive state; the write path for those submodes is
  still unverified.

## Maintenance guidance

- Keep protocol parsing in `EncoProtocol.java`.
- Keep Bluetooth lifecycle and frame reading in `EncoController.java`.
- Run `tools/test_java_protocol.ps1` with JDK 17 or newer whenever frame parsing
  changes. It compiles and exercises the production `EncoProtocol.java` directly.
  `tools/test_protocol_vectors.mjs` remains a supplementary reference-vector check.
- Never add raw Bluetooth addresses or unredacted system logs to the project.
- Test firmware-dependent assumptions on physical hardware before release.
