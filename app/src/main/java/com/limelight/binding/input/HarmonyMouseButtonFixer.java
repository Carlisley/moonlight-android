package com.limelight.binding.input;

import com.limelight.nvstream.input.MouseButtonPacket;

/**
 * HarmonyOS 4.x and 5.0 map a mouse wheel click to Back. Some of those builds
 * also report a side button as a left click. This helper decides which of
 * those events should become a middle, X1, or X2 click.
 *
 * <p>Keyboard Back and the navigation bar / gesture Back are left alone. A
 * synthetic Back is only claimed when the event itself comes from a mouse, or
 * when it arrives together with a middle, side, or otherwise unmapped mouse
 * button. The class does not reference Android types so the decisions can be
 * unit tested on the host JVM.
 */
public final class HarmonyMouseButtonFixer {
    public static final long CORRELATION_WINDOW_MS = 200L;
    public static final long SPURIOUS_LEFT_WINDOW_MS = 120L;
    public static final long HIDE_TIMEOUT_MS = 3000L;

    public static final int BUTTON_PRIMARY = 1 << 0;
    public static final int BUTTON_SECONDARY = 1 << 1;
    public static final int BUTTON_TERTIARY = 1 << 2;
    public static final int BUTTON_BACK = 1 << 3;
    public static final int BUTTON_FORWARD = 1 << 4;

    public static final int KEYCODE_BACK = 4;
    public static final int KEYCODE_FORWARD = 125;
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;

    public static final int MOTION_BUTTON_PRESS = 11;
    public static final int MOTION_BUTTON_RELEASE = 12;

    public static final int SOURCE_KEYBOARD = 0x00000101;
    public static final int SOURCE_MOUSE = 0x00002002;
    public static final int SOURCE_MOUSE_RELATIVE = 0x00020004;

    public static final int FLAG_VIRTUAL_HARD_KEY = 0x40;
    public static final int KEYBOARD_TYPE_ALPHABETIC = 2;

    public static final class KeyResult {
        public final boolean consume;
        /** {@link MouseButtonPacket} value, or 0 when the key only needs to be eaten. */
        public final byte button;
        public final boolean down;
        public final boolean releaseSpuriousLeft;

        private KeyResult(boolean consume, byte button, boolean down, boolean releaseSpuriousLeft) {
            this.consume = consume;
            this.button = button;
            this.down = down;
            this.releaseSpuriousLeft = releaseSpuriousLeft;
        }

        static final KeyResult IGNORE = new KeyResult(false, (byte) 0, false, false);

        static KeyResult consumeOnly() {
            return new KeyResult(true, (byte) 0, false, false);
        }
    }

    public enum BackAction {
        IGNORE,
        /** The mouse button is already down; do not open the menu. */
        SWALLOW,
        /** No button edge was sent yet. Deliver a complete click. */
        CLICK
    }

    public static final class BackResult {
        public final BackAction action;
        public final byte button;
        public final boolean releaseSpuriousLeft;

        private BackResult(BackAction action, byte button, boolean releaseSpuriousLeft) {
            this.action = action;
            this.button = button;
            this.releaseSpuriousLeft = releaseSpuriousLeft;
        }

        static final BackResult IGNORE = new BackResult(BackAction.IGNORE, (byte) 0, false);
    }

    public static final class MotionResult {
        public final int buttonState;
        public final int changedButtons;

        MotionResult(int buttonState, int changedButtons) {
            this.buttonState = buttonState;
            this.changedButtons = changedButtons;
        }
    }

    private boolean middleDown;
    private boolean x1Down;
    private boolean x2Down;
    private byte keyLatchedButton;
    private boolean suspiciousPrimary;
    private long suspiciousPrimaryAt;
    private boolean suppressPrimary;
    private long suppressPrimaryUntil;
    private int hideUntilClear;
    private long hideUntil;
    private byte armedButton;
    private long armedAt;
    private boolean armed;

    private long seenEventTime = Long.MIN_VALUE;
    private int seenAction = Integer.MIN_VALUE;
    private int seenKeyCode = Integer.MIN_VALUE;
    private int seenRepeat = Integer.MIN_VALUE;
    private int seenDeviceId = Integer.MIN_VALUE;
    private KeyResult seenResult = KeyResult.IGNORE;

