#include "FeatureDialogs.h"
#include "ScreenInputView.h"

#include <QClipboard>
#include <QCloseEvent>
#include <QDateTime>
#include <QGuiApplication>
#include <QHBoxLayout>
#include <QJsonObject>
#include <QKeyEvent>
#include <QLabel>
#include <QList>
#include <QMessageBox>
#include <QMetaObject>
#include <QPushButton>
#include <QSize>
#include <QTextEdit>
#include <QTimer>
#include <QVBoxLayout>
#include <algorithm>
#include <cmath>
#include <utility>

namespace {
constexpr qint64 kSessionLimitMs = 60LL * 60 * 1000;

/**
 * Shortest gap between two pointer positions on the wire. A mouse reports far
 * faster than the phone can move an overlay, and the extra packets only queue up
 * in front of the one that is still true.
 */
constexpr qint64 kCursorMinGapMs = 16;
}

FeatureDialogBase::FeatureDialogBase(const QString &title, ClientHandler *handler, QWidget *parent)
    : QDialog(parent)
    , m_handler(handler)
{
    setWindowTitle(title);
    resize(560, 420);
    setModal(false);
}

void FeatureDialogBase::sendAction(const QString &action, const QJsonObject &payload)
{
    if (!m_handler) {
        appendStatus(QStringLiteral("Client is no longer connected."));
        return;
    }
    m_handler->sendCommand(action, payload);
    appendStatus(QStringLiteral("Sent command: %1").arg(action));
}

void FeatureDialogBase::sendInput(const QString &action, const QJsonObject &payload)
{
    if (!m_handler) {
        appendStatus(QStringLiteral("Client is no longer connected."));
        return;
    }
    m_handler->sendCommand(action, payload);
    appendStatus(QStringLiteral("Sent %1").arg(action));
}

void FeatureDialogBase::sendQuiet(const QString &action, const QJsonObject &payload)
{
    if (!m_handler) {
        return;
    }
    m_handler->sendCommand(action, payload);
}

void FeatureDialogBase::appendStatus(const QString &text)
{
    if (m_status) {
        m_status->append(text);
    }
}

ScreenMonitorDialog::ScreenMonitorDialog(ClientHandler *handler, QWidget *parent)
    : FeatureDialogBase(QStringLiteral("Screen monitoring"), handler, parent)
{
    resize(540, 1100);

    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(12, 12, 12, 12);
    layout->setSpacing(10);

    m_offlineBanner = new QLabel(QStringLiteral("Not Connected to the Internet"), this);
    m_offlineBanner->setAlignment(Qt::AlignCenter);
    m_offlineBanner->setWordWrap(true);
    m_offlineBanner->setStyleSheet(QStringLiteral(
        "QLabel { background: #FDECEC; color: #9B1C1C; border: 1px solid #E6B0B0;"
        " border-radius: 8px; padding: 10px 14px; font-weight: 700; font-size: 14px; }"));
    m_offlineBanner->setVisible(false);
    layout->addWidget(m_offlineBanner);
    const QString phone = handler ? handler->info().phoneNumber : QStringLiteral("client");
    setWindowTitle(QStringLiteral("Screen monitoring — %1").arg(phone));

    m_preview = new ScreenInputView(this);
    m_preview->onEvent = [this](const ScreenInputView::Event &ev) {
        handlePreviewEvent(ev);
    };
    m_preview->onSizeApplied = [this] {
        if (m_handler) {
            m_handler->setPreviewTargetSize(m_preview->size());
        }
        scheduleDialogFit();
    };

    m_stats = new QLabel(QStringLiteral("Waiting for video…"), this);
    m_stats->setAlignment(Qt::AlignCenter);

    m_stallTimer = new QTimer(this);
    m_stallTimer->setSingleShot(true);
    connect(m_stallTimer, &QTimer::timeout, this, &ScreenMonitorDialog::onVideoStall);

    m_reattachTimer = new QTimer(this);
    connect(m_reattachTimer, &QTimer::timeout, this, &ScreenMonitorDialog::tryReattach);

    m_rateTimer = new QTimer(this);
    connect(m_rateTimer, &QTimer::timeout, this, &ScreenMonitorDialog::sampleRates);
    m_rateTimer->start(1000);

    m_cursorFlushTimer = new QTimer(this);
    m_cursorFlushTimer->setSingleShot(true);
    connect(m_cursorFlushTimer, &QTimer::timeout, this, &ScreenMonitorDialog::onCursorFlush);

    // One hour per sharing session. The relay is the real clock — it ends the
    // session for whoever holds it — so this is only a local backstop, rebased by
    // setSessionRemaining() to whatever the relay said is left when taking over a
    // session that an earlier operator already spent part of.
    m_sessionLimitTimer = new QTimer(this);
    m_sessionLimitTimer->setSingleShot(true);
    connect(m_sessionLimitTimer, &QTimer::timeout,
            this, &ScreenMonitorDialog::onSessionLimitReached);
    m_sessionStartedAt = QDateTime::currentMSecsSinceEpoch();
    m_sessionLimitTimer->start(int(kSessionLimitMs));

    auto *buttons = new QHBoxLayout;
    auto *backBtn = new QPushButton(QStringLiteral("Back"), this);
    auto *homeBtn = new QPushButton(QStringLiteral("Home"), this);
    auto *recentsBtn = new QPushButton(QStringLiteral("Recents"), this);
    auto *stopBtn = new QPushButton(QStringLiteral("Stop"), this);
    // A dialog button is auto-default, so Enter would click Back instead of
    // reaching the phone. These buttons are mouse-only.
    const QList<QPushButton *> navButtons = {backBtn, homeBtn, recentsBtn, stopBtn};
    for (QPushButton *button : navButtons) {
        button->setAutoDefault(false);
        button->setDefault(false);
        button->setFocusPolicy(Qt::NoFocus);
    }
    buttons->addWidget(backBtn);
    buttons->addWidget(homeBtn);
    buttons->addWidget(recentsBtn);
    buttons->addWidget(stopBtn);

    m_status = new QTextEdit(this);
    m_status->setReadOnly(true);
    m_status->setFocusPolicy(Qt::NoFocus);
    m_status->setMaximumHeight(90);

    layout->addWidget(m_preview, 0, Qt::AlignHCenter);
    layout->addWidget(m_stats);
    layout->addLayout(buttons);
    layout->addWidget(m_status);

    connect(backBtn, &QPushButton::clicked, this, &ScreenMonitorDialog::sendNavBack);
    connect(homeBtn, &QPushButton::clicked, this, &ScreenMonitorDialog::sendNavHome);
    connect(recentsBtn, &QPushButton::clicked, this, &ScreenMonitorDialog::sendNavRecents);
    connect(stopBtn, &QPushButton::clicked, this, &ScreenMonitorDialog::onStopClicked);

    bindHandler(handler);
    startMonitoring();
    if (m_preview) {
        m_preview->setFocus();
    }
}

