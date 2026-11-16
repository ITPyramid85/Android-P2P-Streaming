#pragma once

#include "ClientHandler.h"
#include "ScreenInputView.h"

#include <QDialog>
#include <QImage>
#include <QPointer>
#include <QString>

#include <functional>

class QLabel;
class QTextEdit;
class QJsonObject;
class QKeyEvent;
class QTimer;

class FeatureDialogBase : public QDialog
{
    Q_OBJECT

public:
    FeatureDialogBase(const QString &title, ClientHandler *handler, QWidget *parent = nullptr);
    ClientHandler *clientHandler() const { return m_handler; }

protected:
    void sendAction(const QString &action, const QJsonObject &payload = QJsonObject());
    void sendInput(const QString &action, const QJsonObject &payload);
    void sendQuiet(const QString &action, const QJsonObject &payload);
    void appendStatus(const QString &text);

    QTextEdit *m_status = nullptr;
    QPointer<ClientHandler> m_handler;
};

class ScreenMonitorDialog : public FeatureDialogBase
{
    Q_OBJECT
public:
    ScreenMonitorDialog(ClientHandler *handler, QWidget *parent = nullptr);
    ~ScreenMonitorDialog() override;

    /**
     * Returns a live handler for this mobile, or nullptr while it is unreachable.
     * The relay drops a desktop viewer every time the phone re-registers, which an
     * uplink that changes its public IP causes several times a day, so the window
     * must be able to attach again instead of staying blacked out until reopened.
     */
    std::function<ClientHandler *()> reattachProvider;
    // True once this mobile has left the list. The window closes instead of
    // waiting to re-attach.
    std::function<bool()> shareGone;

    QString agentId() const { return m_agentId; }
    void setInternetOffline(bool offline);
    void requestStop();
    // The phone left the relay. Close without sending another stop to a dead handler.
    void abandon();
    // Time the relay says is left in this mobile's sharing session.
    void setSessionRemaining(qint64 remainMs);

signals:
    // The operator pressed Stop. Sharing and remote control end; the app stays up.
    void sharingStopRequested(const QString &agentId);
    // The phone ended the share. Closing this window must not hand it back.
    void remoteStopped(const QString &agentId);
    // This window's hour ran out. The relay ends the offer for every controller.
    void sessionHourElapsed(const QString &agentId);

protected:
    void closeEvent(QCloseEvent *event) override;
    void keyPressEvent(QKeyEvent *event) override;
    void keyReleaseEvent(QKeyEvent *event) override;

private slots:
    void onVideoStall();
    void startMonitoring();
    void stopMonitoring();
    void onStreamDisconnected();
    void tryReattach();
    void sendNavBack();
    void sendNavHome();
    void sendNavRecents();
    void onStopClicked();
    void onSessionLimitReached();
    void onCursorFlush();

private:
    void bindHandler(ClientHandler *handler);
    void writeCursor(double nx, double ny, bool visible);
    bool sendRemoteKey(QKeyEvent *event, bool down);
    void sendAndroidKey(int keyCode, bool down, int meta = 0);
    void sendTouch(const char *action, double nx, double ny);
    void sendCursor(double nx, double ny, bool visible);
    void sendText(const QString &text);
    void sendClip(const QString &action);
    void sendScroll(double nx, double ny, double vertical);
    void handlePreviewEvent(const ScreenInputView::Event &ev);
    void updateVideoStats();
    void sampleRates();
    QJsonObject monitorStartPayload() const;
    void showDecodedFrame(QImage image, int width, int height);
    void scheduleDialogFit();

    ScreenInputView *m_preview = nullptr;
    QLabel *m_offlineBanner = nullptr;
    QLabel *m_stats = nullptr;
    QTimer *m_stallTimer = nullptr;
    QTimer *m_reattachTimer = nullptr;
    QTimer *m_rateTimer = nullptr;
    QTimer *m_sessionLimitTimer = nullptr;
    // Sends the last position the rate limit held back, once the mouse pauses.
    QTimer *m_cursorFlushTimer = nullptr;
    qint64 m_sessionStartedAt = 0;
    bool m_sessionClosed = false;
    QString m_agentId;
    // Bumped on every (re)bind so a queued signal from a replaced handler cannot
    // black out the session that just took its place.
    quint64 m_handlerGen = 0;
    qint64 m_frameCount = 0;
    qint64 m_stallFrameMark = -1;
    int m_stallStrikes = 0;
    int m_frameWidth = 0;
    int m_frameHeight = 0;
    bool m_streaming = false;
    bool m_disconnected = false;
    bool m_pressed = false;
    qint64 m_lastTouchTick = 0;
    qint64 m_lastCursorTick = 0;
    double m_lastCursorX = -1.0;
    double m_lastCursorY = -1.0;
    bool m_cursorShown = false;
    // Newest position the rate limit refused to send yet.
    double m_cursorFlushX = 0.0;
    double m_cursorFlushY = 0.0;
    bool m_cursorFlushPending = false;
    bool m_adjustPosted = false;

    // Rates measured over the last sampling window, so the label separates "frames
    // never reached this PC" from "they reached it and were dropped or not painted".
    qint64 m_rateSampleAt = 0;
    qint64 m_lastRxFrames = 0;
    qint64 m_lastRxBytes = 0;
    qint64 m_lastRxDropped = 0;
    qint64 m_lastShownFrames = 0;
    double m_recvFps = 0.0;
    double m_recvKbps = 0.0;
    double m_shownFps = 0.0;
    qint64 m_droppedTotal = 0;
};
