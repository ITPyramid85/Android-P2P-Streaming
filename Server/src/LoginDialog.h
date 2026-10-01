#pragma once

#include <QDialog>
#include <QString>

class QLineEdit;
class QLabel;
class QPushButton;

class LoginDialog : public QDialog
{
    Q_OBJECT

public:
    explicit LoginDialog(QWidget *parent = nullptr);

    QString host() const;
    quint16 port() const;
    QString username() const;
    void resetFields();
    void setNotice(const QString &text);
    void setBusy(bool busy);

signals:
    void loginRequested(const QString &host, quint16 port, const QString &username,
                        const QString &password);

private slots:
    void tryLogin();

private:
    bool parseAddress(QString *host, quint16 *port, QString *error) const;

    QLineEdit *m_address = nullptr;
    QLineEdit *m_user = nullptr;
    QLineEdit *m_pass = nullptr;
    QLabel *m_error = nullptr;
    QPushButton *m_signIn = nullptr;
};
