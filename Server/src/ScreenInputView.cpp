#include "ScreenInputView.h"

#include <QColor>
#include <QEvent>
#include <QFont>
#include <QGuiApplication>
#include <QImage>
#include <QKeyEvent>
#include <QMetaObject>
#include <QMouseEvent>
#include <QPainter>
#include <QScreen>
#include <QSize>
#include <QSizePolicy>
#include <QThread>
#include <QTimer>
#include <QWheelEvent>
#include <algorithm>
#include <utility>

QSize ScreenInputView::fittedSize(int fw, int fh)
{
    if (fw < 2 || fh < 2) {
        return QSize(480, 960);
    }
    QSize src(fw, fh);
    int capW = 1080;
    int capH = 1600;
    if (QScreen *screen = QGuiApplication::primaryScreen()) {
        const QRect ag = screen->availableGeometry();
        capW = std::max(320, std::min(capW, ag.width() - 48));
        capH = std::max(320, std::min(capH, ag.height() - 160));
    }
    src.scale(QSize(capW, capH), Qt::KeepAspectRatio);
    src.setWidth(std::max(160, src.width()));
    src.setHeight(std::max(160, src.height()));
    return src;
}

ScreenInputView::ScreenInputView(QWidget *parent)
    : QWidget(parent)
{
    setMouseTracking(true);
    setFocusPolicy(Qt::StrongFocus);
    setCursor(Qt::PointingHandCursor);
    setAttribute(Qt::WA_OpaquePaintEvent, true);
    setAttribute(Qt::WA_Hover, true);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    setFixedSize(fittedSize(0, 0));
}

QSize ScreenInputView::sizeHint() const
{
    return fittedSize(m_frameW, m_frameH);
}

void ScreenInputView::scheduleFitToFrame()
{
    if (m_frameW < 2 || m_frameH < 2) {
        return;
    }
    const QSize target = fittedSize(m_frameW, m_frameH);
    if (size() == target) {
        m_sizeDirty = false;
        return;
    }
    m_sizeDirty = true;
    if (m_fitPosted) {
        return;
    }
    m_fitPosted = true;
    QTimer::singleShot(0, this, [this] {
        m_fitPosted = false;
        applyFitToFrame();
    });
}

void ScreenInputView::applyFitToFrame()
{
    if (m_frameW < 2 || m_frameH < 2) {
        return;
    }
    if (QGuiApplication::mouseButtons() != Qt::NoButton) {
        m_sizeDirty = true;
        return;
    }
    const QSize target = fittedSize(m_frameW, m_frameH);
    if (size() == target) {
        m_sizeDirty = false;
        return;
    }
    setFixedSize(target);
    m_sizeDirty = false;
    if (onSizeApplied) {
        onSizeApplied();
    }
}

void ScreenInputView::clearFrame()
{
    m_image = QImage();
    m_scaled = QImage();
    m_scaledFor = QSize();
    m_frameW = 0;
    m_frameH = 0;
    m_seq = 0;
    m_placeholder = true;
    m_blackout = false;
    update();
}

// The relay or mobile link dropped: paint the view solid black and stop showing
// the last (now stale) frame, so the operator can see at a glance the session is
// dead rather than staring at a frozen picture.
void ScreenInputView::showBlackout(const QString &text)
{
    m_image = QImage();
    m_scaled = QImage();
    m_scaledFor = QSize();
    m_seq = 0;
    m_placeholder = false;
    m_blackout = true;
    if (!text.isEmpty()) {
        m_statusText = text;
    }
    update();
}

void ScreenInputView::setStatusText(const QString &text)
{
    m_statusText = text;
    if (m_placeholder || m_image.isNull()) {
        update();
    }
}

void ScreenInputView::setFrame(const QImage &image, int frameW, int frameH, qint64 seq)
{
    setFrameImage(QImage(image), frameW, frameH, seq);
}

void ScreenInputView::setFrameImage(QImage image, int frameW, int frameH, qint64 seq)
{
    if (image.isNull()) {
        return;
    }
    if (QThread::currentThread() != thread()) {
        QMetaObject::invokeMethod(this, [this, image = std::move(image), frameW, frameH, seq]() mutable {
            setFrameImage(std::move(image), frameW, frameH, seq);
        }, Qt::QueuedConnection);
        return;
    }
    adoptImage(std::move(image), frameW, frameH, seq);
}

void ScreenInputView::adoptImage(QImage image, int frameW, int frameH, qint64 seq)
{
    if (image.isNull()) {
        return;
    }
    if (image.format() != QImage::Format_RGB32 && image.format() != QImage::Format_ARGB32) {
        image = image.convertToFormat(QImage::Format_RGB32);
    }
    m_image = std::move(image);
    m_scaled = QImage();
    m_scaledFor = QSize();
    m_blackout = false;
    if (m_image.isNull()) {
        return;
    }
    const int nextW = frameW > 1 ? frameW : m_image.width();
    const int nextH = frameH > 1 ? frameH : m_image.height();
    const bool sizeChanged = nextW != m_frameW || nextH != m_frameH;
    m_frameW = nextW;
    m_frameH = nextH;
    m_seq = seq;
    m_placeholder = false;
    if (sizeChanged) {
        scheduleFitToFrame();
    }
    update();
}