    private int lastAction = -1;
    private int lastRawButtons;
    private int lastAdjustedButtons;
    private int lastActionButton;
    private long lastMotionAt;

    public MotionResult adjustMotion(long eventTimeMs, int actionMasked, int actionButton,
                                     int rawButtonState, int lastButtonState) {
        int buttonState = rawButtonState;
        if (actionMasked == MOTION_BUTTON_PRESS && isSideOrMiddle(actionButton)) {
            buttonState |= actionButton;
        } else if (actionMasked == MOTION_BUTTON_RELEASE && isSideOrMiddle(actionButton)) {
            buttonState &= ~actionButton;
        }

        int previewChanged = buttonState ^ lastButtonState;
        boolean sideOrMiddlePress = (isSideOrMiddle(actionButton) && actionMasked == MOTION_BUTTON_PRESS)
                || newlyDown(previewChanged, buttonState, BUTTON_TERTIARY | BUTTON_BACK | BUTTON_FORWARD);
        // A side or middle press that also raises PRIMARY is the "side button
        // arrives as left click" report. Drop only a newly pressed primary.
        if (sideOrMiddlePress
                && newlyDown(previewChanged, buttonState, BUTTON_PRIMARY)
                && actionButton != BUTTON_PRIMARY) {
            buttonState &= ~BUTTON_PRIMARY;
        }

        if (suppressPrimary) {
            if ((rawButtonState & BUTTON_PRIMARY) == 0 || eventTimeMs > suppressPrimaryUntil) {
                suppressPrimary = false;
            } else {
                buttonState = (buttonState & ~BUTTON_PRIMARY) | (lastButtonState & BUTTON_PRIMARY);
            }
        }

        if (hideUntilClear != 0) {
            if (eventTimeMs > hideUntil) {
                hideUntilClear = 0;
            } else {
                hideUntilClear &= rawButtonState;
                buttonState = (buttonState & ~hideUntilClear) | (lastButtonState & hideUntilClear);
            }
        }

        int changed = buttonState ^ lastButtonState;
        if ((changed & BUTTON_TERTIARY) != 0) {
            middleDown = (buttonState & BUTTON_TERTIARY) != 0;
        }
        if ((changed & BUTTON_BACK) != 0) {
            x1Down = (buttonState & BUTTON_BACK) != 0;
        }
        if ((changed & BUTTON_FORWARD) != 0) {
            x2Down = (buttonState & BUTTON_FORWARD) != 0;
        }

        boolean plainPrimaryDown = newlyDown(changed, buttonState, BUTTON_PRIMARY)
                && (buttonState & (BUTTON_TERTIARY | BUTTON_BACK | BUTTON_FORWARD)) == 0
                && !isSideOrMiddle(actionButton);
        if (plainPrimaryDown) {
            suspiciousPrimary = true;
            suspiciousPrimaryAt = eventTimeMs;
        }
        if ((changed & BUTTON_PRIMARY) != 0 && (buttonState & BUTTON_PRIMARY) == 0) {
            suspiciousPrimary = false;
        }

        byte arm = 0;
        if (newlyDown(changed, buttonState, BUTTON_TERTIARY)
                || (actionButton == BUTTON_TERTIARY && actionMasked == MOTION_BUTTON_PRESS)) {
            arm = MouseButtonPacket.BUTTON_MIDDLE;
        } else if (newlyDown(changed, buttonState, BUTTON_BACK)
                || (actionButton == BUTTON_BACK && actionMasked == MOTION_BUTTON_PRESS)) {
            arm = MouseButtonPacket.BUTTON_X1;
        } else if (newlyDown(changed, buttonState, BUTTON_FORWARD)
                || (actionButton == BUTTON_FORWARD && actionMasked == MOTION_BUTTON_PRESS)) {
            arm = MouseButtonPacket.BUTTON_X2;
        } else if ((actionMasked == MOTION_BUTTON_PRESS || actionMasked == MOTION_BUTTON_RELEASE)
                && actionButton == 0
                && (changed & (BUTTON_PRIMARY | BUTTON_SECONDARY | BUTTON_TERTIARY | BUTTON_BACK | BUTTON_FORWARD)) == 0) {
            // Button edge with no button bit. HarmonyOS sometimes does this
            // for the wheel click before (or instead of) delivering Back.
            arm = MouseButtonPacket.BUTTON_MIDDLE;
        }
        if (arm != 0) {
            armed = true;
            armedButton = arm;
            armedAt = eventTimeMs;
        }

        lastAction = actionMasked;
        lastRawButtons = rawButtonState;
        lastAdjustedButtons = buttonState;
        lastActionButton = actionButton;
        lastMotionAt = eventTimeMs;
        return new MotionResult(buttonState, changed);
    }

