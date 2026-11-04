package io.codex.encofree4;

/** Main-thread state; only a device report can confirm a noise mode. */
final class PanelState {
    enum ModeDisplay { WAITING_CONNECTION, SWITCHING, ISSUE, CURRENT, READ_FAILED, READING }
    int confirmedMode = -1;
    int pendingMode = -1;
    int cachedMode = -1;
    EncoProtocol.Battery battery;

    PanelState(int cachedMode) {
        if (validMode(cachedMode)) {
            this.cachedMode = cachedMode;
        }
    }

    ModeDisplay modeDisplay(boolean connected, boolean readTimedOut, boolean hasIssue) {
        if (!connected) return ModeDisplay.WAITING_CONNECTION;
        if (pendingMode >= 0) return ModeDisplay.SWITCHING;
        if (hasIssue) return ModeDisplay.ISSUE;
        if (confirmedMode >= 0) return ModeDisplay.CURRENT;
        return readTimedOut ? ModeDisplay.READ_FAILED : ModeDisplay.READING;
    }

    boolean requestMode(int mode) {
        if (!validMode(mode) || pendingMode >= 0) {
            return false;
        }
        pendingMode = mode;
        return true;
    }

    void observeMode(int mode) {
        if (!validMode(mode)) {
            return;
        }
        confirmedMode = mode;
        cachedMode = mode;
        // An older state notification must not complete a different request.
        if (pendingMode == mode) {
            pendingMode = -1;
        }
    }

    int expireRequest() {
        int requested = pendingMode;
        pendingMode = -1;
        return requested;
    }

    void disconnect() {
        confirmedMode = -1;
        pendingMode = -1;
        battery = null;
    }

    void mergeBattery(EncoProtocol.Battery update) {
        if (update == null) {
            return;
        }
        if (battery == null) {
            battery = new EncoProtocol.Battery();
        }
        if (update.left >= 0) {
            battery.left = update.left;
            battery.leftCharging = update.leftCharging;
        }
        if (update.right >= 0) {
            battery.right = update.right;
            battery.rightCharging = update.rightCharging;
        }
        if (update.caseBattery >= 0) {
            battery.caseBattery = update.caseBattery;
            battery.caseCharging = update.caseCharging;
        }
        battery.count = (battery.left >= 0 ? 1 : 0)
                + (battery.right >= 0 ? 1 : 0) + (battery.caseBattery >= 0 ? 1 : 0);
    }

    static boolean validMode(int mode) {
        return mode == EncoProtocol.MODE_OFF || mode == EncoProtocol.MODE_TRANSPARENCY
                || mode == EncoProtocol.MODE_ANC || mode == EncoProtocol.MODE_ADAPTIVE;
    }
}
