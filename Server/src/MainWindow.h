#pragma once

#include "ClientHandler.h"
#include "TcpServer.h"

#include <QMainWindow>
#include <QHash>
#include <QSet>

class QTableWidget;
class QTextEdit;
class QSplitter;

class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);
    ~MainWindow() override = default;

    void lockSession();
    void setListeningEnabled(bool enabled);

private slots:
    void onClientRegistered(const ClientInfo &info);
    void onClientDisconnected(const QString &clientId);
    void onClientMessage(const QString &clientId, const QString &message);
    void onServerLog(const QString &message, bool highlight);
    void onClientRowActivated();
    void onClientTableContextMenu(const QPoint &pos);
    void openScreenMonitoring();
    void closeScreenMonitoring();

private:
    void setupUi();
    void appendLog(const QString &message, bool highlight = false);
    int findRowByClientId(const QString &clientId) const;
    int findRowByPhone(const QString &phone) const;
    void rebuildRowIndex();
    void setStatusIcon(int row, bool online);
    QString selectedClientId() const;
    ClientHandler *selectedHandler() const;

    QSplitter *m_splitter = nullptr;
    QTableWidget *m_table = nullptr;
    QTextEdit *m_log = nullptr;
    TcpServer *m_server = nullptr;
    QHash<QString, int> m_rowByClientId;
    // Mobiles whose preview window is open. They stay in the table's model so a
    // re-registration still updates them, but their row is hidden.
    QSet<QString> m_monitoredIds;
};