    public KeyResult onKey(long eventTimeMs, int action, int keyCode, int repeatCount,
                           int source, int deviceSources, int keyboardType, int deviceId, int flags) {
        if (eventTimeMs == seenEventTime && action == seenAction && keyCode == seenKeyCode
                && repeatCount == seenRepeat && deviceId == seenDeviceId) {
            // The stream view and the activity can both observe one event.
            return seenResult.consume ? KeyResult.consumeOnly() : seenResult;
        }
        KeyResult result = classifyKey(eventTimeMs, action, keyCode, repeatCount,
                source, deviceSources, keyboardType, deviceId, flags);
        seenEventTime = eventTimeMs;
        seenAction = action;
        seenKeyCode = keyCode;
        seenRepeat = repeatCount;
        seenDeviceId = deviceId;
        seenResult = result;
        return result;
    }

    public BackResult onBackInvoked(long eventTimeMs) {
        if (!armed || eventTimeMs - armedAt > CORRELATION_WINDOW_MS) {
            return BackResult.IGNORE;
        }
        byte button = armedButton == 0 ? MouseButtonPacket.BUTTON_MIDDLE : armedButton;
        boolean releaseLeft = shouldReleaseSpuriousLeft(eventTimeMs);
        if (releaseLeft) {
            suspiciousPrimary = false;
        }
        armed = false;
        beginPrimarySuppression(eventTimeMs);
        if (isHostDown(button)) {
            return new BackResult(BackAction.SWALLOW, button, releaseLeft);
        }
        // The system invoked Back without a matching key up, so this is a click.
        setHostDown(button, true);
        setHostDown(button, false);
        ownButton(button, eventTimeMs);
        return new BackResult(BackAction.CLICK, button, releaseLeft);
    }

    public int lastAction() {
        return lastAction;
    }

    public int lastRawButtons() {
        return lastRawButtons;
    }

    public int lastAdjustedButtons() {
        return lastAdjustedButtons;
    }

    public int lastActionButton() {
        return lastActionButton;
    }

    public long lastMotionAt() {
        return lastMotionAt;
    }

    private KeyResult classifyKey(long eventTimeMs, int action, int keyCode, int repeatCount,
                                  int source, int deviceSources, int keyboardType, int deviceId, int flags) {
        if (action != ACTION_DOWN && action != ACTION_UP) {
            return KeyResult.IGNORE;
        }
        if (keyCode != KEYCODE_BACK && keyCode != KEYCODE_FORWARD) {
            return KeyResult.IGNORE;
        }
        // Navigation-bar Back and a real keyboard Back always pass through.
        // Correlation must not steal those, or a click followed by Back opens nothing.
        if ((flags & FLAG_VIRTUAL_HARD_KEY) != 0 && !sourceHasMouse(source)) {
            return KeyResult.IGNORE;
        }
        if (keyboardType == KEYBOARD_TYPE_ALPHABETIC && !sourceHasMouse(source)) {
            return KeyResult.IGNORE;
        }

        boolean correlated = armed && eventTimeMs - armedAt <= CORRELATION_WINDOW_MS;
        boolean mouseOrigin = sourceHasMouse(source) || isMouseOnlyDevice(deviceSources, keyboardType, deviceId);
        if (!mouseOrigin && !correlated) {
            return KeyResult.IGNORE;
        }

        if (action == ACTION_DOWN) {
            if (repeatCount > 0 || keyLatchedButton != 0) {
                return KeyResult.consumeOnly();
            }
            byte desired = chooseButton(keyCode, correlated);
            boolean releaseLeft = shouldReleaseSpuriousLeft(eventTimeMs);
            if (releaseLeft) {
                suspiciousPrimary = false;
            }
            beginPrimarySuppression(eventTimeMs);
            if (isHostDown(desired)) {
                return new KeyResult(true, (byte) 0, false, releaseLeft);
            }
            keyLatchedButton = desired;
            setHostDown(desired, true);
            ownButton(desired, eventTimeMs);
            return new KeyResult(true, desired, true, releaseLeft);
        }

        // The press that this key rewrote has ended. If the mouse is not still
        // reporting left, allow the next real left click through immediately.
        if ((lastRawButtons & BUTTON_PRIMARY) == 0) {
            suppressPrimary = false;
        }
        if (keyLatchedButton == 0) {
            return KeyResult.consumeOnly();
        }
        byte button = keyLatchedButton;
        keyLatchedButton = 0;
        setHostDown(button, false);
        return new KeyResult(true, button, false, false);
    }

