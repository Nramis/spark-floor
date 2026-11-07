package org.example.sparkfloor;

import java.util.Locale;

/** A bounded command vocabulary. Negated, mixed and unknown phrases execute nothing. */
public final class VoiceCommands {
    public enum Command {
        CAPTURE,
        REPEAT,
        DETAILS,
        REPORT,
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
            case "подробнее":
            case "подробности":
            case "подробнее о снимке":
                return Command.DETAILS;
            case "отчет":
            case "отчет о проверке":
                return Command.REPORT;
            case "помощь":
            case "команды":
            case "что ты умеешь":
                return Command.HELP;
            case "статус":
            case "готовность":
            case "ты готов":
            case "проверь камеру":
            case "направление камеры":
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
                + "«снимок», «повтори», «подробнее», «статус», «высота», «отчёт», «помощь» или «стоп». "
                + "Каждое нажатие принимает одну команду. Повторное нажатие отменяет запись. "
                + "Все основные действия доступны отдельными крупными кнопками. "
                + "Для остановки речи нажмите «Остановить речь и микрофон». "
                + "Перед снимком остановитесь. Направьте телефон перед собой, объективом от себя и немного вниз. "
                + "Длина и изгибы относятся только к видимому участку пола. "
                + "Безопасность прохода не определяется.";
    }
}