void ScreenMonitorDialog::setInternetOffline(bool offline)
{
    if (m_offlineBanner) {
        m_offlineBanner->setVisible(offline);
    }
}

// Wire the window to the handler streaming this mobile. Called again after a
// re-attach, so every connection is scoped to that handler and to a generation.
void ScreenMonitorDialog::bindHandler(ClientHandler *handler)
{
    if (m_handler && m_handler != handler) {
        disconnect(m_handler, nullptr, this, nullptr);
    }
    m_handler = handler;
    m_disconnected = false;
    const quint64 gen = ++m_handlerGen;
    if (!handler) {
        return;
    }
    m_agentId = handler->clientId();
    if (m_preview) {
        handler->setPreviewTargetSize(m_preview->size());
    }

    connect(handler, &ClientHandler::frameReady, this,
            [this]() {
                // Keep the stall timer running as a freeze watchdog rather than
                // stopping it: if frames later stop arriving mid-session (a phone
                // that wedges after a while), onVideoStall re-requests capture.
                if (m_stallTimer) {
                    m_stallTimer->start(4000);
                }
                // Coalesced: pull only the newest frame. If several were decoded
                // while this call sat queued, the intermediates are already gone,
                // so the counter advances by one per real repaint and never bursts.
                if (!m_handler) {
                    return;
                }
                QImage image;
                int width = 0;
                int height = 0;
                if (m_handler->takeLatestFrame(&image, &width, &height)) {
                    showDecodedFrame(std::move(image), width, height);
                }
            }, Qt::QueuedConnection);
    connect(handler, &ClientHandler::responseReceived, this,
            [this](const QString &clientId, const QString &action, const QJsonObject &payload) {
                if (!m_handler || clientId != m_handler->clientId()) {
                    return;
                }
                if (action == QLatin1String("screen_monitor_start")
                    || action == QLatin1String("screen_monitor_stop")) {
                    const QString err = payload.value(QStringLiteral("error")).toString();
                    const QString status = payload.value(QStringLiteral("status")).toString();
                    if (!err.isEmpty()) {
                        appendStatus(err);
                        if (m_preview) {
                            m_preview->setStatusText(err);
                        }
                    } else if (!status.isEmpty()) {
                        appendStatus(QStringLiteral("%1: %2").arg(action, status));
                    }
                    updateVideoStats();
                    return;
                }
                if (action != QLatin1String("input_mouse")
                    && action != QLatin1String("input_key")
                    && action != QLatin1String("input_touch")
                    && action != QLatin1String("input_text")
                    && action != QLatin1String("input_scroll")) {
                    return;
                }
                const QString err = payload.value(QStringLiteral("error")).toString();
                const QString status = payload.value(QStringLiteral("status")).toString();
                if (!err.isEmpty() || status == QLatin1String("need_permission")) {
                    appendStatus(err.isEmpty()
                                     ? QStringLiteral("Screen capture failed on the phone.")
                                     : err);
                } else if (status == QLatin1String("ok")) {
                    appendStatus(QStringLiteral("%1: ok").arg(action));
                }
            });

    // Relay drop, mobile going offline, or the stream socket closing all end the
    // ClientHandler's receive loop, which emits disconnected(). Its reap then
    // destroys the handler. Either event blacks out the window; the generation
    // guard keeps a queued signal from a replaced handler out of a fresh session.
    connect(handler, &ClientHandler::disconnected, this, [this, gen]() {
        if (gen == m_handlerGen) {
            onStreamDisconnected();
        }
    }, Qt::QueuedConnection);
    connect(handler, &ClientHandler::remoteShareEnded, this, [this, gen]() {
        if (gen != m_handlerGen || m_sessionClosed) {
            return;
        }
        abandon();
    }, Qt::QueuedConnection);
    connect(handler, &ClientHandler::destroyed, this, [this, gen]() {
        if (gen == m_handlerGen) {
            onStreamDisconnected();
        }
    }, Qt::QueuedConnection);

    // The previous window may have died holding an unconsumed notification, which
    // would keep this handler from ever emitting again.
    handler->resyncFrameNotify();
}

