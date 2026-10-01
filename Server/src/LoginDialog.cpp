#include "LoginDialog.h"
#include "AuthProtocol.h"

#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QSettings>
#include <QVBoxLayout>

LoginDialog::LoginDialog(QWidget *parent)
    : QDialog(parent)
{
    setWindowTitle(QStringLiteral("Remote Android Devices"));
    setObjectName(QStringLiteral("loginDialog"));
    setModal(true);
    setFixedSize(420, 460);
    setWindowFlag(Qt::WindowContextHelpButtonHint, false);

    auto *title = new QLabel(QStringLiteral("Sign in"), this);
    title->setObjectName(QStringLiteral("loginTitle"));

    auto *hint = new QLabel(
        QStringLiteral("Connect to AdminPanel. Use a RemoteController account created there."),
        this);
    hint->setWordWrap(true);

    QSettings settings;
    QString savedHost = settings.value(QStringLiteral("adminHost"),
                                       QStringLiteral("127.0.0.1")).toString().trimmed();
    if (savedHost.isEmpty()) {
        savedHost = QStringLiteral("127.0.0.1");
    }
    int savedPort = settings.value(QStringLiteral("adminPort"),
                                   AuthProtocol::kDefaultPort).toInt();
    if (savedPort == 9100 || savedPort < 1 || savedPort > 65535) {
        savedPort = AuthProtocol::kDefaultPort;
    }
    QString savedAddress = settings.value(QStringLiteral("adminAddress")).toString().trimmed();
    if (savedAddress.isEmpty()) {
        savedAddress = QStringLiteral("%1:%2").arg(savedHost).arg(savedPort);
    }

    auto *addressLabel = new QLabel(QStringLiteral("AdminPanel"), this);
    m_address = new QLineEdit(this);
    m_address->setPlaceholderText(QStringLiteral("127.0.0.1:516"));
    m_address->setClearButtonEnabled(true);
    m_address->setText(savedAddress);

    auto *userLabel = new QLabel(QStringLiteral("Username"), this);
    m_user = new QLineEdit(this);
    m_user->setPlaceholderText(QStringLiteral("Username"));
    m_user->setClearButtonEnabled(true);

    auto *passLabel = new QLabel(QStringLiteral("Password"), this);
    m_pass = new QLineEdit(this);
    m_pass->setPlaceholderText(QStringLiteral("Password"));
    m_pass->setEchoMode(QLineEdit::Password);
    m_pass->setClearButtonEnabled(true);

    m_error = new QLabel(this);
    m_error->setObjectName(QStringLiteral("loginError"));
    m_error->setWordWrap(true);
    m_error->hide();

    m_signIn = new QPushButton(QStringLiteral("Sign in"), this);
    m_signIn->setObjectName(QStringLiteral("loginButton"));
    m_signIn->setDefault(true);
    m_signIn->setCursor(Qt::PointingHandCursor);

    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(36, 28, 36, 28);
    layout->setSpacing(8);
    layout->addWidget(title);
    layout->addWidget(hint);
    layout->addSpacing(8);
    layout->addWidget(addressLabel);
    layout->addWidget(m_address);
    layout->addSpacing(6);
    layout->addWidget(userLabel);
    layout->addWidget(m_user);
    layout->addSpacing(6);
    layout->addWidget(passLabel);
    layout->addWidget(m_pass);
    layout->addWidget(m_error);
    layout->addStretch(1);
    layout->addWidget(m_signIn);

    connect(m_signIn, &QPushButton::clicked, this, &LoginDialog::tryLogin);
    connect(m_address, &QLineEdit::returnPressed, this, &LoginDialog::tryLogin);
    connect(m_user, &QLineEdit::returnPressed, this, &LoginDialog::tryLogin);
    connect(m_pass, &QLineEdit::returnPressed, this, &LoginDialog::tryLogin);

    m_address->setFocus();
}

bool LoginDialog::parseAddress(QString *host, quint16 *port, QString *error) const
{
    const QString text = m_address ? m_address->text().trimmed() : QString();
    const int colon = text.lastIndexOf(QLatin1Char(':'));
    if (colon <= 0 || colon == text.size() - 1) {
        if (error) {
            *error = QStringLiteral("Enter AdminPanel as IP:port, for example 127.0.0.1:516.");
        }
        return false;
    }
    const QString parsedHost = text.left(colon).trimmed();
    bool ok = false;
    const int parsedPort = text.mid(colon + 1).trimmed().toInt(&ok);
    if (parsedHost.isEmpty() || !ok || parsedPort < 1 || parsedPort > 65535) {
        if (error) {
            *error = QStringLiteral("Enter AdminPanel as IP:port, for example 127.0.0.1:516.");
        }
        return false;
    }
    if (host) {
        *host = parsedHost;
    }
    if (port) {
        *port = static_cast<quint16>(parsedPort);
    }
    return true;
}

QString LoginDialog::host() const
{
    QString host;
    quint16 port = AuthProtocol::kDefaultPort;
    if (!parseAddress(&host, &port, nullptr)) {
        return {};
    }
    return host;
}

quint16 LoginDialog::port() const
{
    QString host;
    quint16 port = AuthProtocol::kDefaultPort;
    if (!parseAddress(&host, &port, nullptr)) {
        return AuthProtocol::kDefaultPort;
    }
    return port;
}

QString LoginDialog::username() const
{
    return m_user ? m_user->text().trimmed() : QString();
}

void LoginDialog::resetFields()
{
    m_pass->clear();
    m_error->hide();
    setBusy(false);
    m_pass->setFocus();
}

void LoginDialog::setNotice(const QString &text)
{
    if (text.isEmpty()) {
        m_error->hide();
        return;
    }
    m_error->setText(text);
    m_error->show();
}

void LoginDialog::setBusy(bool busy)
{
    m_address->setEnabled(!busy);
    m_user->setEnabled(!busy);
    m_pass->setEnabled(!busy);
    m_signIn->setEnabled(!busy);
    m_signIn->setText(busy ? QStringLiteral("Connecting...") : QStringLiteral("Sign in"));
}

void LoginDialog::tryLogin()
{
    if (!m_signIn->isEnabled()) {
        return;
    }
    QString host;
    quint16 port = AuthProtocol::kDefaultPort;
    QString addressError;
    if (!parseAddress(&host, &port, &addressError)) {
        setNotice(addressError);
        m_address->setFocus();
        return;
    }
    const QString user = m_user->text().trimmed();
    const QString pass = m_pass->text();
    if (user.isEmpty() || pass.isEmpty()) {
        setNotice(QStringLiteral("Enter username and password."));
        if (user.isEmpty()) {
            m_user->setFocus();
        } else {
            m_pass->setFocus();
        }
        return;
    }
    m_error->hide();
    setBusy(true);
    emit loginRequested(host, port, user, pass);
}
