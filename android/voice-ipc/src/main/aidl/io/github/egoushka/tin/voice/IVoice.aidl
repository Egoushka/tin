package io.github.egoushka.tin.voice;

import io.github.egoushka.tin.voice.IVoiceCallback;

// Served by tin's VoiceService (permission io.github.egoushka.tin.permission.VOICE); Home binds it.
interface IVoice {
    // Records until the first speech segment ends or maxSeconds pass. Answers on cb; returns at once.
    void listen(double maxSeconds, IVoiceCallback cb) = 1;
    void cancel() = 2;
    // False while the model is missing or loading, or SpeechService is not running.
    boolean ready() = 3;
}
