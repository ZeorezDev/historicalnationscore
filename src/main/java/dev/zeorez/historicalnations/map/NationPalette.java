package dev.zeorez.historicalnations.map;

import dev.zeorez.historicalnations.core.RuleViolation;

import java.awt.Color;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;

/**
 * Curated 1930-1950 geopolitical strategic map palette.
 * Avoids neon colors and maximizes perceptual contrast between sovereign nations.
 */
public final class NationPalette {
    public static final int INDEPENDENT_COLOR = 0x8C8370;

    private static final int[] PALETTE = {
            0xB83A3A, // Balkan Crimson
            0x3E6B89, // Prussian Steel Blue
            0x557A46, // Continental Olive
            0xC4943D, // Imperial Ochre
            0x7A4B78, // Royal Plum
            0xB55E37, // Anatolian Terracotta
            0x3B7D7A, // Baltic Pine Teal
            0x9E3B5A, // Burgundian Carmine
            0x5A638C, // French Slate Azure
            0x7D7338, // Steppe Khaki
            0x8F4C38, // Iberian Sienna
            0x487899, // Adriatic Cerulean
            0x6B875E, // Alpine Sage
            0xAD7849, // Danubian Amber
            0x6E5485, // Carpathian Violet
            0x994242, // Soviet Deep Red
            0x3F695B, // Nordic Fjord Green
            0xB0825B, // Levantine Bronze
            0x4F5E73, // North Sea Iron
            0x8A6240, // Silesian Umber
            0x697D49, // Ardennes Moss
            0xA3586D, // Imperial Rose
            0x4C808A, // Aegean Slate
            0x9C7E44  // Savannah Gold
    };

    private NationPalette() {}

    public static int pickDistinct(Collection<Integer> existingColors, UUID seedId) {
        int start = Math.floorMod(seedId.hashCode(), PALETTE.length);
        if (existingColors.isEmpty()) {
            return PALETTE[start];
        }
        int bestColor = -1;
        double bestMinDist = -1.0;

        for (int i = 0; i < PALETTE.length; i++) {
            int candidate = PALETTE[(start + i) % PALETTE.length];
            double minDist = minColorDistanceSq(candidate, existingColors);
            if (minDist > bestMinDist) {
                bestMinDist = minDist;
                bestColor = candidate;
            }
        }
        if (bestMinDist >= 1800.0) {
            return bestColor;
        }
        // Fallback for servers with dozens/hundreds of nations: golden-angle muted HSV sweep.
        for (int k = 0; k < 72; k++) {
            float hue = (((seedId.hashCode() & 0xFFFF) / 65536.0f) + k * 0.6180339887f) % 1.0f;
            float sat = 0.46f + (k % 3) * 0.08f;
            float val = 0.62f + ((k / 3) % 3) * 0.07f;
            int candidate = Color.HSBtoRGB(hue, sat, val) & 0xFFFFFF;
            double minDist = minColorDistanceSq(candidate, existingColors);
            if (minDist > bestMinDist) {
                bestMinDist = minDist;
                bestColor = candidate;
            }
        }
        return bestColor;
    }

    public static int parseHex(String raw) {
        String clean = raw == null ? "" : raw.strip();
        if (clean.startsWith("#")) clean = clean.substring(1);
        if (!clean.matches("[0-9a-fA-F]{6}")) {
            throw new RuleViolation("invalid_color");
        }
        return Integer.parseInt(clean.toUpperCase(Locale.ROOT), 16) & 0xFFFFFF;
    }

    public static String formatHex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    private static double minColorDistanceSq(int candidate, Collection<Integer> existing) {
        int r1 = (candidate >> 16) & 0xFF;
        int g1 = (candidate >> 8) & 0xFF;
        int b1 = candidate & 0xFF;
        double min = Double.MAX_VALUE;
        for (int other : existing) {
            int r2 = (other >> 16) & 0xFF;
            int g2 = (other >> 8) & 0xFF;
            int b2 = other & 0xFF;
            double dr = r1 - r2;
            double dg = g1 - g2;
            double db = b1 - b2;
            // Perceptual redmean-weighted RGB distance squared
            double rMean = (r1 + r2) * 0.5;
            double dist = (2.0 + rMean / 256.0) * dr * dr + 4.0 * dg * dg + (2.0 + (255.0 - rMean) / 256.0) * db * db;
            if (dist < min) min = dist;
        }
        return min;
    }
}
