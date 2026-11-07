package org.example.sparkfloor;

/** Guidance for a stationary portrait phone; directions refer to the camera, not a walking route. */
public final class CameraGuidance {
    public enum State {
        UNSTABLE,
        STRAIGHTEN,
        TILT_DOWN,
        RAISE,
        READY
    }

    public static State state(double[] up) {
        if (up == null || up.length != 3) return State.UNSTABLE;
        double norm = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
        if (!Double.isFinite(norm) || norm < .001) return State.UNSTABLE;
        double pitch = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, up[2] / norm))));
        if (pitch > 65) return State.RAISE;
        if (Math.atan2(Math.abs(up[0]), up[1]) > Math.toRadians(30)) return State.STRAIGHTEN;
        if (pitch < 10) return State.TILT_DOWN;
        return State.READY;
    }

    public static String hint(double[] up) {
        switch (state(up)) {
            case STRAIGHTEN:
                return "Держите телефон вертикально, объективом от себя.";
            case TILT_DOWN:
                return "Наклоните камеру немного вниз, чтобы в кадр попал пол.";
            case RAISE:
                return "Камера направлена слишком низко. Поднимите направление съёмки немного выше.";
            case READY:
                return "Наклон подходит для снимка. Держите телефон неподвижно.";
            default:
                return "Держите телефон неподвижно несколько секунд: наклон ещё не готов.";
        }
    }
}
