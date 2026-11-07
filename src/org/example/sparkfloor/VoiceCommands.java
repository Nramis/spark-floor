package org.example.sparkfloor;

import java.util.Locale;

/** A bounded command vocabulary. Negated, mixed and unknown phrases execute nothing. */
public final class VoiceCommands {
    public enum Command {
        CAPTURE,
        REPEAT,
        HELP,
        STATUS,
        HEIGHT,
        STOP,
        UNKNOWN
    }

    public static Command parse(String phrase) {
        if (phrase == null || phrase.length() > 120) return Command.UNKNOWN;
        String text =
                phrase.toLowerCase(Locale.ROOT)
                        .replace('ё', 'е')
                        .replaceAll("[.,!?…:;]", " ")
                        .trim()
                        .replaceAll("\\s+", " ");
        switch (text) {
            case "снимок":
            case "сделай снимок":
            case "сделать снимок":
            case "фото":
            case "сфотографируй":
            case "анализ":
            case "анализируй":
                return Command.CAPTURE;
            case "повтори":
            case "повторить":
            case "повтори результат":
            case "результат":
                return Command.REPEAT;
            case "помощь":
            case "команды":
            case "что ты умеешь":
                return Command.HELP;
            case "статус":
            case "готовность":
            case "ты готов":
                return Command.STATUS;
            case "высота":
            case "высота камеры":
            case "настройки":
                return Command.HEIGHT;
            case "стоп":
            case "замолчи":
            case "останови речь":
            case "отмена":
                return Command.STOP;
            default:
                return Command.UNKNOWN;
        }
    }

    public static String help() {
        return "Нажмите «Голосовая команда», дождитесь готовности микрофона и скажите: "
                + "«снимок», «повтори», «статус», «высота», «помощь» или «стоп». "
                + "Каждое нажатие принимает одну команду. Повторное нажатие отменяет запись. "
                + "Длина и изгибы относятся только к видимому участку пола. "
                + "Безопасность прохода не определяется.";
    }
}
