package com.limelight.binding.input;

import com.limelight.nvstream.input.MouseButtonPacket;

import org.junit.Test;

import static com.limelight.binding.input.HarmonyMouseButtonFixer.ACTION_DOWN;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.ACTION_UP;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.BUTTON_BACK;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.BUTTON_FORWARD;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.BUTTON_PRIMARY;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.BUTTON_TERTIARY;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.FLAG_VIRTUAL_HARD_KEY;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.KEYBOARD_TYPE_ALPHABETIC;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.KEYCODE_BACK;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.KEYCODE_FORWARD;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.MOTION_BUTTON_PRESS;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.MOTION_BUTTON_RELEASE;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.SOURCE_KEYBOARD;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.SOURCE_MOUSE;
import static com.limelight.binding.input.HarmonyMouseButtonFixer.SOURCE_MOUSE_RELATIVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HarmonyMouseButtonFixerTest {
    private static final int MOUSE_DEVICE = SOURCE_MOUSE;
    private static final int KEYBOARD_NONE = 0;

    @Test public void mouseSourceBackBecomesMiddleAndRepeatsAreEaten() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();

        HarmonyMouseButtonFixer.KeyResult down = key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        assertTrue(down.consume);
        assertEquals(MouseButtonPacket.BUTTON_MIDDLE, down.button);
        assertTrue(down.down);
        assertFalse(down.releaseSpuriousLeft);

        HarmonyMouseButtonFixer.KeyResult repeat = key(fixer, 1100, ACTION_DOWN, KEYCODE_BACK, 1,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        assertTrue(repeat.consume);
        assertEquals(0, repeat.button);

        HarmonyMouseButtonFixer.KeyResult up = key(fixer, 1200, ACTION_UP, KEYCODE_BACK, 0,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        assertTrue(up.consume);
        assertEquals(MouseButtonPacket.BUTTON_MIDDLE, up.button);
        assertFalse(up.down);
    }

    @Test public void relativeMouseSourceBackBecomesMiddle() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.KeyResult down = key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_MOUSE_RELATIVE, SOURCE_MOUSE_RELATIVE, KEYBOARD_NONE, 4, 0);
        assertEquals(MouseButtonPacket.BUTTON_MIDDLE, down.button);
        assertTrue(down.down);
    }

    @Test public void keyboardBackAndNavigationBackPassThrough() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.KeyResult keyboard = key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, SOURCE_KEYBOARD, KEYBOARD_TYPE_ALPHABETIC, 7, 0);
        assertFalse(keyboard.consume);

        HarmonyMouseButtonFixer.KeyResult nav = key(fixer, 1100, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, 0, KEYBOARD_NONE, -1, FLAG_VIRTUAL_HARD_KEY);
        assertFalse(nav.consume);

        HarmonyMouseButtonFixer.KeyResult gesture = key(fixer, 1200, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, 0, KEYBOARD_NONE, -1, 0);
        assertFalse(gesture.consume);
    }

    @Test public void keyboardBackStaysAKeyEvenAfterAMiddleClick() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        fixer.adjustMotion(1000, MOTION_BUTTON_PRESS, BUTTON_TERTIARY, BUTTON_TERTIARY, 0);
        HarmonyMouseButtonFixer.KeyResult keyboard = key(fixer, 1050, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, SOURCE_KEYBOARD, KEYBOARD_TYPE_ALPHABETIC, 7, 0);
        assertFalse(keyboard.consume);
    }

    @Test public void mouseDeviceWithKeyboardSourceBecomesMiddle() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.KeyResult down = key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, SOURCE_MOUSE, KEYBOARD_NONE, 6, 0);
        assertTrue(down.consume);
        assertEquals(MouseButtonPacket.BUTTON_MIDDLE, down.button);
        assertTrue(down.down);
    }

    @Test public void virtualBackAfterTertiaryIsSwallowedWithoutASecondDown() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.MotionResult motion = fixer.adjustMotion(
                1000, MOTION_BUTTON_PRESS, BUTTON_TERTIARY, BUTTON_TERTIARY, 0);
        assertEquals(BUTTON_TERTIARY, motion.changedButtons & BUTTON_TERTIARY);

        HarmonyMouseButtonFixer.KeyResult down = key(fixer, 1040, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, 0, KEYBOARD_NONE, -1, 0);
        assertTrue(down.consume);
        assertEquals(0, down.button);

        HarmonyMouseButtonFixer.BackResult back = fixer.onBackInvoked(1060);
        assertEquals(HarmonyMouseButtonFixer.BackAction.SWALLOW, back.action);
    }

    @Test public void virtualBackAfterAPlainLeftClickStillOpensTheMenu() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        fixer.adjustMotion(1000, MOTION_BUTTON_PRESS, BUTTON_PRIMARY, BUTTON_PRIMARY, 0);
        HarmonyMouseButtonFixer.KeyResult gesture = key(fixer, 1080, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, 0, KEYBOARD_NONE, -1, 0);
        assertFalse(gesture.consume);
        assertEquals(HarmonyMouseButtonFixer.BackAction.IGNORE, fixer.onBackInvoked(1100).action);
    }

    @Test public void sideButtonReportedAsPrimaryIsRewrittenToBack() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.MotionResult motion = fixer.adjustMotion(
                1000, MOTION_BUTTON_PRESS, BUTTON_BACK, BUTTON_PRIMARY | BUTTON_BACK, 0);
        assertEquals(BUTTON_BACK, motion.buttonState);
        assertEquals(0, motion.changedButtons & BUTTON_PRIMARY);
        assertEquals(BUTTON_BACK, motion.changedButtons & BUTTON_BACK);
    }

    @Test public void actionButtonBackWithOnlyPrimaryStateDoesNotClickLeft() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.MotionResult motion = fixer.adjustMotion(
                1000, MOTION_BUTTON_PRESS, BUTTON_BACK, BUTTON_PRIMARY, 0);
        assertEquals(BUTTON_BACK, motion.buttonState);
        assertEquals(0, motion.buttonState & BUTTON_PRIMARY);
    }

    @Test public void heldLeftButtonIsKeptWhenTheSideButtonIsAdded() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.MotionResult motion = fixer.adjustMotion(
                1000, MOTION_BUTTON_PRESS, BUTTON_BACK, BUTTON_PRIMARY | BUTTON_BACK, BUTTON_PRIMARY);
        assertEquals(BUTTON_PRIMARY | BUTTON_BACK, motion.buttonState);
        assertEquals(BUTTON_BACK, motion.changedButtons);
    }

    @Test public void primaryThenMouseBackReleasesTheFalseLeftClick() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        fixer.adjustMotion(1000, MOTION_BUTTON_PRESS, BUTTON_PRIMARY, BUTTON_PRIMARY, 0);
        HarmonyMouseButtonFixer.KeyResult down = key(fixer, 1030, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        assertTrue(down.releaseSpuriousLeft);
        assertEquals(MouseButtonPacket.BUTTON_MIDDLE, down.button);
        assertTrue(down.down);
    }

    @Test public void primaryThenNavigationBackDoesNotReleaseLeft() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        fixer.adjustMotion(1000, MOTION_BUTTON_PRESS, BUTTON_PRIMARY, BUTTON_PRIMARY, 0);
        HarmonyMouseButtonFixer.KeyResult nav = key(fixer, 1040, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, 0, KEYBOARD_NONE, -1, FLAG_VIRTUAL_HARD_KEY);
        assertFalse(nav.consume);
        assertFalse(nav.releaseSpuriousLeft);
    }

    @Test public void forwardKeyFromMouseBecomesX2() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.KeyResult down = key(fixer, 1000, ACTION_DOWN, KEYCODE_FORWARD, 0,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        assertEquals(MouseButtonPacket.BUTTON_X2, down.button);
        assertTrue(down.down);
        HarmonyMouseButtonFixer.KeyResult up = key(fixer, 1100, ACTION_UP, KEYCODE_FORWARD, 0,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        assertEquals(MouseButtonPacket.BUTTON_X2, up.button);
        assertFalse(up.down);
    }

    @Test public void backAfterSideButtonBecomesX1() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        fixer.adjustMotion(1000, MOTION_BUTTON_PRESS, BUTTON_BACK, BUTTON_BACK, 0);
        HarmonyMouseButtonFixer.KeyResult down = key(fixer, 1020, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_KEYBOARD, 0, KEYBOARD_NONE, -1, 0);
        assertTrue(down.consume);
        assertEquals(0, down.button);
    }

    @Test public void emptyButtonPressArmsMiddleClickWhenBackIsInvoked() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        fixer.adjustMotion(1000, MOTION_BUTTON_PRESS, 0, 0, 0);
        HarmonyMouseButtonFixer.BackResult back = fixer.onBackInvoked(1050);
        assertEquals(HarmonyMouseButtonFixer.BackAction.CLICK, back.action);
        assertEquals(MouseButtonPacket.BUTTON_MIDDLE, back.button);
    }

    @Test public void backInvocationWithoutAMouseButtonIsIgnored() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        assertEquals(HarmonyMouseButtonFixer.BackAction.IGNORE, fixer.onBackInvoked(1000).action);
    }

    @Test public void sameKeyEventIsNotAppliedTwice() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.KeyResult first = key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        HarmonyMouseButtonFixer.KeyResult second = key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0,
                SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        assertTrue(first.down);
        assertEquals(0, second.button);
        assertTrue(second.consume);
    }

    @Test public void keyDownThenMotionDoesNotEmitASecondMiddleEdge() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0, SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        int tracked = BUTTON_TERTIARY;
        HarmonyMouseButtonFixer.MotionResult motion = fixer.adjustMotion(
                1010, MOTION_BUTTON_PRESS, BUTTON_TERTIARY, BUTTON_TERTIARY, tracked);
        assertEquals(0, motion.changedButtons & BUTTON_TERTIARY);
    }

    @Test public void primaryFollowingAMouseBackIsSuppressedUntilItClears() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        key(fixer, 1000, ACTION_DOWN, KEYCODE_BACK, 0, SOURCE_MOUSE, MOUSE_DEVICE, KEYBOARD_NONE, 4, 0);
        HarmonyMouseButtonFixer.MotionResult pressed = fixer.adjustMotion(
                1010, MOTION_BUTTON_PRESS, BUTTON_PRIMARY, BUTTON_PRIMARY, 0);
        assertEquals(0, pressed.buttonState & BUTTON_PRIMARY);

        HarmonyMouseButtonFixer.MotionResult released = fixer.adjustMotion(
                1100, MOTION_BUTTON_RELEASE, 0, 0, 0);
        assertEquals(0, released.changedButtons & BUTTON_PRIMARY);

        HarmonyMouseButtonFixer.MotionResult realLeft = fixer.adjustMotion(
                1300, MOTION_BUTTON_PRESS, BUTTON_PRIMARY, BUTTON_PRIMARY, 0);
        assertEquals(BUTTON_PRIMARY, realLeft.buttonState & BUTTON_PRIMARY);
    }

    @Test public void forwardButtonBitIsPreserved() {
        HarmonyMouseButtonFixer fixer = new HarmonyMouseButtonFixer();
        HarmonyMouseButtonFixer.MotionResult motion = fixer.adjustMotion(
                1000, MOTION_BUTTON_PRESS, BUTTON_FORWARD, BUTTON_FORWARD, 0);
        assertEquals(BUTTON_FORWARD, motion.changedButtons);
    }

    private static HarmonyMouseButtonFixer.KeyResult key(HarmonyMouseButtonFixer fixer, long time, int action,
                                                         int keyCode, int repeat, int source, int deviceSources,
                                                         int keyboardType, int deviceId, int flags) {
        return fixer.onKey(time, action, keyCode, repeat, source, deviceSources, keyboardType, deviceId, flags);
    }
}
