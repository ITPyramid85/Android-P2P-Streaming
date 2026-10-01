#include "SessionIdleWatch.h"

#include <QEvent>

SessionIdleWatch::SessionIdleWatch(int idleMs, QObject *parent)
    : QObject(parent)
{
    m_timer.setSingleShot(true);
    m_timer.setInterval(idleMs);
    connect(&m_timer, &QTimer::timeout, this, &SessionIdleWatch::idleTimeout);
}

void SessionIdleWatch::setArmed(bool armed)
{
    m_armed = armed;
    if (m_armed) {
        bump();
    } else {
        m_timer.stop();
    }
}

bool SessionIdleWatch::eventFilter(QObject *watched, QEvent *event)
{
    Q_UNUSED(watched);
    if (!m_armed) {
        return false;
    }
    switch (event->type()) {
    case QEvent::MouseButtonPress:
    case QEvent::MouseButtonDblClick:
    case QEvent::MouseMove:
    case QEvent::KeyPress:
    case QEvent::Wheel:
    case QEvent::TouchBegin:
        bump();
        break;
    default:
        break;
    }
    return false;
}

void SessionIdleWatch::bump()
{
    if (m_armed) {
        m_timer.start();
    }
}
