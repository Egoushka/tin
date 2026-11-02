package io.github.egoushka.tin;

interface ITinService {
    // Shizuku calls this transaction code when it unbinds or replaces the service.
    void destroy() = 16777114;

    // One line: whether the tin child is alive and how many times it has been started.
    String status() = 1;

    // The app's ISpeech binder; @tin-speech forwards to it. The app calls this again after every restart.
    void setSpeech(IBinder speech) = 2;
}
