package io.github.egoushka.tin.home.signals

/** The ways a tap on the music card can open the player. */
enum class OpenVia { Session, Launch }

/** What a tap tries, in order: the session's own screen (the player on the right track), then the launcher of its app. */
fun openOrder(hasSession: Boolean, hasLaunch: Boolean): List<OpenVia> = buildList {
    if (hasSession) add(OpenVia.Session)
    if (hasLaunch) add(OpenVia.Launch)
}

/** Walks [order] until one way opens; a session intent its app has cancelled returns false and falls through to the launcher. */
fun openFirst(order: List<OpenVia>, open: (OpenVia) -> Boolean): Boolean = order.any(open)
