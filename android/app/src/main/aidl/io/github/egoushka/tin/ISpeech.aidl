package io.github.egoushka.tin;

// Served by SpeechService in the app process; TinService (shell uid) forwards @tin-speech lines to it.
interface ISpeech {
    // One protocol line in (io.github.egoushka.tin.speech.Protocol), one line out. Blocks while recording.
    String handle(String line) = 1;
}
