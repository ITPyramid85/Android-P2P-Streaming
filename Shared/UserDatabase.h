#pragma once

#include <QString>
#include <QVector>

struct ControllerUser
{
    QString username;
    QString createdAt;
};

class UserDatabase
{
public:
    static UserDatabase &instance();

    QString filePath() const { return m_path; }
    bool isOpen() const { return m_open; }
    QString lastError() const { return m_lastError; }

    bool open();
    bool addUser(const QString &username, const QString &password);
    bool deleteUser(const QString &username);
    bool authenticate(const QString &username, const QString &password);
    bool userExists(const QString &username);
    QVector<ControllerUser> listUsers();

    bool hasAdmin();
    bool authenticateAdmin(const QString &email, const QString &password);
    bool setAdmin(const QString &email, const QString &password);

private:
    UserDatabase() = default;
    QString hashPassword(const QString &username, const QString &password) const;

    QString m_path;
    QString m_lastError;
    bool m_open = false;
};
