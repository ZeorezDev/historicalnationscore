package dev.zeorez.historicalnations.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.UUID;

/** One immutable review. The containing town's chronological list is its audit trail. */
public record CityEvaluation(UUID reviewer, long date, Draft draft) {
    public enum Category {
        ARCHITECTURE(30), INFRASTRUCTURE(20), RESIDENTIAL(20), PUBLIC_BUILDINGS(20), COMPLETION(10);
        public final int max;
        Category(int max) { this.max = max; }
    }
    public static int suggested(int total) {
        if (total < 0 || total > 100) throw new RuleViolation("city_invalid");
        return Math.max(1, (total + 19) / 20);
    }
    public static String stars(int rating) {
        return "★".repeat(rating) + "☆".repeat(5 - rating);
    }

    public record Draft(int architecture, int infrastructure, int residential, int publicBuildings, int completion,
                        int level, int rating, String reason, String notes, String summary) {
        public int score(Category category) {
            return switch (category) {
                case ARCHITECTURE -> architecture;
                case INFRASTRUCTURE -> infrastructure;
                case RESIDENTIAL -> residential;
                case PUBLIC_BUILDINGS -> publicBuildings;
                case COMPLETION -> completion;
            };
        }
        public int total() { return architecture + infrastructure + residential + publicBuildings + completion; }
        public void validate() {
            for (var c : Category.values()) {
                if (score(c) < 0 || score(c) > c.max) throw new RuleViolation("city_invalid");
            }
            if (level < 1 || level > 5 || rating < 1 || rating > 5) throw new RuleViolation("city_invalid");
            for (String field : new String[]{reason, notes, summary}) {
                if (field.length() > 256 || field.chars().anyMatch(ch -> Character.isISOControl(ch) || ch == 167))
                    throw new RuleViolation("city_invalid");
            }
            if (rating != suggested(total()) && reason.isBlank()) throw new RuleViolation("city_reason");
        }

        public CompoundTag fields(boolean internal) {
            var tag = new CompoundTag();
            for (var c : Category.values()) tag.putInt(c.name(), score(c));
            tag.putInt("level", level);
            tag.putInt("rating", rating);
            tag.putInt("total", total());
            tag.putString("summary", summary);
            if (internal) {
                tag.putString("reason", reason);
                tag.putString("notes", notes);
            }
            return tag;
        }
        public static Draft read(CompoundTag t) {
            for (var c : Category.values()) {
                if (!t.contains(c.name(), Tag.TAG_INT)) throw new RuleViolation("city_invalid");
            }
            var draft = new Draft(t.getInt("ARCHITECTURE"), t.getInt("INFRASTRUCTURE"), t.getInt("RESIDENTIAL"),
                    t.getInt("PUBLIC_BUILDINGS"), t.getInt("COMPLETION"), t.getInt("level"), t.getInt("rating"),
                    t.getString("reason"), t.getString("notes"), t.getString("summary"));
            draft.validate();
            return draft;
        }
    }
    public CompoundTag fields(boolean internal) {
        var tag = draft.fields(internal);
        tag.putLong("date", date);
        if (internal) tag.putUUID("reviewer", reviewer);
        return tag;
    }
    static CityEvaluation load(CompoundTag t) {
        return new CityEvaluation(t.getUUID("reviewer"), t.getLong("date"), Draft.read(t));
    }
}
