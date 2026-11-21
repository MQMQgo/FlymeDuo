/*
 * Copyright (C) 2026 FlymeDuo contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Exercises the dual-SIM drawing and the settings sent to SystemUI. */
public final class DualSimTest {
    private static final int SIZE = 320;
    private static final int[] SLOT_LEVELS = { 4, 1 };

    public static void main(String[] args) {
        ringRows();
        capsuleRows();
        warningTakesCentre();
        settingsRoundTrip();
        System.out.println("PASS: dual-SIM rendering and settings");
    }

    private static void ringRows() {
        final TrioSettings settings = TrioSettings.defaults();
        settings.ringZones = Prefs.decodeLayout("7||8");
        settings.dualInsideRing = true;
        for (boolean matrix : new boolean[] { false, true }) {
            settings.dualStackMatrix = matrix;
            for (int side : new int[] { Prefs.DUAL_STACK_TOP, Prefs.DUAL_STACK_BOTTOM }) {
                settings.dualStackPos = side;
                settings.showSim1 = true;
                settings.showSim2 = false;
                assertDots(settings, 4, 4);
                settings.showSim1 = false;
                settings.showSim2 = true;
                assertDots(settings, 4, 1);
                settings.showSim1 = true;
                assertDots(settings, 8, 5);
                final TrioAppearance.Ring ring = TrioAppearance.of(settings)
                        .ring(72, false, -1, "5G", 2);
                check(ring.stackedDual && ring.bottomOpen() == (side == Prefs.DUAL_STACK_BOTTOM)
                        && ring.gapUsed == (side == Prefs.DUAL_STACK_TOP),
                        "the pair uses one opening on the configured side");
                settings.showSim1 = false;
                settings.showSim2 = false;
                assertDots(settings, 0, 0);
            }
        }

        settings.showSim1 = settings.showSim2 = settings.showWifi = true;
        settings.ringZones = Prefs.decodeLayout("1,7||8");
        final TrioAppearance.Ring wifi = TrioAppearance.of(settings).ring(72, false, 3, "5G", 2);
        check(!wifi.stackedDual && wifi.parts[0] == Prefs.PART_WIFI,
                "a row behind higher-priority Wi-Fi does not reappear in a stack");
        settings.signalMode = Prefs.SIGNAL_OUT_RING;
        assertDots(settings, 0, 0);
    }

    private static void capsuleRows() {
        final TrioSettings settings = TrioSettings.defaults();
        settings.trioStyle = Prefs.STYLE_CAPSULE;
        settings.ringZones = Prefs.decodeLayout("8||7", Prefs.STYLE_CAPSULE);
        settings.showSim2 = true;
        assertDots(settings, 4, 1);
        settings.showSim1 = true;
        settings.showSim2 = false;
        assertDots(settings, 4, 4);
        settings.showSim1 = false;
        assertDots(settings, 0, 0);
        check(!TrioAppearance.of(settings).foldsMobile(),
                "disabling both placed rows hands the signal back to SystemUI");

        settings.showSim2 = true;
        settings.ringZones = Prefs.decodeLayout("|8||", Prefs.STYLE_CAPSULE);
        final TrioSettings restored = TrioSettings.fromBundle(settings.toBundle());
        check(restored.ringZones[Prefs.ZONE_CENTRE][0] == Prefs.PART_SIM_2,
                "the capsule can retain a signal row in the right cell");
        assertDots(restored, 4, 1);
    }

    private static void warningTakesCentre() {
        final TrioSettings settings = TrioSettings.defaults();
        settings.showWifi = settings.showValue = settings.typeHideOnWifi = true;
        settings.ringWarnNoService = settings.chargingShowsValue = true;
        settings.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
        settings.ringZones = Prefs.decodeLayout("5,1|3|");
        final TrioState state = new TrioState();
        state.charging = true;
        state.wifiPresent = true;
        state.mobileType = "5G";
        state.noServiceLabel = "No service";
        final RecordingCanvas outage = new RecordingCanvas(state.foreground());
        TrioRenderer.drawState(outage, SIZE, SIZE, state, settings);
        check(outage.text.equals(List.of("No service")), "the warning owns the ring centre");
        state.noServiceLabel = null;
        final RecordingCanvas recovered = new RecordingCanvas(state.foreground());
        TrioRenderer.drawState(recovered, SIZE, SIZE, state, settings);
        check(recovered.text.contains("72"), "the regular centre returns after recovery");
    }

    private static void settingsRoundTrip() {
        final TrioSettings settings = TrioSettings.defaults();
        check(settings.ringTypeSim == Prefs.TYPE_SIM_DATA && !settings.ringWarnNoService,
                "existing installs default to the data SIM with the warning off");
        settings.ringTypeSim = Prefs.TYPE_SIM_2;
        settings.ringWarnNoService = true;
        final TrioSettings fromBroadcast = TrioSettings.fromBundle(settings.toBundle());
        check(fromBroadcast.ringTypeSim == Prefs.TYPE_SIM_2 && fromBroadcast.ringWarnNoService,
                "both settings survive a broadcast");
        final TrioSettings incremental = TrioSettings.defaults();
        check(incremental.applyKeyFrom(settings, Prefs.KEY_RING_TYPE_SIM)
                && incremental.applyKeyFrom(settings, Prefs.KEY_RING_WARN_NO_SERVICE)
                && incremental.equals(fromBroadcast), "live preference changes reach SystemUI");
        final Bundle invalid = new Bundle();
        invalid.putInt(Prefs.KEY_RING_TYPE_SIM, 99);
        check(TrioSettings.fromBundle(invalid).ringTypeSim == Prefs.TYPE_SIM_2,
                "the SIM choice stays within the supported range");
    }

    private static void assertDots(TrioSettings settings, int total, int active) {
        final int ink = 0xFF514321;
        final RecordingCanvas canvas = new RecordingCanvas(ink);
        TrioRenderer.drawInto(canvas, SIZE, SIZE, 72, false, false, false, false,
                -1, 2, SLOT_LEVELS, "", ink, settings, false);
        check(canvas.dots == total && canvas.activeDots == active,
                "expected " + total + " dots (" + active + " lit), got "
                        + canvas.dots + " (" + canvas.activeDots + " lit)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingCanvas extends Canvas {
        final List<String> text = new ArrayList<>();
        final int ink;
        int dots;
        int activeDots;

        RecordingCanvas(int ink) {
            super(new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB).createGraphics());
            this.ink = ink;
        }

        @Override
        public void drawCircle(float x, float y, float radius, Paint paint) {
            dots++;
            if (paint.getColor() == ink) {
                activeDots++;
            }
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            this.text.add(text);
        }
    }
}