QRect ScreenInputView::contentRect() const
{
    const QRect r = rect();
    const int fw = m_frameW > 1 ? m_frameW : (m_image.isNull() ? 0 : m_image.width());
    const int fh = m_frameH > 1 ? m_frameH : (m_image.isNull() ? 0 : m_image.height());
    if (fw < 2 || fh < 2) {
        return r;
    }
    const QSize drawn = QSize(fw, fh).scaled(r.size(), Qt::KeepAspectRatio);
    QRect box(QPoint(0, 0), drawn);
    box.moveCenter(r.center());
    return box;
}

bool ScreenInputView::mapNormalized(const QPoint &pos, double *nx, double *ny) const
{
    if (!nx || !ny) {
        return false;
    }
    const QRect box = contentRect();
    if (box.width() < 2 || box.height() < 2) {
        return false;
    }
    *nx = std::clamp((double(pos.x() - box.x()) + 0.5) / double(box.width()), 0.0, 1.0);
    *ny = std::clamp((double(pos.y() - box.y()) + 0.5) / double(box.height()), 0.0, 1.0);
    return true;
}

void ScreenInputView::emitEvent(Kind kind, const QPoint &pos, Qt::MouseButton button,
                               const QPoint &angleDelta, QKeyEvent *key)
{
    if (!onEvent) {
        return;
    }
    Event ev;
    ev.kind = kind;
    ev.pos = pos;
    ev.button = button;
    ev.angleDelta = angleDelta;
    ev.key = key;
    try {
        onEvent(ev);
    } catch (...) {
    }
}

void ScreenInputView::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    if (m_blackout) {
        p.fillRect(rect(), Qt::black);
        if (!m_statusText.isEmpty()) {
            p.setFont(overlayFont());
            p.setRenderHint(QPainter::TextAntialiasing, true);
            p.setPen(QColor(0x70, 0x76, 0x80));
            p.drawText(rect(), Qt::AlignCenter, m_statusText);
        }
        return;
    }
    p.fillRect(rect(), QColor(0x12, 0x14, 0x1A));
    p.setFont(overlayFont());
    p.setRenderHint(QPainter::TextAntialiasing, true);
    if (m_placeholder || m_image.isNull()) {
        p.setPen(QColor(0xb0, 0xb8, 0xc4));
        p.drawText(rect(), Qt::AlignCenter, m_statusText);
        return;
    }

    const QRect box = contentRect();
    p.setCompositionMode(QPainter::CompositionMode_Source);
    if (m_image.size() == box.size()) {
        p.drawImage(box.topLeft(), m_image);
        return;
    }
    // Cache the fitted copy so repeated repaints of one frame rescale only once.
    if (m_scaled.isNull() || m_scaledFor != box.size()) {
        m_scaled = m_image.scaled(box.size(), Qt::IgnoreAspectRatio,
                                  Qt::SmoothTransformation);
        m_scaledFor = box.size();
    }
    if (m_scaled.isNull()) {
        p.setRenderHint(QPainter::SmoothPixmapTransform, true);
        p.drawImage(box, m_image);
        return;
    }
    p.drawImage(box.topLeft(), m_scaled);
}

QFont ScreenInputView::overlayFont() const
{
    QFont font = this->font();
    font.setHintingPreference(QFont::PreferFullHinting);
    font.setStyleStrategy(QFont::PreferAntialias);
    if (font.pixelSize() < 0) {
        font.setPixelSize(13);
    }
    return font;
}

void ScreenInputView::mousePressEvent(QMouseEvent *event)
{
    if (!event) {
        return;
    }
    setFocus(Qt::MouseFocusReason);
    emitEvent(Kind::Press, event->pos(), event->button());
    event->accept();
}

void ScreenInputView::mouseDoubleClickEvent(QMouseEvent *event)
{
    if (!event) {
        return;
    }
    // Qt delivers this instead of mousePressEvent for the second press, so a
    // double-click was one tap and then a release with no matching down.
    setFocus(Qt::MouseFocusReason);
    emitEvent(Kind::Press, event->pos(), event->button());
    event->accept();
}

void ScreenInputView::mouseMoveEvent(QMouseEvent *event)
{
    if (!event) {
        return;
    }
    emitEvent(Kind::Move, event->pos(), event->button());
    event->accept();
}

void ScreenInputView::leaveEvent(QEvent *event)
{
    emitEvent(Kind::Leave, QPoint(), Qt::NoButton);
    QWidget::leaveEvent(event);
}

void ScreenInputView::mouseReleaseEvent(QMouseEvent *event)
{
    if (!event) {
        return;
    }
    emitEvent(Kind::Release, event->pos(), event->button());
    if (m_sizeDirty) {
        scheduleFitToFrame();
    }
    event->accept();
}

void ScreenInputView::wheelEvent(QWheelEvent *event)
{
    QPoint delta = event->angleDelta();
    if (delta.manhattanLength() == 0) {
        const QPoint px = event->pixelDelta();
        delta = QPoint(px.x() * 3, px.y() * 3);
    }
    emitEvent(Kind::Wheel, event->position().toPoint(), Qt::NoButton, delta);
    event->accept();
}

void ScreenInputView::keyPressEvent(QKeyEvent *event)
{
    emitEvent(Kind::Key, QPoint(), Qt::NoButton, QPoint(), event);
    event->accept();
}

void ScreenInputView::keyReleaseEvent(QKeyEvent *event)
{
    emitEvent(Kind::KeyRelease, QPoint(), Qt::NoButton, QPoint(), event);
    event->accept();
}
