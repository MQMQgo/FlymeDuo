/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 *
 * This file is part of HyperDuo, released under the GNU General Public
 * License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package android.view;

/** Minimal desktop stand-in for the parts of {@code android.view.View} used by {@code TrioRenderer}. */
public class View {

    private int width;
    private int height;
    private final Resources resources = new Resources();

    public int getWidth() { return width; }

    public int getHeight() { return height; }

    public Resources getResources() { return resources; }

    public void setSize(int w, int h) {
        width = w;
        height = h;
    }

    public static final class Resources {
        private final DisplayMetrics metrics = new DisplayMetrics();

        public DisplayMetrics getDisplayMetrics() { return metrics; }
    }

    public static final class DisplayMetrics {
        public float density = 1f;
    }
}