ScreenMonitorDialog::~ScreenMonitorDialog()
{
    if (m_handler) {
        disconnect(m_handler, &ClientHandler::frameReady, this, nullptr);
        m_handler->setPreviewTargetSize(QSize());
        if (m_streaming) {
            m_handler->sendCommand(QStringLiteral("screen_monitor_stop"));
        }
    }
    m_streaming = false;
}

void ScreenMonitorDialog::closeEvent(QCloseEvent *event)
{
    stopMonitoring();
    QDialog::closeEvent(event);
}

void ScreenMonitorDialog::requestStop()
{
    stopMonitoring();
    close();
}

// Stop matches the handset's own Stop button: screen sharing and remote control
// end, and the mobile app stays open. Closing the window without Stop only hands
// the phone back, so another controller can still take it.
//
// The latch is set before the claim is released. Releasing the viewer socket
// posts disconnected(), and a re-attach timeout may already be queued; either
// one used to call startMonitoring() again and flip the preview between
// "TCP connected" and "Disconnected".
void ScreenMonitorDialog::onStopClicked()
{
    if (m_sessionClosed) {
        return;
    }
    m_sessionClosed = true;
    m_disconnected = true;
    m_streaming = false;
    if (m_stallTimer) {
        m_stallTimer->stop();
    }
    if (m_reattachTimer) {
        m_reattachTimer->stop();
    }
    if (m_sessionLimitTimer) {
        m_sessionLimitTimer->stop();
    }
    sendCursor(0.0, 0.0, false);
    if (m_preview) {
        m_preview->showBlackout(QStringLiteral("Disconnected"));
    }
    if (m_stats) {
        m_stats->setText(QStringLiteral("Disconnected"));
    }
    emit sharingStopRequested(m_agentId);
    appendStatus(QStringLiteral("Stopped — sharing and remote control have ended on the mobile."));
    close();
}

void ScreenMonitorDialog::abandon()
{
    if (!m_sessionClosed) {
        emit remoteStopped(m_agentId);
    }
    m_streaming = false;
    m_disconnected = true;
    m_sessionClosed = true;
    m_handler = nullptr;
    if (m_stallTimer) {
        m_stallTimer->stop();
    }
    if (m_reattachTimer) {
        m_reattachTimer->stop();
    }
    if (m_sessionLimitTimer) {
        m_sessionLimitTimer->stop();
    }
    close();
}

void ScreenMonitorDialog::onSessionLimitReached()
{
    if (m_sessionClosed) {
        return;
    }
    m_sessionClosed = true;
    if (m_preview) {
        m_preview->showBlackout(QStringLiteral("Stopped after 1 hour"));
    }
    QWidget *host = parentWidget();
    const QString id = m_agentId;
    // Tell the relay before this window closes. Closing alone hands the phone
    // back, which put it on the list again instead of ending the session.
    emit sessionHourElapsed(id);
    stopMonitoring();
    hide();
    QTimer::singleShot(0, host, [host, id]() {
        const QString who = id.isEmpty()
            ? QString()
            : QStringLiteral(" (%1)").arg(id);
        QMessageBox::information(
            host,
            QStringLiteral("Screen monitoring"),
            QStringLiteral("Streaming was stopped automatically after 1 hour%1.")
                .arg(who));
    });
    close();
}