    private byte chooseButton(int keyCode, boolean correlated) {
        if (keyCode == KEYCODE_FORWARD) {
            return MouseButtonPacket.BUTTON_X2;
        }
        if (correlated && armedButton == MouseButtonPacket.BUTTON_X1 && !middleDown) {
            return MouseButtonPacket.BUTTON_X1;
        }
        if (correlated && armedButton == MouseButtonPacket.BUTTON_X2) {
            return MouseButtonPacket.BUTTON_X2;
        }
        return MouseButtonPacket.BUTTON_MIDDLE;
    }

    private boolean shouldReleaseSpuriousLeft(long now) {
        return suspiciousPrimary
                && now - suspiciousPrimaryAt <= SPURIOUS_LEFT_WINDOW_MS
                && !middleDown && !x1Down && !x2Down;
    }

    private void beginPrimarySuppression(long now) {
        suppressPrimary = true;
        suppressPrimaryUntil = now + HIDE_TIMEOUT_MS;
    }

    private void ownButton(byte button, long now) {
        int bit = bitFor(button);
        if (bit != 0) {
            hideUntilClear |= bit;
            hideUntil = now + HIDE_TIMEOUT_MS;
        }
    }

    private void setHostDown(byte button, boolean down) {
        if (button == MouseButtonPacket.BUTTON_MIDDLE) {
            middleDown = down;
        } else if (button == MouseButtonPacket.BUTTON_X1) {
            x1Down = down;
        } else if (button == MouseButtonPacket.BUTTON_X2) {
            x2Down = down;
        }
    }

    private boolean isHostDown(byte button) {
        if (button == MouseButtonPacket.BUTTON_MIDDLE) {
            return middleDown;
        }
        if (button == MouseButtonPacket.BUTTON_X1) {
            return x1Down;
        }
        if (button == MouseButtonPacket.BUTTON_X2) {
            return x2Down;
        }
        return false;
    }

    public static int bitFor(byte button) {
        if (button == MouseButtonPacket.BUTTON_MIDDLE) {
            return BUTTON_TERTIARY;
        }
        if (button == MouseButtonPacket.BUTTON_X1) {
            return BUTTON_BACK;
        }
        if (button == MouseButtonPacket.BUTTON_X2) {
            return BUTTON_FORWARD;
        }
        return 0;
    }

    public static boolean sourceHasMouse(int source) {
        return (source & SOURCE_MOUSE) == SOURCE_MOUSE
                || (source & SOURCE_MOUSE_RELATIVE) == SOURCE_MOUSE_RELATIVE;
    }

    private static boolean isMouseOnlyDevice(int deviceSources, int keyboardType, int deviceId) {
        return deviceId >= 0
                && keyboardType != KEYBOARD_TYPE_ALPHABETIC
                && sourceHasMouse(deviceSources);
    }

    private static boolean isSideOrMiddle(int button) {
        return button == BUTTON_TERTIARY || button == BUTTON_BACK || button == BUTTON_FORWARD;
    }

    private static boolean newlyDown(int changed, int state, int mask) {
        return (changed & mask) != 0 && (state & mask) != 0;
    }
}
