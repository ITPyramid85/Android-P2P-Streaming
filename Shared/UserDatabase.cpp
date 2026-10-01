#include "UserDatabase.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QSqlDatabase>
#include <QSqlError>
#include <QSqlQuery>

namespace {
const char *kConnection = "mproject_controller_users";
const char *kDbFile = "controller_users.db";

QString adminPanelDirectory()
{
    const QString appDir = QCoreApplication::applicationDirPath();
    const QString appName = QFileInfo(QCoreApplication::applicationFilePath()).completeBaseName();
    if (appName.compare(QLatin1String("AdminPanel"), Qt::CaseInsensitive) == 0) {
        return appDir;
    }

    const QStringList guesses = {
        QDir(appDir).filePath(QStringLiteral("AdminPanel.exe")),
        QDir(appDir).absoluteFilePath(QStringLiteral("../../AdminPanel/build-rel/AdminPanel.exe")),
        QDir(appDir).absoluteFilePath(QStringLiteral("../../../AppService/AdminPanel/build-rel/AdminPanel.exe")),
    };
    for (const QString &guess : guesses) {
        const QFileInfo info(QDir::cleanPath(guess));
        if (info.exists()) {
            return info.absolutePath();
        }
    }
    return appDir;
}
}

UserDatabase &UserDatabase::instance()
{
    static UserDatabase db;
    return db;
}

bool UserDatabase::open()
{
    if (m_open) {
        return true;
    }
    m_lastError.clear();
    m_path = QDir(adminPanelDirectory()).filePath(QLatin1String(kDbFile));

    if (QSqlDatabase::contains(QLatin1String(kConnection))) {
        QSqlDatabase::removeDatabase(QLatin1String(kConnection));
    }
    QSqlDatabase db = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), QLatin1String(kConnection));
    db.setDatabaseName(m_path);
    if (!db.open()) {
        m_lastError = db.lastError().text();
        m_open = false;
        return false;
    }

    QSqlQuery pragma(db);
    pragma.exec(QStringLiteral("PRAGMA journal_mode=WAL"));
    pragma.exec(QStringLiteral("PRAGMA busy_timeout=4000"));

    QSqlQuery create(db);
    if (!create.exec(QStringLiteral(
            "CREATE TABLE IF NOT EXISTS users ("
            "username TEXT PRIMARY KEY COLLATE NOCASE,"
            "password_hash TEXT NOT NULL,"
            "created_at TEXT NOT NULL)"))) {
        m_lastError = create.lastError().text();
        m_open = false;
        return false;
    }
    if (!create.exec(QStringLiteral(
            "CREATE TABLE IF NOT EXISTS admins ("
            "email TEXT PRIMARY KEY COLLATE NOCASE,"
            "password_hash TEXT NOT NULL,"
            "created_at TEXT NOT NULL)"))) {
        m_lastError = create.lastError().text();
        m_open = false;
        return false;
    }
    m_open = true;
    return true;
}

QString UserDatabase::hashPassword(const QString &username, const QString &password) const
{
    const QByteArray raw = (username.trimmed().toLower() + QLatin1Char(':') + password).toUtf8();
    return QString::fromLatin1(QCryptographicHash::hash(raw, QCryptographicHash::Sha256).toHex());
}

bool UserDatabase::addUser(const QString &username, const QString &password)
{
    if (!m_open && !open()) {
        return false;
    }
    const QString name = username.trimmed();
    if (name.isEmpty() || password.isEmpty()) {
        m_lastError = QStringLiteral("Username and password are required.");
        return false;
    }
    QSqlQuery q(QSqlDatabase::database(QLatin1String(kConnection)));
    q.prepare(QStringLiteral(
        "INSERT INTO users(username, password_hash, created_at) VALUES(?, ?, ?)"));
    q.addBindValue(name);
    q.addBindValue(hashPassword(name, password));
    q.addBindValue(QDateTime::currentDateTime().toString(Qt::ISODate));
    if (!q.exec()) {
        m_lastError = q.lastError().text();
        return false;
    }
    return true;
}

bool UserDatabase::deleteUser(const QString &username)
{
    if (!m_open && !open()) {
        return false;
    }
    QSqlQuery q(QSqlDatabase::database(QLatin1String(kConnection)));
    q.prepare(QStringLiteral("DELETE FROM users WHERE username = ?"));
    q.addBindValue(username.trimmed());
    if (!q.exec()) {
        m_lastError = q.lastError().text();
        return false;
    }
    return q.numRowsAffected() > 0;
}