void ScreenMonitorDialog::startMonitoring()
{
    // Stop already ended this window. A queued re-attach must not paint
    // "TCP connected" or open another socket.
    if (m_sessionClosed) {
        return;
    }
    m_streaming = true;
    m_frameCount = 0;
    m_stallFrameMark = 0;
    m_stallStrikes = 0;
    // Hide any leftover yellow pointer from a previous session; do not show it
    // again until the first video frame arrives.
    sendCursor(0.0, 0.0, false);
    if (m_preview) {
        m_preview->clearFrame();
        m_preview->setStatusText(
            QStringLiteral("TCP connected — sent screen_monitor_start\nWaiting for video…"));
    }
    updateVideoStats();
    if (m_stallTimer) {
        m_stallTimer->start(2500);
    }
    sendAction(QStringLiteral("screen_monitor_start"), monitorStartPayload());
}

// The phone runs its own stop timer as a backstop. It cannot tell a newly opened
// window from a re-attach or a stall re-request, so every start carries the time
// this window has left and the phone re-arms to the same deadline.
void ScreenMonitorDialog::setSessionRemaining(qint64 remainMs)
{
    if (remainMs <= 0) {
        return;
    }
    const qint64 clamped = std::min<qint64>(remainMs, kSessionLimitMs);
    m_sessionStartedAt = QDateTime::currentMSecsSinceEpoch() - (kSessionLimitMs - clamped);
    if (m_sessionLimitTimer) {
        m_sessionLimitTimer->start(int(clamped));
    }
}

QJsonObject ScreenMonitorDialog::monitorStartPayload() const
{
    const qint64 elapsed = QDateTime::currentMSecsSinceEpoch() - m_sessionStartedAt;
    QJsonObject payload;
    payload.insert(QStringLiteral("limit_ms"),
                   double(std::max<qint64>(1000, kSessionLimitMs - elapsed)));
    return payload;
}

void ScreenMonitorDialog::stopMonitoring()
{
    sendCursor(0.0, 0.0, false);
    if (m_stallTimer) {
        m_stallTimer->stop();
    }
    if (m_reattachTimer) {
        m_reattachTimer->stop();
    }
    if (m_sessionLimitTimer) {
        m_sessionLimitTimer->stop();
    }
    if (!m_streaming) {
        return;
    }
    m_streaming = false;
    sendAction(QStringLiteral("screen_monitor_stop"));
}

// Connection to the relay or the mobile was lost. Black out the preview and stop
// treating the session as live so we neither ask a dead handler to stop streaming
// nor keep the stale last frame on screen.
void ScreenMonitorDialog::onStreamDisconnected()
{
    if (m_sessionClosed) {
        if (m_reattachTimer) {
            m_reattachTimer->stop();
        }
        return;
    }
    if (m_disconnected) {
        return;
    }
    m_disconnected = true;
    m_streaming = false;
    sendCursor(0.0, 0.0, false);
    if (m_stallTimer) {
        m_stallTimer->stop();
    }
    if (m_preview) {
        m_preview->showBlackout(QStringLiteral("Disconnected"));
    }
    if (m_stats) {
        m_stats->setText(QStringLiteral("Disconnected"));
    }
    appendStatus(QStringLiteral("Connection lost — screen blacked out."));
    // Phone Stop takes the device off the list and tells this window to go.
    // Waiting here is what left the streaming view on screen.
    if (shareGone && shareGone()) {
        abandon();
        return;
    }
    // The relay closes this viewer whenever the phone re-registers, expecting the
    // desktop to attach again — an uplink that comes back with a different public
    // IP triggers that several times a day. Poll for a live handler so the window
    // recovers on its own instead of staying black until it is closed and reopened.
    if (m_reattachTimer && reattachProvider) {
        m_reattachTimer->start(1500);
    }
}

void ScreenMonitorDialog::tryReattach()
{
    // Stop has already dropped the socket. Never dial another one from here.
    if (m_sessionClosed || !m_disconnected || !reattachProvider) {
        if (m_reattachTimer) {
            m_reattachTimer->stop();
        }
        return;
    }
    if (shareGone && shareGone()) {
        if (m_reattachTimer) {
            m_reattachTimer->stop();
        }
        abandon();
        return;
    }
    ClientHandler *handler = reattachProvider();
    if (!handler) {
        // Still unreachable — keep waiting rather than giving up on the window.
        return;
    }
    m_reattachTimer->stop();
    appendStatus(QStringLiteral("Re-attached — restarting capture."));
    bindHandler(handler);
    startMonitoring();
}

