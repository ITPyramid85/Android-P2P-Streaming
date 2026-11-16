#pragma once

#include "ClientHandler.h"
#include "TcpServer.h"

#include <QMainWindow>
#include <QHash>
#include <QNetworkInformation>
#include <QPointer>
#include <QSet>

class ScreenMonitorDialog;

class QLabel;
class QTableWidget;
class QTextEdit;
class QSplitter;

class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);
    ~MainWindow() override;

    void lockSession();
    void setListeningEnabled(bool enabled);

private slots:
    void onClientRegistered(const ClientInfo &info);
    void onClientDisconnected(const QString &clientId);
    void onClientBusy(const QString &clientId);
    void onClientRemoved(const QString &clientId);
    void onClientMessage(const QString &clientId, const QString &message);
    void onServerLog(const QString &message, bool highlight);
    void onClientRowActivated();
    void onClientTableContextMenu(const QPoint &pos);
    void openScreenMonitoring();
    void closeScreenMonitoring();
    void onAgentClaimed(const QString &agentId, qint64 remainMs, const QString &controlKey);
    void onAgentClaimDenied(const QString &agentId, const QString &reason);

private:
    void setupUi();
    void watchInternet();
    void applyInternetReachability(QNetworkInformation::Reachability reach);
    void showMonitorFor(const QString &agentId, qint64 remainMs);
    void appendLog(const QString &message, bool highlight = false);
    int findRowByClientId(const QString &clientId) const;
    int findRowByPhone(const QString &phone) const;
    void rebuildRowIndex();
    void sortClientsNewestFirst();
    void closeStreamingWindow(const QString &clientId);
    void setStatusIcon(int row, bool online);
    QString selectedClientId() const;
    ClientHandler *selectedHandler() const;

    QSplitter *m_splitter = nullptr;
    QLabel *m_offlineBanner = nullptr;
    QTableWidget *m_table = nullptr;
    QTextEdit *m_log = nullptr;
    bool m_internetOffline = false;
    TcpServer *m_server = nullptr;
    QHash<QString, int> m_rowByClientId;
    // Mobiles whose preview window is open here. They stay in the table's model so a
    // re-registration still updates them, but their row is hidden.
    QSet<QString> m_monitoredIds;
    QHash<QString, QPointer<ScreenMonitorDialog>> m_monitors;
    // Mobiles hidden because another operator has the preview open.
    QSet<QString> m_busyIds;
    // Claims asked of the relay and not yet answered. Nothing is attached or shown
    // until the relay grants one, so two operators cannot both open the same mobile.
    QSet<QString> m_claimPending;
    // Stop was pressed for these mobiles. Releasing them ends sharing instead of
    // putting the row back on offer.
    QSet<QString> m_endShareIds;
    // The phone (or the relay) already ended the session. Closing the window
    // that was showing it must not hand the phone back onto the list.
    QSet<QString> m_remoteEndedIds;
    // The hour elapsed. The relay consumes the offer; closing the window must
    // not hand the phone back onto the list.
    QSet<QString> m_hourEndedIds;
};
