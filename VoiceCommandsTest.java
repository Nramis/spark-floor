import org.example.sparkfloor.VoiceCommands;
import static org.example.sparkfloor.VoiceCommands.Command.*;

public class VoiceCommandsTest {
    private static void expect(String phrase, VoiceCommands.Command expected) {
        VoiceCommands.Command actual = VoiceCommands.parse(phrase);
        if (actual != expected)
            throw new AssertionError(phrase + ": " + actual + " != " + expected);
    }

    public static void main(String[] args) {
        expect("Сделай снимок!", CAPTURE);
        expect("  СДЕЛАЙ   СНИМОК  ", CAPTURE);
        expect("сфотографируй", CAPTURE);
        expect("анализ", CAPTURE);
        expect("Повтори результат.", REPEAT);
        expect("помощь", HELP);
        expect("Что ты умеешь?", HELP);
        expect("готовность", STATUS);
        expect("высота камеры", HEIGHT);
        expect("СТОП!", STOP);
        expect("отмена", STOP);
        expect(null, UNKNOWN);
        expect("", UNKNOWN);
        expect("не делай снимок", UNKNOWN);
        expect("не повторяй", UNKNOWN);
        expect("снимок стоп", UNKNOWN);
        expect("сделай снимок и повтори", UNKNOWN);
        expect("покажи безопасную дорогу", UNKNOWN);
        expect("снимок 5", UNKNOWN);
        expect(new String(new char[200]).replace('\0', 'а'), UNKNOWN);
        System.out.println(
                "PASS Russian commands, case/whitespace/punctuation, negation/mixed/unknown/empty/oversized refusal");
    }
}