namespace {
constexpr int kAndroidBack = 4;
constexpr int kAndroidHome = 3;
constexpr int kAndroidAppSwitch = 187;
constexpr int kAndroidDel = 67;
constexpr int kAndroidEnter = 66;
constexpr int kAndroidTab = 61;
constexpr int kAndroidLeft = 21;
constexpr int kAndroidRight = 22;
constexpr int kAndroidUp = 19;
constexpr int kAndroidDown = 20;
constexpr int kAndroidForwardDel = 112;
constexpr int kAndroidMoveHome = 122;
constexpr int kAndroidMoveEnd = 123;
constexpr int kMetaShift = 1;
constexpr int kMetaAlt = 2;
constexpr int kMetaCtrl = 0x1000;
constexpr int kMetaMeta = 0x10000;

bool isTextKey(int key)
{
    return (key >= Qt::Key_A && key <= Qt::Key_Z)
        || (key >= Qt::Key_0 && key <= Qt::Key_9)
        || key == Qt::Key_Space;
}

int androidMeta(Qt::KeyboardModifiers mods)
{
    int meta = 0;
    if (mods & Qt::ShiftModifier) {
        meta |= kMetaShift;
    }
    if (mods & Qt::AltModifier) {
        meta |= kMetaAlt;
    }
    if (mods & Qt::ControlModifier) {
        meta |= kMetaCtrl;
    }
    if (mods & Qt::MetaModifier) {
        meta |= kMetaMeta;
    }
    return meta;
}

int qtPunctToAndroid(int key)
{
    switch (key) {
    case Qt::Key_Space:
        return 62;
    case Qt::Key_Comma:
        return 55;
    case Qt::Key_Period:
        return 56;
    case Qt::Key_Minus:
        return 69;
    case Qt::Key_Equal:
        return 70;
    case Qt::Key_BracketLeft:
        return 71;
    case Qt::Key_BracketRight:
        return 72;
    case Qt::Key_Backslash:
        return 73;
    case Qt::Key_Semicolon:
        return 74;
    case Qt::Key_Apostrophe:
        return 75;
    case Qt::Key_Slash:
        return 76;
    case Qt::Key_QuoteLeft:
        return 68;
    default:
        return 0;
    }
}

int qtKeyToAndroid(int key)
{
    if (key >= Qt::Key_A && key <= Qt::Key_Z) {
        return 29 + (key - Qt::Key_A);
    }
    if (key >= Qt::Key_0 && key <= Qt::Key_9) {
        return 7 + (key - Qt::Key_0);
    }
    switch (key) {
    case Qt::Key_Escape:
    case Qt::Key_Back:
    case Qt::Key_F1:
        return kAndroidBack;
    case Qt::Key_Backspace:
        return kAndroidDel;
    case Qt::Key_Return:
    case Qt::Key_Enter:
        return kAndroidEnter;
    case Qt::Key_Tab:
        return kAndroidTab;
    case Qt::Key_Left:
        return kAndroidLeft;
    case Qt::Key_Right:
        return kAndroidRight;
    case Qt::Key_Up:
        return kAndroidUp;
    case Qt::Key_Down:
        return kAndroidDown;
    case Qt::Key_Delete:
        return kAndroidForwardDel;
    case Qt::Key_Home:
        return kAndroidMoveHome;
    case Qt::Key_End:
        return kAndroidMoveEnd;
    case Qt::Key_F2:
        return kAndroidHome;
    case Qt::Key_Menu:
    case Qt::Key_F3:
        return kAndroidAppSwitch;
    case Qt::Key_Shift:
        return 59;
    case Qt::Key_Control:
        return 113;
    case Qt::Key_Alt:
        return 57;
    case Qt::Key_Meta:
        return 117;
    case Qt::Key_PageUp:
        return 92;
    case Qt::Key_PageDown:
        return 93;
    case Qt::Key_Insert:
        return 124;
    case Qt::Key_CapsLock:
        return 115;
    default:
        return 0;
    }
}
}

void ScreenMonitorDialog::sendNavBack()
{
    sendAndroidKey(kAndroidBack, true);
    sendAndroidKey(kAndroidBack, false);
}

void ScreenMonitorDialog::sendNavHome()
{
    sendAndroidKey(kAndroidHome, true);
    sendAndroidKey(kAndroidHome, false);
}

void ScreenMonitorDialog::sendNavRecents()
{
    sendAndroidKey(kAndroidAppSwitch, true);
    sendAndroidKey(kAndroidAppSwitch, false);
}

