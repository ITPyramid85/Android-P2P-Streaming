#pragma once

#include <QDialog>
#include <QString>

class QLabel;
class QLineEdit;
class QPushButton;
class QWidget;

class AdminLoginDialog : public QDialog
{
    Q_OBJECT

public:
    explicit AdminLoginDialog(QWidget *parent = nullptr);

private slots:
    void trySignIn();
    void trySaveAccount();
    void showReset();
    void showSignIn();

private:
    void setNotice(const QString &text);
    void applyMode(bool resetMode);

    QLabel *m_title = nullptr;
    QLabel *m_hint = nullptr;
    QLabel *m_error = nullptr;
    QLabel *m_confirmLabel = nullptr;
    QLineEdit *m_email = nullptr;
    QLineEdit *m_pass = nullptr;
    QLineEdit *m_confirm = nullptr;
    QPushButton *m_primary = nullptr;
    QPushButton *m_forgot = nullptr;
    QPushButton *m_back = nullptr;
    QWidget *m_confirmBlock = nullptr;
    bool m_resetMode = false;
};
