/*
 * Copyright (C) 2026 FlymeDuo contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.yixing233.hyperduo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;

import java.awt.geom.Point2D;
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
        settings.trioStyle = Prefs.STYLE_RING;
        settings.ringZones = Prefs.decodeLayout("7,4,3|5|8");
        settings.showSim1 = settings.showSim2 = settings.showBolt = settings.showValue = true;
        settings.mobileTypeMode = Prefs.MOBILE_TYPE_IN_RING;
        final double centre = ringCentre(settings).getY();
        final RecordingCanvas split = render(settings, false, -1, SLOT_LEVELS);
        checkDots(split, 8, 5);
        check(split.dotPositions.subList(0, 4).stream().allMatch(dot -> dot.getY() < centre)
                && split.dotPositions.subList(4, 8).stream().allMatch(dot -> dot.getY() > centre),
                "separate positions put one row on each side of the ring");

        final TrioSettings stacked = TrioZones.withPiece(settings, Prefs.ZONE_BOTTOM,
                TrioZones.Part.SIM_1);
        final TrioSettings restored = TrioSettings.fromBundle(stacked.toBundle());
        final TrioAppearance.Ring ring = TrioAppearance.of(restored).ring(72, false, -1, "5G", 2);
        check(ring.drawsPart(Prefs.ZONE_BOTTOM, Prefs.PART_SIM_1)
                && ring.drawsPart(Prefs.ZONE_BOTTOM, Prefs.PART_SIM_2)
                && ring.drawsPart(0, Prefs.PART_VALUE) && ring.gapUsed && ring.bottomOpen(),
                "both SIMs share the bottom while the top keeps its battery content");
        final RecordingCanvas bottom = render(restored, false, -1, SLOT_LEVELS);
        checkDots(bottom, 8, 5);
        check(bottom.dotPositions.stream().allMatch(dot -> dot.getY() > centre),
                "both rows are below the ring centre");
        check(bottom.text.containsAll(List.of("72", "5G")),
                "the two rows fit alongside the battery number and network type");
        assertConcentric(bottom, ringCentre(restored));
        final RecordingCanvas charging = render(restored, true, -1, SLOT_LEVELS);
        checkDots(charging, 8, 5);
        check(charging.paths == 1 && !charging.text.contains("72"),
                "the charge bolt can take the upper opening while both bottom rows draw");

        restored.dualStackMatrix = true;
        final RecordingCanvas matrix = render(restored, false, -1, SLOT_LEVELS);
        checkDots(matrix, 8, 5);
        check(matrix.dotPositions.stream().allMatch(dot -> dot.getY() > centre)
                && matrix.text.contains("72"), "the optional matrix also stays below the ring centre");

        final TrioSettings topPair = TrioZones.withPiece(settings, 0, TrioZones.Part.SIM_2);
        final RecordingCanvas top = render(topPair, false, -1, SLOT_LEVELS);
        checkDots(top, 8, 5);
        check(top.dotPositions.stream().allMatch(dot -> dot.getY() < centre),
                "two cards assigned to the top can share the upper opening");

        checkDots(render(restored, false, -1, new int[] { -1, 1 }), 4, 1);
        checkDots(render(restored, false, -1, new int[] { 4, -1 }), 4, 4);
        restored.showSim1 = false;
        assertDots(restored, 4, 1);
        restored.showSim1 = true;
        restored.showSim2 = false;
        assertDots(restored, 4, 4);
        restored.showSim1 = false;
        assertDots(restored, 0, 0);

        settings.ringZones = Prefs.decodeLayout("3||1,7,8");
        settings.showSim1 = settings.showSim2 = settings.showWifi = true;
        final TrioAppearance.Ring wifi = TrioAppearance.of(settings).ring(72, false, 3, "5G", 2);
        check(wifi.stackedSignalZone == -1 && wifi.parts[Prefs.ZONE_BOTTOM] == Prefs.PART_WIFI,
                "higher-priority Wi-Fi can occupy the shared opening");
        stacked.signalMode = Prefs.SIGNAL_OUT_RING;
        assertDots(stacked, 0, 0);
    }

    private static Point2D ringCentre(TrioSettings settings) {
        final float[] transform = new float[3];
        check(TrioRenderer.designTransform(SIZE, SIZE, TrioAppearance.of(settings), transform),
                "the ring fits the preview");
        return new Point2D.Double(TrioGeometry.B_CX * transform[0] + transform[1],
                TrioGeometry.B_CY * transform[0] + transform[2]);
    }

    private static void assertConcentric(RecordingCanvas canvas, Point2D centre) {
        final double outer = canvas.dotPositions.get(0).distance(centre);
        final double inner = canvas.dotPositions.get(4).distance(centre);
        check(outer > inner, "the second row follows the inner arc");
        for (int i = 0; i < canvas.dotPositions.size(); i++) {
            final double radius = i < 4 ? outer : inner;
            check(Math.abs(canvas.dotPositions.get(i).distance(centre) - radius) < 0.001,
                    "each row has a constant radius around the ring centre");
        }
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
        checkDots(render(settings, false, -1, SLOT_LEVELS), total, active);
    }

    private static RecordingCanvas render(TrioSettings settings, boolean charging, int wifiLevel,
                                           int[] slots) {
        final int ink = 0xFF514321;
        final RecordingCanvas canvas = new RecordingCanvas(ink);
        TrioRenderer.drawInto(canvas, SIZE, SIZE, 72, charging, false, false, false,
                wifiLevel, 2, slots, "5G", ink, settings, false);
        return canvas;
    }

    private static void checkDots(RecordingCanvas canvas, int total, int active) {
        final int count = canvas.dotPositions.size();
        check(count == total && canvas.activeDots == active,
                "expected " + total + " dots (" + active + " lit), got "
                        + count + " (" + canvas.activeDots + " lit)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingCanvas extends Canvas {
        final List<String> text = new ArrayList<>();
        final List<Point2D> dotPositions = new ArrayList<>();
        final int ink;
        int paths;
        int activeDots;

        RecordingCanvas(int ink) {
            super(new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB).createGraphics());
            this.ink = ink;
        }

        @Override
        public void drawCircle(float x, float y, float radius, Paint paint) {
            dotPositions.add(graphics().getTransform().transform(new Point2D.Double(x, y), null));
            if (paint.getColor() == ink) {
                activeDots++;
            }
        }

        @Override
        public void drawPath(Path path, Paint paint) {
            paths++;
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            this.text.add(text);
        }
    }
}