void ScreenMonitorDialog::sendAndroidKey(int keyCode, bool down, int meta)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("k"), keyCode);
    payload.insert(QStringLiteral("d"), down);
    if (meta != 0) {
        payload.insert(QStringLiteral("m"), meta);
    }
    sendQuiet(QStringLiteral("input_key"), payload);
}

void ScreenMonitorDialog::sendTouch(const char *action, double nx, double ny)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("a"), QString::fromUtf8(action));
    payload.insert(QStringLiteral("x"), nx);
    payload.insert(QStringLiteral("y"), ny);
    sendQuiet(QStringLiteral("input_touch"), payload);
}

void ScreenMonitorDialog::sendCursor(double nx, double ny, bool visible)
{
    // No yellow pointer until video is on screen. Otherwise a hover during
    // "Waiting for video…" paints the phone before anything is connected.
    if (visible && (m_frameCount <= 0 || !m_streaming || m_disconnected)) {
        return;
    }
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (visible) {
        if (m_cursorShown && now - m_lastCursorTick < kCursorMinGapMs) {
            // Rate-limited, but not thrown away. The position right before the
            // mouse comes to rest is the one that matters most, and it is also
            // the one most likely to land inside this window — dropping it left
            // the phone's circle wherever the mouse happened to be a frame
            // earlier, with nothing following to correct it.
            m_cursorFlushX = nx;
            m_cursorFlushY = ny;
            m_cursorFlushPending = true;
            if (m_cursorFlushTimer && !m_cursorFlushTimer->isActive()) {
                m_cursorFlushTimer->start(kCursorMinGapMs);
            }
            return;
        }
    } else if (!m_cursorShown) {
        return;
    }
    writeCursor(nx, ny, visible);
}

/**
 * The mouse has stopped, or at least paused long enough that the last position
 * we held back is now the current one. Send it so the phone ends up where the
 * pointer actually is.
 */
void ScreenMonitorDialog::onCursorFlush()
{
    if (!m_cursorFlushPending) {
        return;
    }
    m_cursorFlushPending = false;
    if (!m_cursorShown || !m_streaming || m_disconnected || m_frameCount <= 0) {
        return;
    }
    writeCursor(m_cursorFlushX, m_cursorFlushY, true);
}

// Puts a cursor position on the wire, bypassing the rate limit. Everything that
// decides whether to send has already been decided by the caller.
void ScreenMonitorDialog::writeCursor(double nx, double ny, bool visible)
{
    m_cursorFlushPending = false;
    if (m_cursorFlushTimer) {
        m_cursorFlushTimer->stop();
    }
    m_lastCursorTick = QDateTime::currentMSecsSinceEpoch();
    m_lastCursorX = nx;
    m_lastCursorY = ny;
    m_cursorShown = visible;
    QJsonObject payload;
    payload.insert(QStringLiteral("a"),
                   visible ? QStringLiteral("hover") : QStringLiteral("hover_end"));
    payload.insert(QStringLiteral("x"), nx);
    payload.insert(QStringLiteral("y"), ny);
    sendQuiet(QStringLiteral("input_touch"), payload);
}

void ScreenMonitorDialog::sendText(const QString &text)
{
    if (text.isEmpty()) {
        return;
    }
    QJsonObject payload;
    payload.insert(QStringLiteral("t"), text);
    sendQuiet(QStringLiteral("input_text"), payload);
}

void ScreenMonitorDialog::sendClip(const QString &action)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("clip"), action);
    if (action == QLatin1String("paste")) {
        const QString text = QGuiApplication::clipboard()->text();
        if (!text.isEmpty()) {
            payload.insert(QStringLiteral("t"), text);
        }
    }
    sendQuiet(QStringLiteral("input_key"), payload);
}

void ScreenMonitorDialog::sendScroll(double nx, double ny, double vertical)
{
    QJsonObject payload;
    payload.insert(QStringLiteral("x"), nx);
    payload.insert(QStringLiteral("y"), ny);
    payload.insert(QStringLiteral("v"), vertical);
    sendQuiet(QStringLiteral("input_scroll"), payload);
}