bool UserDatabase::authenticate(const QString &username, const QString &password)
{
    if (!m_open && !open()) {
        return false;
    }
    const QString name = username.trimmed();
    QSqlQuery q(QSqlDatabase::database(QLatin1String(kConnection)));
    q.prepare(QStringLiteral(
        "SELECT password_hash FROM users WHERE username = ? COLLATE NOCASE"));
    q.addBindValue(name);
    if (!q.exec() || !q.next()) {
        return false;
    }
    return q.value(0).toString() == hashPassword(name, password);
}

bool UserDatabase::userExists(const QString &username)
{
    if (!m_open && !open()) {
        return false;
    }
    QSqlQuery q(QSqlDatabase::database(QLatin1String(kConnection)));
    q.prepare(QStringLiteral(
        "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE LIMIT 1"));
    q.addBindValue(username.trimmed());
    return q.exec() && q.next();
}

QVector<ControllerUser> UserDatabase::listUsers()
{
    QVector<ControllerUser> users;
    if (!m_open && !open()) {
        return users;
    }
    QSqlQuery q(QSqlDatabase::database(QLatin1String(kConnection)));
    if (!q.exec(QStringLiteral(
            "SELECT username, created_at FROM users ORDER BY username COLLATE NOCASE"))) {
        m_lastError = q.lastError().text();
        return users;
    }
    while (q.next()) {
        ControllerUser row;
        row.username = q.value(0).toString();
        row.createdAt = q.value(1).toString();
        users.push_back(row);
    }
    return users;
}

namespace {
bool looksLikeEmail(const QString &email)
{
    const QString value = email.trimmed();
    const int at = value.indexOf(QLatin1Char('@'));
    if (at <= 0 || value.contains(QLatin1Char(' '))) {
        return false;
    }
    const int dot = value.indexOf(QLatin1Char('.'), at + 1);
    return dot > at + 1 && dot < value.size() - 1;
}
}

bool UserDatabase::hasAdmin()
{
    if (!m_open && !open()) {
        return false;
    }
    QSqlQuery q(QSqlDatabase::database(QLatin1String(kConnection)));
    if (!q.exec(QStringLiteral("SELECT 1 FROM admins LIMIT 1"))) {
        m_lastError = q.lastError().text();
        return false;
    }
    return q.next();
}

bool UserDatabase::authenticateAdmin(const QString &email, const QString &password)
{
    if (!m_open && !open()) {
        return false;
    }
    const QString name = email.trimmed();
    QSqlQuery q(QSqlDatabase::database(QLatin1String(kConnection)));
    q.prepare(QStringLiteral(
        "SELECT password_hash FROM admins WHERE email = ? COLLATE NOCASE"));
    q.addBindValue(name);
    if (!q.exec() || !q.next()) {
        return false;
    }
    return q.value(0).toString() == hashPassword(name, password);
}

bool UserDatabase::setAdmin(const QString &email, const QString &password)
{
    if (!m_open && !open()) {
        return false;
    }
    const QString name = email.trimmed();
    if (!looksLikeEmail(name) || password.isEmpty()) {
        m_lastError = QStringLiteral("A valid email and password are required.");
        return false;
    }
    QSqlDatabase db = QSqlDatabase::database(QLatin1String(kConnection));
    if (!db.transaction()) {
        m_lastError = db.lastError().text();
        return false;
    }
    QSqlQuery clear(db);
    if (!clear.exec(QStringLiteral("DELETE FROM admins"))) {
        m_lastError = clear.lastError().text();
        db.rollback();
        return false;
    }
    QSqlQuery insert(db);
    insert.prepare(QStringLiteral(
        "INSERT INTO admins(email, password_hash, created_at) VALUES(?, ?, ?)"));
    insert.addBindValue(name);
    insert.addBindValue(hashPassword(name, password));
    insert.addBindValue(QDateTime::currentDateTime().toString(Qt::ISODate));
    if (!insert.exec()) {
        m_lastError = insert.lastError().text();
        db.rollback();
        return false;
    }
    if (!db.commit()) {
        m_lastError = db.lastError().text();
        db.rollback();
        return false;
    }
    return true;
}
