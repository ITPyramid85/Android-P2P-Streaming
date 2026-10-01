#include "AdminLoginDialog.h"
#include "UserDatabase.h"

#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QSettings>
#include <QVBoxLayout>

AdminLoginDialog::AdminLoginDialog(QWidget *parent)
    : QDialog(parent)
{
    setWindowTitle(QStringLiteral("AdminPanel"));
    setObjectName(QStringLiteral("loginDialog"));
    setModal(true);
    setFixedSize(420, 500);
    setWindowFlag(Qt::WindowContextHelpButtonHint, false);

    m_title = new QLabel(this);
    m_title->setObjectName(QStringLiteral("loginTitle"));

    m_hint = new QLabel(this);
    m_hint->setWordWrap(true);

    QSettings settings;
    auto *emailLabel = new QLabel(QStringLiteral("Email"), this);
    m_email = new QLineEdit(this);
    m_email->setPlaceholderText(QStringLiteral("name@example.com"));
    m_email->setClearButtonEnabled(true);
    m_email->setText(settings.value(QStringLiteral("adminEmail")).toString());

    auto *passLabel = new QLabel(QStringLiteral("Password"), this);
    m_pass = new QLineEdit(this);
    m_pass->setPlaceholderText(QStringLiteral("Password"));
    m_pass->setEchoMode(QLineEdit::Password);
    m_pass->setClearButtonEnabled(true);

    m_confirmLabel = new QLabel(QStringLiteral("Confirm password"), this);
    m_confirm = new QLineEdit(this);
    m_confirm->setPlaceholderText(QStringLiteral("Confirm password"));
    m_confirm->setEchoMode(QLineEdit::Password);
    m_confirm->setClearButtonEnabled(true);

    m_confirmBlock = new QWidget(this);
    auto *confirmLayout = new QVBoxLayout(m_confirmBlock);
    confirmLayout->setContentsMargins(0, 6, 0, 0);
    confirmLayout->setSpacing(8);
    confirmLayout->addWidget(m_confirmLabel);
    confirmLayout->addWidget(m_confirm);

    m_error = new QLabel(this);
    m_error->setObjectName(QStringLiteral("loginError"));
    m_error->setWordWrap(true);
    m_error->hide();

    m_primary = new QPushButton(this);
    m_primary->setObjectName(QStringLiteral("loginButton"));
    m_primary->setDefault(true);
    m_primary->setCursor(Qt::PointingHandCursor);

    m_forgot = new QPushButton(QStringLiteral("Forgot password?"), this);
    m_forgot->setFlat(true);
    m_forgot->setCursor(Qt::PointingHandCursor);
    m_forgot->setStyleSheet(QStringLiteral(
        "QPushButton { color: #5B8DEF; border: none; text-align: left; padding: 0; }"));

    m_back = new QPushButton(QStringLiteral("Back to sign in"), this);
    m_back->setFlat(true);
    m_back->setCursor(Qt::PointingHandCursor);
    m_back->setStyleSheet(QStringLiteral(
        "QPushButton { color: #5B8DEF; border: none; text-align: left; padding: 0; }"));

    auto *layout = new QVBoxLayout(this);
    layout->setContentsMargins(36, 28, 36, 28);
    layout->setSpacing(8);
    layout->addWidget(m_title);
    layout->addWidget(m_hint);
    layout->addSpacing(8);
    layout->addWidget(emailLabel);
    layout->addWidget(m_email);
    layout->addSpacing(6);
    layout->addWidget(passLabel);
    layout->addWidget(m_pass);
    layout->addWidget(m_confirmBlock);
    layout->addWidget(m_error);
    layout->addStretch(1);
    layout->addWidget(m_forgot, 0, Qt::AlignLeft);
    layout->addWidget(m_back, 0, Qt::AlignLeft);
    layout->addWidget(m_primary);

    connect(m_primary, &QPushButton::clicked, this, [this] {
        if (m_resetMode) {
            trySaveAccount();
        } else {
            trySignIn();
        }
    });
    connect(m_email, &QLineEdit::returnPressed, m_primary, &QPushButton::click);
    connect(m_pass, &QLineEdit::returnPressed, m_primary, &QPushButton::click);
    connect(m_confirm, &QLineEdit::returnPressed, m_primary, &QPushButton::click);
    connect(m_forgot, &QPushButton::clicked, this, &AdminLoginDialog::showReset);
    connect(m_back, &QPushButton::clicked, this, &AdminLoginDialog::showSignIn);

    applyMode(!UserDatabase::instance().hasAdmin());
    if (m_email->text().trimmed().isEmpty()) {
        m_email->setFocus();
    } else {
        m_pass->setFocus();
    }
}