void ScreenMonitorDialog::handlePreviewEvent(const ScreenInputView::Event &ev)
{
    if (!m_preview || !m_handler) {
        return;
    }
    switch (ev.kind) {
    case ScreenInputView::Kind::Press: {
        if (ev.button == Qt::RightButton) {
            break;
        }
        double nx = 0.5;
        double ny = 0.5;
        m_preview->mapNormalized(ev.pos, &nx, &ny);
        m_pressed = true;
        m_lastTouchTick = QDateTime::currentMSecsSinceEpoch();
        sendTouch("down", nx, ny);
        break;
    }
    case ScreenInputView::Kind::Move: {
        double nx = 0.5;
        double ny = 0.5;
        if (!m_preview->mapNormalized(ev.pos, &nx, &ny)) {
            break;
        }
        if (!m_pressed) {
            sendCursor(nx, ny, true);
            break;
        }
        m_lastTouchTick = QDateTime::currentMSecsSinceEpoch();
        sendTouch("move", nx, ny);
        break;
    }
    case ScreenInputView::Kind::Leave:
        sendCursor(0.0, 0.0, false);
        break;
    case ScreenInputView::Kind::Release: {
        double nx = 0.5;
        double ny = 0.5;
        m_preview->mapNormalized(ev.pos, &nx, &ny);
        if (ev.button == Qt::RightButton) {
            sendAndroidKey(kAndroidBack, true);
            sendAndroidKey(kAndroidBack, false);
            break;
        }
        if (!m_pressed) {
            break;
        }
        m_pressed = false;
        sendTouch("up", nx, ny);
        break;
    }
    case ScreenInputView::Kind::Wheel: {
        double nx = 0.5;
        double ny = 0.5;
        m_preview->mapNormalized(ev.pos, &nx, &ny);
        const double ticks = ev.angleDelta.y() / 120.0;
        if (qAbs(ticks) < 0.01) {
            break;
        }
        sendScroll(nx, ny, ticks);
        break;
    }
    case ScreenInputView::Kind::Key:
        sendRemoteKey(ev.key, true);
        break;
    case ScreenInputView::Kind::KeyRelease:
        sendRemoteKey(ev.key, false);
        break;
    }
}

bool ScreenMonitorDialog::sendRemoteKey(QKeyEvent *event, bool down)
{
    if (!event) {
        return false;
    }
    const Qt::KeyboardModifiers mods = event->modifiers();
    const int meta = androidMeta(mods);
    const int key = event->key();
    const bool chord = (mods & (Qt::ControlModifier | Qt::AltModifier | Qt::MetaModifier)) != 0;
    if ((mods & Qt::ControlModifier) && !(mods & (Qt::AltModifier | Qt::MetaModifier))) {
        const bool clipKey = key == Qt::Key_C || key == Qt::Key_X || key == Qt::Key_V
            || key == Qt::Key_A || key == Qt::Key_Insert;
        if (clipKey) {
            if (!event->isAutoRepeat() && !down) {
                if (key == Qt::Key_C || key == Qt::Key_Insert) {
                    sendClip(QStringLiteral("copy"));
                } else if (key == Qt::Key_X) {
                    sendClip(QStringLiteral("cut"));
                } else if (key == Qt::Key_V) {
                    sendClip(QStringLiteral("paste"));
                } else if (key == Qt::Key_A) {
                    sendClip(QStringLiteral("selectall"));
                }
            }
            return true;
        }
    }
    if (!chord && (mods & Qt::ShiftModifier) && !event->isAutoRepeat() && !down) {
        if (key == Qt::Key_Insert) {
            sendClip(QStringLiteral("paste"));
            return true;
        }
        if (key == Qt::Key_Delete) {
            sendClip(QStringLiteral("cut"));
            return true;
        }
    }
    if (!chord && isTextKey(key)) {
        if (down) {
            const QString text = event->text();
            if (!text.isEmpty()) {
                sendText(text);
            }
        }
        return true;
    }
    int code = qtKeyToAndroid(key);
    if (code == 0 && chord) {
        code = qtPunctToAndroid(key);
    }
    if (!chord && code == 0 && down) {
        const QString text = event->text();
        if (!text.isEmpty() && text.at(0).isPrint()) {
            sendText(text);
            return true;
        }
    }
    if (code != 0) {
        sendAndroidKey(code, down, meta);
        return true;
    }
    return false;
}

void ScreenMonitorDialog::keyPressEvent(QKeyEvent *event)
{
    if (sendRemoteKey(event, true)) {
        event->accept();
        return;
    }
    FeatureDialogBase::keyPressEvent(event);
}

void ScreenMonitorDialog::keyReleaseEvent(QKeyEvent *event)
{
    if (sendRemoteKey(event, false)) {
        event->accept();
        return;
    }
    FeatureDialogBase::keyReleaseEvent(event);
}

void ScreenMonitorDialog::showDecodedFrame(QImage image, int width, int height)
{
    ++m_frameCount;
    m_frameWidth = image.width();
    m_frameHeight = image.height();
    if (width > 1 && height > 1) {
        m_frameWidth = width;
        m_frameHeight = height;
    }
    if (m_preview) {
        m_preview->setFrameImage(std::move(image), m_frameWidth, m_frameHeight, m_frameCount);
    }
    if (m_stats) {
        m_stats->setText(QStringLiteral("Video: %1 frames  %2x%3")
                             .arg(m_frameCount)
                             .arg(m_frameWidth)
                             .arg(m_frameHeight));
    }
}

