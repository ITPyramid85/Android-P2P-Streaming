#pragma once

#include <QByteArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QString>

namespace AuthProtocol {

constexpr quint16 kDefaultPort = 516;

inline QByteArray toLine(const QJsonObject &object)
{
    return QJsonDocument(object).toJson(QJsonDocument::Compact) + '\n';
}

inline QByteArray loginRequest(const QString &username, const QString &password)
{
    return toLine({
        {QStringLiteral("type"), QStringLiteral("login")},
        {QStringLiteral("username"), username},
        {QStringLiteral("password"), password},
    });
}

inline QByteArray pingRequest()
{
    return toLine({{QStringLiteral("type"), QStringLiteral("ping")}});
}

inline QByteArray okReply()
{
    return toLine({{QStringLiteral("type"), QStringLiteral("ok")}});
}

inline QByteArray errorReply(const QString &message)
{
    return toLine({
        {QStringLiteral("type"), QStringLiteral("error")},
        {QStringLiteral("message"), message},
    });
}

inline QByteArray revokeReply()
{
    return toLine({{QStringLiteral("type"), QStringLiteral("revoke")}});
}

inline QJsonObject parseLine(const QByteArray &line)
{
    QJsonParseError err;
    const QJsonDocument doc = QJsonDocument::fromJson(line, &err);
    if (err.error != QJsonParseError::NoError || !doc.isObject()) {
        return {};
    }
    return doc.object();
}

} // namespace AuthProtocol
