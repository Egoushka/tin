package io.github.egoushka.tin.voice;

oneway interface IVoiceCallback {
    void onState(int state) = 1;      // 1 listening, 2 thinking
    void onHeard(String text) = 2;    // empty: nothing understood
    void onError(String reason) = 3;  // "mic busy", "speech off", ...
}