void ScreenMonitorDialog::scheduleDialogFit()
{
    if (m_adjustPosted) {
        return;
    }
    m_adjustPosted = true;
    QTimer::singleShot(0, this, [this] {
        m_adjustPosted = false;
        if (!m_preview || QGuiApplication::mouseButtons() != Qt::NoButton) {
            return;
        }
        adjustSize();
    });
}

void ScreenMonitorDialog::onVideoStall()
{
    if (m_sessionClosed || !m_streaming || m_disconnected) {
        return;
    }
    // The watchdog fired. If frames advanced since the last check the stream is
    // healthy — just keep watching. If they did not (no video yet, or the phone
    // wedged mid-session), re-request capture so the window self-heals instead of
    // sitting frozen until the operator closes and reopens it. The phone throttles
    // its own consent launches, so a periodic re-request is safe.
    const bool progressed = m_frameCount != m_stallFrameMark;
    m_stallFrameMark = m_frameCount;
    if (m_frameCount > 1 && !progressed) {
        // The phone does not repeat an unchanged screen. Silence here, after more
        // than the opening picture has arrived, is a still picture, not a dead
        // capture. A count that never leaves 1 is the other thing: the relay
        // handed over one snapshot and the live path never started, so fall
        // through and ask the phone again.
        if (m_stallTimer) {
            m_stallTimer->start(4000);
        }
        return;
    }
    if (progressed) {
        m_stallStrikes = 0;
    } else {
        ++m_stallStrikes;
        // Frames may well be arriving and decoding while only the notification is
        // lost, so re-arm locally before blaming the phone.
        if (m_handler) {
            m_handler->resyncFrameNotify();
        }
        sendQuiet(QStringLiteral("screen_monitor_start"), monitorStartPayload());
        const QString msg = m_frameCount > 0
            ? QStringLiteral("Video stalled — re-requesting capture from the phone.")
            : QStringLiteral("Waiting for video… re-requesting capture from the phone.");
        appendStatus(msg);
        if (m_preview && m_frameCount == 0) {
            m_preview->setStatusText(msg);
        }
    }
    if (m_stallTimer) {
        // Every re-request makes the phone reset its pacing and burst fresh frames,
        // which starves the link other windows share. Back off once a few tries have
        // not helped instead of hammering it at a fixed 3.5 s forever.
        const int wait = progressed ? 4000 : qMin(3500 * m_stallStrikes, 12000);
        m_stallTimer->start(wait);
    }
    updateVideoStats();
}

/**
 * Sample the receive counters once a second. The picture being late has three very
 * different causes — the phone not sending, the relay->desktop leg not delivering,
 * or frames arriving here and being dropped/not painted — and they are
 * indistinguishable by watching the window. Comparing "recv" against the phone's
 * own measured send rate says which leg is losing the time.
 */
void ScreenMonitorDialog::sampleRates()
{
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (!m_sessionClosed && m_sessionStartedAt != 0
            && now - m_sessionStartedAt >= kSessionLimitMs) {
        onSessionLimitReached();
        return;
    }
    if (!m_handler) {
        m_rateSampleAt = now;
        return;
    }
    const ClientHandler::VideoCounters c = m_handler->videoCounters();
    if (m_rateSampleAt != 0) {
        const double secs = double(now - m_rateSampleAt) / 1000.0;
        if (secs >= 0.2) {
            m_recvFps = double(c.frames - m_lastRxFrames) / secs;
            m_recvKbps = double(c.bytes - m_lastRxBytes) / 1024.0 / secs;
            m_shownFps = double(m_frameCount - m_lastShownFrames) / secs;
        }
    }
    m_rateSampleAt = now;
    m_lastRxFrames = c.frames;
    m_lastRxBytes = c.bytes;
    m_droppedTotal = c.dropped;
    m_lastRxDropped = c.dropped;
    m_lastShownFrames = m_frameCount;
    updateVideoStats();
}

void ScreenMonitorDialog::updateVideoStats()
{
    if (!m_stats) {
        return;
    }
    m_stats->setText(
        QStringLiteral("Video: %1 frames  %2x%3   recv %4 fps  %5 KB/s   shown %6 fps   dropped %7")
            .arg(m_frameCount)
            .arg(m_frameWidth)
            .arg(m_frameHeight)
            .arg(m_recvFps, 0, 'f', 1)
            .arg(m_recvKbps, 0, 'f', 0)
            .arg(m_shownFps, 0, 'f', 1)
            .arg(m_droppedTotal));
}
