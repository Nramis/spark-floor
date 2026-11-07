package org.example.sparkfloor;

/** Immutable result with a monotonic capture time. Repeating never resets its age. */
public final class ResultSnapshot {
    public static final long STALE_AFTER_MS = 15000;
    public final String brief, details;
    public final long capturedAt;
    public final boolean demo;

    public ResultSnapshot(String brief, String details, long capturedAt, boolean demo) {
        this.brief = brief;
        this.details = details;
        this.capturedAt = capturedAt;
        this.demo = demo;
    }

    public String age(long now) {
        if (demo) return "Встроенный пример. Это не текущая обстановка.";
        if (capturedAt < 0 || now < capturedAt)
            return "Возраст снимка неизвестен. Сделайте новый снимок.";
        long seconds = (now - capturedAt) / 1000;
        long amount = seconds < 60 ? seconds : seconds / 60;
        String unit =
                seconds < 60
                        ? plural(amount, "секунду", "секунды", "секунд")
                        : plural(amount, "минуту", "минуты", "минут");
        String age = "Снимок сделан " + amount + " " + unit + " назад.";
        if (now - capturedAt >= STALE_AFTER_MS)
            age += " Обстановка могла измениться. Для новой оценки нужен новый снимок.";
        return age;
    }

    public String message(boolean detailed, long now) {
        return age(now) + " " + (detailed ? details : brief);
    }

    private static String plural(long number, String one, String few, String many) {
        long last = number % 100;
        if (last >= 11 && last <= 14) return many;
        if (number % 10 == 1) return one;
        if (number % 10 >= 2 && number % 10 <= 4) return few;
        return many;
    }
}
