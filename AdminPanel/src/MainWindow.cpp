#include "MainWindow.h"
#include "AuthProtocol.h"
#include "AuthServer.h"
#include "UserDatabase.h"

#include <QAbstractItemView>
#include <QDialog>
#include <QFormLayout>
#include <QHeaderView>
#include <QHBoxLayout>
#include <QLabel>
#include <QLineEdit>
#include <QMessageBox>
#include <QPushButton>
#include <QStatusBar>
#include <QTableWidget>
#include <QVBoxLayout>
#include <QWidget>

namespace {
QString selectedUsername(QTableWidget *table)
{
    const auto items = table->selectedItems();
    if (items.isEmpty()) {
        return {};
    }
    const int row = items.first()->row();
    QTableWidgetItem *cell = table->item(row, 0);
    return cell ? cell->text() : QString();
}
}

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
    , m_auth(new AuthServer(this))
{
    setWindowTitle(QStringLiteral("AdminPanel"));
    resize(720, 480);
    setupUi();
    refreshUsers();

    if (!m_auth->start(AuthProtocol::kDefaultPort)) {
        QMessageBox::critical(
            this,
            QStringLiteral("AdminPanel"),
            QStringLiteral("Cannot listen for RemoteController sign-in on port %1:\n%2")
                .arg(AuthProtocol::kDefaultPort)
                .arg(m_auth->lastError()));
        statusBar()->showMessage(QStringLiteral("RemoteController sign-in is not listening"));
    } else {
        statusBar()->showMessage(
            QStringLiteral("RemoteController sign-in port %1").arg(m_auth->port()));
    }
}

void MainWindow::setupUi()
{
    auto *central = new QWidget(this);
    auto *layout = new QVBoxLayout(central);
    layout->setContentsMargins(16, 16, 16, 16);
    layout->setSpacing(10);

    auto *title = new QLabel(QStringLiteral("RemoteController users"), this);
    title->setObjectName(QStringLiteral("loginTitle"));

    auto *hint = new QLabel(
        QStringLiteral("Create accounts for RemoteController. RemoteController connects to this "
                       "PC by IP and port, then sends username and password. Deleting a signed-in "
                       "user stops that controller from listening and from talking to mobile clients."),
        this);
    hint->setWordWrap(true);

    m_table = new QTableWidget(this);
    m_table->setObjectName(QStringLiteral("clientTable"));
    m_table->setColumnCount(2);
    m_table->setHorizontalHeaderLabels({
        QStringLiteral("Username"),
        QStringLiteral("Created"),
    });
    m_table->setSelectionBehavior(QAbstractItemView::SelectRows);
    m_table->setSelectionMode(QAbstractItemView::SingleSelection);
    m_table->setEditTriggers(QAbstractItemView::NoEditTriggers);
    m_table->setAlternatingRowColors(true);
    m_table->verticalHeader()->setVisible(false);
    m_table->horizontalHeader()->setStretchLastSection(true);
    m_table->horizontalHeader()->setSectionResizeMode(0, QHeaderView::Stretch);
    m_table->setColumnWidth(1, 200);

    auto *addBtn = new QPushButton(QStringLiteral("Add user"), this);
    addBtn->setObjectName(QStringLiteral("loginButton"));
    auto *deleteBtn = new QPushButton(QStringLiteral("Delete user"), this);
    auto *refreshBtn = new QPushButton(QStringLiteral("Refresh"), this);
    auto *buttons = new QHBoxLayout();
    buttons->addWidget(addBtn);
    buttons->addWidget(deleteBtn);
    buttons->addStretch(1);
    buttons->addWidget(refreshBtn);

    layout->addWidget(title);
    layout->addWidget(hint);
    layout->addWidget(m_table, 1);
    layout->addLayout(buttons);
    setCentralWidget(central);

    connect(addBtn, &QPushButton::clicked, this, &MainWindow::addUser);
    connect(deleteBtn, &QPushButton::clicked, this, &MainWindow::deleteSelectedUser);
    connect(refreshBtn, &QPushButton::clicked, this, &MainWindow::refreshUsers);
}

void MainWindow::refreshUsers()
{
    auto &db = UserDatabase::instance();
    if (!db.isOpen() && !db.open()) {
        QMessageBox::critical(this, QStringLiteral("AdminPanel"),
                              QStringLiteral("Cannot open database:\n%1").arg(db.lastError()));
        return;
    }
    const auto users = db.listUsers();
    m_table->setRowCount(users.size());
    for (int i = 0; i < users.size(); ++i) {
        m_table->setItem(i, 0, new QTableWidgetItem(users.at(i).username));
        m_table->setItem(i, 1, new QTableWidgetItem(users.at(i).createdAt));
    }
}

void MainWindow::addUser()
{
    QDialog dialog(this);
    dialog.setWindowTitle(QStringLiteral("Add RemoteController user"));
    dialog.setFixedWidth(380);
    auto *form = new QFormLayout(&dialog);
    auto *user = new QLineEdit(&dialog);
    auto *pass = new QLineEdit(&dialog);
    auto *confirm = new QLineEdit(&dialog);
    pass->setEchoMode(QLineEdit::Password);
    confirm->setEchoMode(QLineEdit::Password);
    form->addRow(QStringLiteral("Username"), user);
    form->addRow(QStringLiteral("Password"), pass);
    form->addRow(QStringLiteral("Confirm"), confirm);
    auto *ok = new QPushButton(QStringLiteral("Create"), &dialog);
    ok->setObjectName(QStringLiteral("loginButton"));
    ok->setDefault(true);
    form->addRow(ok);
    connect(ok, &QPushButton::clicked, &dialog, [&] {
        if (user->text().trimmed().isEmpty() || pass->text().isEmpty()) {
            QMessageBox::warning(&dialog, QStringLiteral("AdminPanel"),
                                 QStringLiteral("Username and password are required."));
            return;
        }
        if (pass->text() != confirm->text()) {
            QMessageBox::warning(&dialog, QStringLiteral("AdminPanel"),
                                 QStringLiteral("Passwords do not match."));
            return;
        }
        dialog.accept();
    });
    if (dialog.exec() != QDialog::Accepted) {
        return;
    }
    auto &db = UserDatabase::instance();
    if (!db.addUser(user->text(), pass->text())) {
        QMessageBox::warning(this, QStringLiteral("AdminPanel"),
                             QStringLiteral("Could not create user:\n%1").arg(db.lastError()));
        return;
    }
    refreshUsers();
}

void MainWindow::deleteSelectedUser()
{
    const QString name = selectedUsername(m_table);
    if (name.isEmpty()) {
        QMessageBox::information(this, QStringLiteral("AdminPanel"),
                                 QStringLiteral("Select a user to delete."));
        return;
    }
    const auto answer = QMessageBox::question(
        this,
        QStringLiteral("Delete user"),
        QStringLiteral("Delete \"%1\"?\nRemoteController will stop listening and "
                       "close mobile sockets if this account is signed in.")
            .arg(name));
    if (answer != QMessageBox::Yes) {
        return;
    }
    auto &db = UserDatabase::instance();
    if (!db.deleteUser(name)) {
        QMessageBox::warning(this, QStringLiteral("AdminPanel"),
                             QStringLiteral("Could not delete user:\n%1").arg(db.lastError()));
        return;
    }
    if (m_auth) {
        m_auth->revokeUser(name);
    }
    refreshUsers();
}
