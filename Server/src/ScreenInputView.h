#pragma once

#include <QFont>
#include <QImage>
#include <QPoint>
#include <QSize>
#include <QString>
#include <QWidget>
#include <functional>

class QKeyEvent;

class ScreenInputView : public QWidget
{
public:
    enum class Kind { Press, Move, Release, Wheel, Key, KeyRelease };

    struct Event {
        Kind kind;
        QPoint pos;
        Qt::MouseButton button = Qt::NoButton;
        QPoint angleDelta;
        QKeyEvent *key = nullptr;
    };

    explicit ScreenInputView(QWidget *parent = nullptr);

    static QSize fittedSize(int frameW, int frameH);

    void clearFrame();
    void showBlackout(const QString &text = QString());
    void setFrame(const QImage &image, int frameW, int frameH, qint64 seq = 0);
    void setFrameImage(QImage image, int frameW, int frameH, qint64 seq = 0);
    void setStatusText(const QString &text);
    bool mapNormalized(const QPoint &pos, double *nx, double *ny) const;
    QSize sizeHint() const override;

    std::function<void(const Event &)> onEvent;
    std::function<void()> onSizeApplied;

protected:
    void paintEvent(QPaintEvent *event) override;
    void mousePressEvent(QMouseEvent *event) override;
    void mouseMoveEvent(QMouseEvent *event) override;
    void mouseReleaseEvent(QMouseEvent *event) override;
    void mouseDoubleClickEvent(QMouseEvent *event) override;
    void wheelEvent(QWheelEvent *event) override;
    void keyPressEvent(QKeyEvent *event) override;
    void keyReleaseEvent(QKeyEvent *event) override;

private:
    void emitEvent(Kind kind, const QPoint &pos, Qt::MouseButton button,
                   const QPoint &angleDelta = QPoint(), QKeyEvent *key = nullptr);
    QRect contentRect() const;
    void adoptImage(QImage image, int frameW, int frameH, qint64 seq);
    void scheduleFitToFrame();
    void applyFitToFrame();
    QFont overlayFont() const;

    QImage m_image;
    QImage m_scaled;
    QSize m_scaledFor;
    int m_frameW = 0;
    int m_frameH = 0;
    qint64 m_seq = 0;
    bool m_placeholder = true;
    bool m_blackout = false;
    bool m_sizeDirty = false;
    bool m_fitPosted = false;
    bool m_sizeLocked = false;
    QString m_statusText = QStringLiteral("Waiting for frames…");
};
