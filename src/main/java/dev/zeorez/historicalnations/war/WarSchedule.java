package dev.zeorez.historicalnations.war;

import dev.zeorez.historicalnations.TerritoryConfig;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Evaluates weekly war windows (default Saturday & Sunday 18:00-23:00 in Europe/Istanbul)
 * with optional runtime admin override for live operations and testing.
 */
public final class WarSchedule {
    public enum OverrideMode { AUTO, FORCE_OPEN, FORCE_CLOSED }

    private static volatile OverrideMode overrideMode = OverrideMode.AUTO;

    private WarSchedule() {}

    public record TimeRange(int startMinuteOfDay, int endMinuteOfDay) {
        public boolean contains(int minuteOfDay) {
            if (startMinuteOfDay <= endMinuteOfDay) {
                return minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay;
            }
            // Overnight range such as 22:00-02:00
            return minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay;
        }
    }

    public static OverrideMode overrideMode() {
        return overrideMode;
    }

    public static void setOverrideMode(OverrideMode mode) {
        overrideMode = mode == null ? OverrideMode.AUTO : mode;
    }

    public static ZoneId configuredZone() {
        String raw = TerritoryConfig.WAR_TIMEZONE.get();
        if (raw != null && !raw.isBlank()) {
            try {
                return ZoneId.of(raw.strip());
            } catch (Exception ignored) {
            }
        }
        return ZoneId.of("Europe/Istanbul");
    }

    public static boolean isWarWindowOpen() {
        return isWarWindowOpen(Instant.now());
    }

    public static boolean isWarWindowOpen(Instant instant) {
        OverrideMode mode = overrideMode;
        if (mode == OverrideMode.FORCE_OPEN) return true;
        if (mode == OverrideMode.FORCE_CLOSED) return false;
        if (!TerritoryConfig.WAR_SCHEDULE_ENABLED.get()) return true;

        ZonedDateTime zdt = instant.atZone(configuredZone());
        List<TimeRange> today = parseRanges(specForDay(zdt.getDayOfWeek()));
        if (today.isEmpty()) return false;

        LocalTime lt = zdt.toLocalTime();
        int minuteOfDay = lt.getHour() * 60 + lt.getMinute();
        for (TimeRange r : today) {
            if (r.contains(minuteOfDay)) return true;
        }
        return false;
    }

    public static String describeStatus(Instant now) {
        ZoneId zone = configuredZone();
        ZonedDateTime zdt = now.atZone(zone);
        boolean open = isWarWindowOpen(now);
        String clock = String.format(Locale.ROOT, "%s %02d:%02d (%s)",
                zdt.getDayOfWeek().name().substring(0, 3), zdt.getHour(), zdt.getMinute(), zone.getId());
        if (overrideMode != OverrideMode.AUTO) {
            return (open ? "OPEN [ADMIN]" : "CLOSED [ADMIN]") + " • " + clock;
        }
        return (open ? "OPEN" : "CLOSED") + " • " + clock;
    }

    public static String specForDay(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> TerritoryConfig.WAR_MONDAY.get();
            case TUESDAY -> TerritoryConfig.WAR_TUESDAY.get();
            case WEDNESDAY -> TerritoryConfig.WAR_WEDNESDAY.get();
            case THURSDAY -> TerritoryConfig.WAR_THURSDAY.get();
            case FRIDAY -> TerritoryConfig.WAR_FRIDAY.get();
            case SATURDAY -> TerritoryConfig.WAR_SATURDAY.get();
            case SUNDAY -> TerritoryConfig.WAR_SUNDAY.get();
        };
    }

    public static List<TimeRange> parseRanges(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        List<TimeRange> out = new ArrayList<>(2);
        for (String part : spec.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) continue;
            int dash = trimmed.indexOf('-');
            if (dash <= 0 || dash >= trimmed.length() - 1) continue;
            int start = parseMinute(trimmed.substring(0, dash).strip());
            int end = parseMinute(trimmed.substring(dash + 1).strip());
            if (start >= 0 && end >= 0 && start != end) {
                out.add(new TimeRange(start, end));
            }
        }
        return out;
    }

    private static int parseMinute(String hhmm) {
        if ("24:00".equals(hhmm)) return 24 * 60;
        int colon = hhmm.indexOf(':');
        if (colon <= 0 || colon >= hhmm.length() - 1) return -1;
        try {
            int h = Integer.parseInt(hhmm.substring(0, colon));
            int m = Integer.parseInt(hhmm.substring(colon + 1));
            if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
            return h * 60 + m;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
