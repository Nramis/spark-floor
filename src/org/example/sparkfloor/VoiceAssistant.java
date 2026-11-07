package org.example.sparkfloor;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.HapticFeedbackConstants;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.function.Consumer;

/** Foreground, one-shot microphone sessions; synthesis and recognition never overlap. */
public final class VoiceAssistant {
    public static final int MICROPHONE_PERMISSION = 4;
    private final Activity activity;
    private final Consumer<VoiceCommands.Command> commands;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Button button;
    private final TextView status;
    private final AccessibilityManager accessibility;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private ToneGenerator readyTone;
    private boolean active, destroyed, ready, listening, permissionPending, speechInitialized;
    private boolean systemConsent;
    private int generation;
    private String pendingSpeech;
    private String pendingPermissionNotice;
    private String voiceProblem = "Загружается русская озвучка…";
    private final Runnable timeout =
            () -> {
                if (listening) {
                    finishListening();
                    tell("Время команды истекло. Нажмите кнопку и попробуйте ещё раз.");
                }
            };

    public VoiceAssistant(Activity activity, Consumer<VoiceCommands.Command> commands) {
        this.activity = activity;
        this.commands = commands;
        try {
            readyTone = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        } catch (RuntimeException ignored) {
        }
        accessibility =
                (AccessibilityManager) activity.getSystemService(Activity.ACCESSIBILITY_SERVICE);
        status = new TextView(activity);
        status.setTextSize(14);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_NONE);
        status.setText(voiceProblem);
        button = new Button(activity);
        button.setText("Голосовая команда");
        button.setContentDescription(
                "Голосовая команда. Нажмите для одной команды; повторное нажатие отменяет запись.");
        button.setOnClickListener(v -> requestCommand());
        button.setOnLongClickListener(
                v -> {
                    showHelp();
                    return true;
                });
        systemConsent = activity.getPreferences(0).getBoolean("systemSpeechConsent", false);
        tts = new TextToSpeech(activity, result -> handler.post(() -> initializeSpeech(result)));
    }

    public Button button() {
        return button;
    }

    public TextView statusView() {
        return status;
    }

    public String speechProblem() {
        return !usesScreenReader() && speechInitialized && !ready ? voiceProblem : null;
    }

    public boolean usesScreenReader() {
        return accessibility != null
                && accessibility.isEnabled()
                && accessibility.isTouchExplorationEnabled();
    }

    public void showHelp() {
        if (!active || destroyed) return;
        finishListening();
        new AlertDialog.Builder(activity)
                .setTitle("Команды помощника")
                .setMessage(VoiceCommands.help())
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Остановить речь", (d, w) -> stop())
                .show();
        tell(VoiceCommands.help());
    }

    private void initializeSpeech(int result) {
        if (destroyed) return;
        speechInitialized = true;
        try {
            if (result == TextToSpeech.SUCCESS) {
                Set<Voice> voices = tts.getVoices();
                Voice selected =
                        voices == null
                                ? null
                                : voices.stream()
                                        .filter(
                                                v ->
                                                        "ru".equals(v.getLocale().getLanguage())
                                                                && !v.isNetworkConnectionRequired())
                                        .sorted(Comparator.comparing(Voice::getName))
                                        .findFirst()
                                        .orElse(null);
                ready = selected != null && tts.setVoice(selected) == TextToSpeech.SUCCESS;
            }
        } catch (RuntimeException e) {
            ready = false;
        }
        voiceProblem =
                ready
                        ? ""
                        : "Нет локального русского голоса. Установите его в настройках синтеза речи Android.";
        if (!listening)
            status.setText(
                    ready || usesScreenReader()
                            ? "Помощник готов. Нажмите «Голосовая команда»."
                            : voiceProblem);
        if (active && pendingSpeech != null) {
            String text = pendingSpeech;
            pendingSpeech = null;
            speak(text);
        }
    }

    public void speak(String text) {
        if (!active || destroyed || text == null || text.isEmpty()) return;
        finishListening();
        if (usesScreenReader()) {
            pendingSpeech = null;
            if (tts != null) tts.stop();
            status.setText(text);
            status.announceForAccessibility(text);
            return;
        }
        if (!ready) {
            pendingSpeech = text;
            status.setText(voiceProblem);
            return;
        }
        pendingSpeech = null;
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assistant") == TextToSpeech.ERROR)
            status.setText("Не удалось озвучить результат. Он доступен текстом.");
    }

    /** A short assistant response; never replaces the last analysis result. */
    public void tell(String text) {
        speak(text);
        status.setText(ready || usesScreenReader() ? text : text + "\n" + voiceProblem);
    }

    public void stop() {
        finishListening();
        pendingSpeech = null;
        if (tts != null) tts.stop();
        if (active && usesScreenReader()) accessibility.interrupt();
        status.setText("Озвучивание остановлено. Микрофон выключен.");
    }

    private boolean onDeviceAvailable() {
        try {
            return Build.VERSION.SDK_INT >= 31
                    && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void requestCommand() {
        if (!active || destroyed) return;
        if (listening) {
            stop();
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            activity.requestPermissions(
                    new String[] {Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION);
            return;
        }
        if (!onDeviceAvailable() && !SpeechRecognizer.isRecognitionAvailable(activity)) {
            tell("На телефоне нет службы распознавания речи. Используйте кнопки приложения.");
            return;
        }
        if (!onDeviceAvailable() && !systemConsent) {
            if (tts != null) tts.stop();
            new AlertDialog.Builder(activity)
                    .setTitle("Голосовые команды")
                    .setMessage(
                            "Команду распознаёт установленная служба Android. Приложение запросит офлайн-режим, но служба может отправлять аудио через интернет. Анализ снимка остаётся на телефоне. Разрешить системное распознавание?")
                    .setPositiveButton(
                            "Использовать",
                            (d, w) -> {
                                systemConsent = true;
                                activity.getPreferences(0)
                                        .edit()
                                        .putBoolean("systemSpeechConsent", true)
                                        .apply();
                                beginListening();
                            })
                    .setNegativeButton(
                            "Отмена",
                            (d, w) ->
                                    tell(
                                            "Голосовая команда отменена. Можно пользоваться кнопками."))
                    .show();
            return;
        }
        beginListening();
    }

    public void onPermissionResult(boolean granted) {
        boolean requested = permissionPending;
        permissionPending = false;
        if (!requested || destroyed) return;
        // Explicit tap is required again: a permission dialog can pause the Activity.
        String notice =
                granted
                        ? "Микрофон разрешён. Нажмите «Голосовая команда», чтобы сказать команду."
                        : "Доступ к микрофону не разрешён. Озвучка и кнопки продолжают работать.";
        if (active) tell(notice);
        else {
            pendingPermissionNotice = notice;
            status.setText(notice);
        }
    }

    private void beginListening() {
        if (!active || destroyed) return;
        finishListening();
        pendingSpeech = null;
        if (tts != null) tts.stop();
        if (usesScreenReader()) accessibility.interrupt();
        final int session = ++generation;
        try {
            boolean local = onDeviceAvailable();
            recognizer =
                    local
                            ? SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
                            : SpeechRecognizer.createSpeechRecognizer(activity);
            listening = true;
            button.setText("Отменить команду");
            status.setText("Включаю микрофон…");
            recognizer.setRecognitionListener(
                    new RecognitionListener() {
                        private boolean current() {
                            return listening && active && !destroyed && session == generation;
                        }

                        @Override
                        public void onReadyForSpeech(Bundle params) {
                            if (!current()) return;
                            status.setText("Микрофон готов. Скажите одну команду.");
                            if (readyTone != null)
                                readyTone.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
                            button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        }

                        @Override
                        public void onBeginningOfSpeech() {}

                        @Override
                        public void onRmsChanged(float rmsdB) {}

                        @Override
                        public void onBufferReceived(byte[] buffer) {}

                        @Override
                        public void onEndOfSpeech() {
                            if (current()) status.setText("Распознаю команду…");
                        }

                        @Override
                        public void onError(int error) {
                            if (!current()) return;
                            finishListening();
                            tell(errorMessage(error));
                        }

                        @Override
                        public void onResults(Bundle results) {
                            if (!current()) return;
                            ArrayList<String> phrases =
                                    results == null
                                            ? null
                                            : results.getStringArrayList(
                                                    SpeechRecognizer.RESULTS_RECOGNITION);
                            String top =
                                    phrases == null || phrases.isEmpty() ? null : phrases.get(0);
                            VoiceCommands.Command command = VoiceCommands.parse(top);
                            finishListening();
                            status.setText("Микрофон выключен.");
                            if (command == VoiceCommands.Command.UNKNOWN)
                                tell("Команда не распознана. Для списка команд нажмите «Помощь».");
                            else commands.accept(command);
                        }

                        @Override
                        public void onPartialResults(Bundle results) {}

                        @Override
                        public void onEvent(int type, Bundle params) {}
                    });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            handler.postDelayed(timeout, 15000);
            recognizer.startListening(intent);
        } catch (RuntimeException e) {
            finishListening();
            tell(
                    "Не удалось включить распознавание речи. Проверьте разрешение микрофона и службу Android.");
        }
    }

    private String errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Команду не удалось расслышать. Нажмите кнопку и повторите.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Нет доступа к микрофону. Разрешите его в настройках приложения.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "Русское распознавание недоступно. Проверьте языковые пакеты службы Android.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Системной службе не удалось распознать команду без сети. Используйте кнопки или установите офлайн-распознавание.";
            default:
                return "Служба распознавания недоступна или микрофон занят. Попробуйте ещё раз.";
        }
    }

    private void finishListening() {
        listening = false;
        generation++;
        handler.removeCallbacks(timeout);
        SpeechRecognizer old = recognizer;
        recognizer = null;
        if (old != null) {
            try {
                old.cancel();
            } catch (RuntimeException ignored) {
            }
            try {
                old.destroy();
            } catch (RuntimeException ignored) {
            }
        }
        button.setText("Голосовая команда");
    }

    public void resume() {
        active = true;
        if (speechInitialized && !ready) initializeSpeech(TextToSpeech.SUCCESS);
        handler.post(
                () -> {
                    if (active && !destroyed && pendingPermissionNotice != null) {
                        String notice = pendingPermissionNotice;
                        pendingPermissionNotice = null;
                        tell(notice);
                    }
                });
    }

    public void pause() {
        active = false;
        stop();
    }

    public void destroy() {
        active = false;
        destroyed = true;
        stop();
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
        if (readyTone != null) {
            readyTone.release();
            readyTone = null;
        }
    }
}