void AdminLoginDialog::applyMode(bool resetMode)
{
    m_resetMode = resetMode;
    const bool firstCreate = resetMode && !UserDatabase::instance().hasAdmin();
    if (resetMode) {
        m_title->setText(firstCreate ? QStringLiteral("Create account")
                                     : QStringLiteral("Reset account"));
        m_hint->setText(firstCreate
                            ? QStringLiteral("Create the AdminPanel email and password. They are stored in the same database as RemoteController users.")
                            : QStringLiteral("Set a new AdminPanel email and password. This replaces the previous login."));
        m_primary->setText(firstCreate ? QStringLiteral("Create") : QStringLiteral("Reset"));
        m_confirmBlock->show();
        m_forgot->setVisible(false);
        m_back->setVisible(!firstCreate);
        m_pass->clear();
        m_confirm->clear();
    } else {
        m_title->setText(QStringLiteral("Sign in"));
        m_hint->setText(QStringLiteral("Sign in with the AdminPanel email and password."));
        m_primary->setText(QStringLiteral("Sign in"));
        m_confirmBlock->hide();
        m_forgot->setVisible(true);
        m_back->setVisible(false);
        m_pass->clear();
        m_confirm->clear();
    }
}

void AdminLoginDialog::setNotice(const QString &text)
{
    if (text.isEmpty()) {
        m_error->hide();
        return;
    }
    m_error->setText(text);
    m_error->show();
}

void AdminLoginDialog::showReset()
{
    setNotice({});
    applyMode(true);
    m_email->setFocus();
}

void AdminLoginDialog::showSignIn()
{
    setNotice({});
    applyMode(false);
    m_pass->setFocus();
}

void AdminLoginDialog::trySignIn()
{
    const QString email = m_email->text().trimmed();
    const QString pass = m_pass->text();
    if (email.isEmpty() || pass.isEmpty()) {
        setNotice(QStringLiteral("Enter email and password."));
        return;
    }
    if (!UserDatabase::instance().authenticateAdmin(email, pass)) {
        setNotice(QStringLiteral("Incorrect email or password."));
        m_pass->selectAll();
        m_pass->setFocus();
        return;
    }
    QSettings settings;
    settings.setValue(QStringLiteral("adminEmail"), email);
    accept();
}

void AdminLoginDialog::trySaveAccount()
{
    const QString email = m_email->text().trimmed();
    const QString pass = m_pass->text();
    const QString confirm = m_confirm->text();
    if (pass != confirm) {
        setNotice(QStringLiteral("Passwords do not match."));
        m_confirm->selectAll();
        m_confirm->setFocus();
        return;
    }
    const bool firstCreate = !UserDatabase::instance().hasAdmin();
    if (!UserDatabase::instance().setAdmin(email, pass)) {
        setNotice(UserDatabase::instance().lastError());
        return;
    }
    QSettings settings;
    settings.setValue(QStringLiteral("adminEmail"), email);
    if (firstCreate) {
        accept();
        return;
    }
    applyMode(false);
    setNotice(QStringLiteral("Account reset. Sign in with the new email and password."));
    m_pass->setFocus();
}
