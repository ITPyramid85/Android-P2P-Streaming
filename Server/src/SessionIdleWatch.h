#pragma once

#include <QObject>
#include <QTimer>

class SessionIdleWatch : public QObject
{
    Q_OBJECT

public:
    explicit SessionIdleWatch(int idleMs, QObject *parent = nullptr);

    void setArmed(bool armed);
    bool isArmed() const { return m_armed; }

signals:
    void idleTimeout();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void bump();

    QTimer m_timer;
    bool m_armed = false;
};
