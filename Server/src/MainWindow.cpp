#include "MainWindow.h"
#include "FeatureDialogs.h"

#include <QAbstractItemView>
#include <QAction>
#include <QFrame>
#include <QFont>
#include <QGuiApplication>
#include <QHeaderView>
#include <QLabel>
#include <QMenu>
#include <QMessageBox>
#include <QNetworkInformation>
#include <QModelIndex>
#include <QPoint>
#include <QSplitter>
#include <QTableWidget>
#include <QTextEdit>
#include <QTextCharFormat>
#include <QTextCursor>
#include <QVBoxLayout>
#include <QWidget>
#include <QColor>
#include <QDialog>
#include <QDateTime>
#include <QIcon>
#include <QItemSelectionModel>
#include <QPainter>
#include <QPen>
#include <QPixmap>
#include <QSize>

namespace {
constexpr int kColNo = 0;
constexpr int kColId = 1;
constexpr int kColDevice = 2;
constexpr int kColStatus = 7;
constexpr int kPhoneRole = Qt::UserRole + 1;

QIcon makeStatusIcon(bool online)
{
    QPixmap pm(20, 20);
    pm.fill(Qt::transparent);
    QPainter p(&pm);
    p.setRenderHint(QPainter::Antialiasing, true);
    const QColor fill = online ? QColor(0x2E, 0xCC, 0x71) : QColor(0xE7, 0x4C, 0x3C);
    const QColor rim = online ? QColor(0x1E, 0x8A, 0x4C) : QColor(0xC0, 0x39, 0x2B);
    p.setPen(QPen(rim, 1.2));
    p.setBrush(fill);
    p.drawEllipse(QRectF(3.0, 3.0, 14.0, 14.0));
    p.setPen(Qt::NoPen);
    p.setBrush(QColor(255, 255, 255, 90));
    p.drawEllipse(QRectF(6.0, 5.5, 6.0, 4.0));
    return QIcon(pm);
}
}

MainWindow::~MainWindow() = default;

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
{
    setWindowTitle(QStringLiteral("Remote Android Devices"));
    resize(1280, 720);

    setupUi();

    m_server = new TcpServer(this);
    connect(m_server, &TcpServer::clientRegistered, this, &MainWindow::onClientRegistered);
    connect(m_server, &TcpServer::clientDisconnected, this, &MainWindow::onClientDisconnected);
    connect(m_server, &TcpServer::clientBusy, this, &MainWindow::onClientBusy);
    connect(m_server, &TcpServer::clientRemoved, this, &MainWindow::onClientRemoved);
    connect(m_server, &TcpServer::agentClaimed, this, &MainWindow::onAgentClaimed);
    connect(m_server, &TcpServer::agentClaimDenied, this, &MainWindow::onAgentClaimDenied);
    connect(m_server, &TcpServer::clientMessage, this, &MainWindow::onClientMessage);
    connect(m_server, &TcpServer::logMessage, this, &MainWindow::onServerLog);
    watchInternet();
}

void MainWindow::lockSession()
{
    const QList<QDialog *> dialogs = findChildren<QDialog *>();
    for (QDialog *dialog : dialogs) {
        dialog->close();
    }
    hide();
}

void MainWindow::setListeningEnabled(bool enabled)
{
    if (!m_server) {
        return;
    }
    if (enabled) {
        if (!m_server->isListening()) {
            m_server->start(80);
        }
        return;
    }
    m_server->stop();
    appendLog(QStringLiteral("Listening and client sockets stopped (controller user removed)."), true);
}

void MainWindow::setupUi()
{
    auto *central = new QWidget(this);
    auto *layout = new QVBoxLayout(central);
    layout->setContentsMargins(12, 12, 12, 12);
    layout->setSpacing(10);

    m_offlineBanner = new QLabel(QStringLiteral("Not Connected to the Internet"), central);
    m_offlineBanner->setAlignment(Qt::AlignCenter);
    m_offlineBanner->setWordWrap(true);
    m_offlineBanner->setStyleSheet(QStringLiteral(
        "QLabel { background: #FDECEC; color: #9B1C1C; border: 1px solid #E6B0B0;"
        " border-radius: 8px; padding: 10px 14px; font-weight: 700; font-size: 15px; }"));
    m_offlineBanner->setVisible(false);
    layout->addWidget(m_offlineBanner);

    m_splitter = new QSplitter(Qt::Vertical, central);

    m_table = new QTableWidget(m_splitter);
    m_table->setObjectName(QStringLiteral("clientTable"));
    m_table->setColumnCount(8);
    m_table->setHorizontalHeaderLabels({
        QStringLiteral("No."),
        QStringLiteral("Screen ID"),
        QStringLiteral("Device Name"),
        QStringLiteral("Model"),
        QStringLiteral("OS"),
        QStringLiteral("IP Address"),
        QStringLiteral("Connected At"),
        QStringLiteral("Status"),
    });
    m_table->setSelectionBehavior(QAbstractItemView::SelectRows);
    m_table->setSelectionMode(QAbstractItemView::SingleSelection);
    m_table->setEditTriggers(QAbstractItemView::NoEditTriggers);
    m_table->setAlternatingRowColors(true);
    m_table->setContextMenuPolicy(Qt::CustomContextMenu);
    m_table->verticalHeader()->setVisible(false);
    m_table->setShowGrid(true);
    m_table->setFrameShape(QFrame::StyledPanel);
    m_table->setFocusPolicy(Qt::StrongFocus);
    m_table->setIconSize(QSize(18, 18));
    auto *header = m_table->horizontalHeader();
    header->setHighlightSections(false);
    header->setDefaultAlignment(Qt::AlignLeft | Qt::AlignVCenter);
    header->setTextElideMode(Qt::ElideNone);
    header->setMinimumHeight(38);
    header->setMinimumSectionSize(48);
    header->setSectionResizeMode(QHeaderView::Interactive);
    header->setSectionResizeMode(kColNo, QHeaderView::Fixed);
    header->setSectionResizeMode(kColId, QHeaderView::Interactive);
    header->setSectionResizeMode(kColDevice, QHeaderView::Stretch);
    header->setSectionResizeMode(kColStatus, QHeaderView::Fixed);
    header->setStretchLastSection(false);
    m_table->setColumnWidth(kColNo, 56);
    m_table->setColumnWidth(kColId, 160);
    m_table->setColumnWidth(kColDevice, 200);
    m_table->setColumnWidth(3, 160);
    m_table->setColumnWidth(4, 140);
    m_table->setColumnWidth(5, 160);
    m_table->setColumnWidth(6, 158);
    m_table->setColumnWidth(kColStatus, 88);
    m_table->verticalHeader()->setDefaultSectionSize(34);
    // Order is applied in code from the Connected At column. Leaving header
    // clicks unsorted keeps a status update from shuffling the rows.
    header->setSortIndicatorShown(true);
    header->setSortIndicator(6, Qt::DescendingOrder);
    connect(m_table, &QTableWidget::cellPressed, this, [this](int, int) {
        if (QGuiApplication::mouseButtons() == Qt::RightButton) {
            return;
        }
        onClientRowActivated();
    });
    connect(m_table, &QTableWidget::customContextMenuRequested,
            this, &MainWindow::onClientTableContextMenu);

    m_log = new QTextEdit(m_splitter);
    m_log->setObjectName(QStringLiteral("eventLog"));
    m_log->setReadOnly(true);
    m_log->setFrameShape(QFrame::NoFrame);
    m_log->setFont(QFont(QStringLiteral("Cascadia Mono"), 10));
    if (m_log->font().family() != QLatin1String("Cascadia Mono")) {
        m_log->setFont(QFont(QStringLiteral("Consolas"), 10));
    }

    m_splitter->addWidget(m_table);
    m_splitter->addWidget(m_log);
    m_splitter->setStretchFactor(0, 3);
    m_splitter->setStretchFactor(1, 2);
    m_splitter->setSizes({420, 220});

    layout->addWidget(m_splitter);
    setCentralWidget(central);
}

void MainWindow::watchInternet()
{
    if (!QNetworkInformation::loadDefaultBackend()) {
        QNetworkInformation::loadBackendByFeatures(QNetworkInformation::Feature::Reachability);
    }
    QNetworkInformation *net = QNetworkInformation::instance();
    if (!net || !net->supports(QNetworkInformation::Feature::Reachability)) {
        return;
    }
    connect(net, &QNetworkInformation::reachabilityChanged,
            this, &MainWindow::applyInternetReachability);
    applyInternetReachability(net->reachability());
}

void MainWindow::applyInternetReachability(QNetworkInformation::Reachability reach)
{
    if (reach == QNetworkInformation::Reachability::Unknown) {
        return;
    }
    const bool offline = reach != QNetworkInformation::Reachability::Online;
    if (offline == m_internetOffline) {
        return;
    }
    m_internetOffline = offline;
    if (m_offlineBanner) {
        m_offlineBanner->setVisible(offline);
    }
    const auto dialogs = findChildren<ScreenMonitorDialog *>();
    for (ScreenMonitorDialog *dialog : dialogs) {
        if (dialog) {
            dialog->setInternetOffline(offline);
        }
    }
    if (offline) {
        appendLog(QStringLiteral("Not Connected to the Internet"), true);
    }
}

void MainWindow::appendLog(const QString &message, bool highlight)
{
    QTextCharFormat format;
    format.setForeground(highlight ? QColor(0x8A, 0xB4, 0xF8) : QColor(0xC5, 0xC8, 0xCE));

    QTextCursor cursor = m_log->textCursor();
    cursor.movePosition(QTextCursor::End);
    cursor.insertText(message + QLatin1Char('\n'), format);
    m_log->setTextCursor(cursor);
    m_log->ensureCursorVisible();
}

void MainWindow::onServerLog(const QString &message, bool highlight)
{
    appendLog(message, highlight);
}

void MainWindow::onClientRegistered(const ClientInfo &info)
{
    m_busyIds.remove(info.id);
    // Drop any leftover rows for the same phone/device so the table never duplicates.
    for (int row = m_table->rowCount() - 1; row >= 0; --row) {
        const QTableWidgetItem *phoneItem = m_table->item(row, 0);
        if (!phoneItem) {
            continue;
        }
        const QString existingId = phoneItem->data(Qt::UserRole).toString();
        const QString existingPhone = phoneItem->data(kPhoneRole).toString().trimmed();
        const bool sameId = existingId == info.id;
        const bool samePhone = !info.phoneNumber.isEmpty()
            && existingPhone == info.phoneNumber
            && existingPhone != QLatin1String("Unknown");
        if (!sameId && samePhone) {
            m_table->removeRow(row);
        }
    }
    rebuildRowIndex();

    int row = findRowByClientId(info.id);
    if (row < 0) {
        row = findRowByPhone(info.phoneNumber);
    }
    const bool inserted = row < 0;
    QString previousTime;
    if (!inserted) {
        if (const QTableWidgetItem *timeItem = m_table->item(row, 6)) {
            previousTime = timeItem->text();
        }
    }
    if (inserted) {
        row = m_table->rowCount();
        m_table->insertRow(row);
    }

    auto setCell = [this, row](int column, const QString &text, const QVariant &userData = {}) {
        auto *item = m_table->item(row, column);
        if (!item) {
            item = new QTableWidgetItem;
            m_table->setItem(row, column, item);
        }
        item->setText(text);
        if (userData.isValid()) {
            item->setData(Qt::UserRole, userData);
        }
    };

    setCell(kColNo, QString::number(row + 1), info.id);
    if (auto *noItem = m_table->item(row, kColNo)) {
        noItem->setData(kPhoneRole, info.phoneNumber);
        noItem->setTextAlignment(Qt::AlignCenter);
    }
    setCell(kColId, info.phoneNumber);
    setCell(kColDevice, info.deviceName);
    setCell(3, info.model);
    setCell(4, info.osVersion);
    setCell(5, info.ipAddress);
    setCell(6, info.connectedAt);
    setStatusIcon(row, true);
    // Newest connection time first. A row already in the list keeps its place
    // until its time actually changes, so a refresh does not reshuffle ties.
    if (inserted || previousTime != info.connectedAt) {
        sortClientsNewestFirst();
    }
    rebuildRowIndex();
}

void MainWindow::sortClientsNewestFirst()
{
    m_table->sortItems(6, Qt::DescendingOrder);
    if (QHeaderView *header = m_table->horizontalHeader()) {
        header->setSortIndicator(6, Qt::DescendingOrder);
    }
}

void MainWindow::closeStreamingWindow(const QString &clientId)
{
    if (clientId.isEmpty()) {
        return;
    }
    ScreenMonitorDialog *dialog = m_monitors.value(clientId);
    if (!dialog) {
        const auto dialogs = findChildren<ScreenMonitorDialog *>();
        for (ScreenMonitorDialog *existing : dialogs) {
            if (existing && existing->agentId() == clientId) {
                dialog = existing;
                break;
            }
        }
    }
    if (!dialog) {
        return;
    }
    // Set before close(). destroyed() must not hand the phone back: that
    // republish is what opened the streaming view again.
    m_remoteEndedIds.insert(clientId);
    dialog->abandon();
    if (m_server) {
        m_server->detachAgent(clientId);
    }
}

void MainWindow::onClientDisconnected(const QString &clientId)
{
    closeStreamingWindow(clientId);
    m_claimPending.remove(clientId);
    const bool wasBusy = m_busyIds.remove(clientId);
    const int row = findRowByClientId(clientId);
    if (row < 0) {
        return;
    }
    setStatusIcon(row, false);
    if (wasBusy) {
        rebuildRowIndex();
    }
}

void MainWindow::onClientRemoved(const QString &clientId)
{
    m_busyIds.remove(clientId);
    m_monitoredIds.remove(clientId);
    m_claimPending.remove(clientId);
    closeStreamingWindow(clientId);
    const int row = findRowByClientId(clientId);
    if (row >= 0) {
        m_table->removeRow(row);
    }
    rebuildRowIndex();
}

void MainWindow::onClientBusy(const QString &clientId)
{
    if (clientId.isEmpty() || m_busyIds.contains(clientId)) {
        return;
    }
    // Joined after the preview opened: there is no row here to hide.
    if (findRowByClientId(clientId) < 0) {
        return;
    }
    m_busyIds.insert(clientId);
    rebuildRowIndex();
    appendLog(QStringLiteral("Mobile in use elsewhere: %1").arg(clientId));
}

void MainWindow::setStatusIcon(int row, bool online)
{
    auto *item = m_table->item(row, kColStatus);
    if (!item) {
        item = new QTableWidgetItem;
        m_table->setItem(row, kColStatus, item);
    }
    item->setText(QString());
    item->setIcon(makeStatusIcon(online));
    item->setTextAlignment(Qt::AlignCenter);
    item->setToolTip(online ? QStringLiteral("Online") : QStringLiteral("Offline"));
    item->setData(Qt::UserRole, online);
}

void MainWindow::onClientMessage(const QString &clientId, const QString &message)
{
    if (message.startsWith(QLatin1String("[screen]"))) {
        return;
    }
    const int row = findRowByClientId(clientId);
    QString phone = clientId.left(8);
    if (row >= 0 && m_table->item(row, kColNo)) {
        const QString stored = m_table->item(row, kColNo)->data(kPhoneRole).toString();
        if (!stored.isEmpty()) {
            phone = stored;
        }
    }
    appendLog(QStringLiteral("%1 [%2] %3")
                  .arg(QDateTime::currentDateTime().toString(QStringLiteral("ddd MMM dd HH:mm:ss t yyyy")),
                       phone,
                       message));
}

int MainWindow::findRowByClientId(const QString &clientId) const
{
    if (m_rowByClientId.contains(clientId)) {
        const int row = m_rowByClientId.value(clientId);
        if (row >= 0 && row < m_table->rowCount()) {
            const QTableWidgetItem *item = m_table->item(row, 0);
            if (item && item->data(Qt::UserRole).toString() == clientId) {
                return row;
            }
        }
    }
    for (int row = 0; row < m_table->rowCount(); ++row) {
        const QTableWidgetItem *item = m_table->item(row, 0);
        if (item && item->data(Qt::UserRole).toString() == clientId) {
            return row;
        }
    }
    return -1;
}

int MainWindow::findRowByPhone(const QString &phone) const
{
    const QString key = phone.trimmed();
    if (key.isEmpty() || key == QLatin1String("Unknown")) {
        return -1;
    }
    for (int row = 0; row < m_table->rowCount(); ++row) {
        const QTableWidgetItem *item = m_table->item(row, 0);
        if (item && item->data(kPhoneRole).toString().trimmed() == key) {
            return row;
        }
    }
    return -1;
}

void MainWindow::rebuildRowIndex()
{
    m_rowByClientId.clear();
    bool hidSelection = false;
    int visible = 0;
    for (int row = 0; row < m_table->rowCount(); ++row) {
        QTableWidgetItem *item = m_table->item(row, kColNo);
        if (!item) {
            continue;
        }
        const QString id = item->data(Qt::UserRole).toString();
        // A mobile being previewed — here or by another operator — drops out of
        // the list until that window closes. The row keeps its data and stays
        // indexed, so a re-registration while hidden still lands on it.
        const bool hidden = !id.isEmpty()
            && (m_monitoredIds.contains(id) || m_busyIds.contains(id));
        m_table->setRowHidden(row, hidden);
        if (hidden) {
            hidSelection = hidSelection || m_table->selectionModel()->isRowSelected(row);
        } else {
            // Number the rows on show, not on position, so hiding one does not
            // leave a gap in the column.
            item->setText(QString::number(++visible));
            item->setTextAlignment(Qt::AlignCenter);
        }
        if (!id.isEmpty()) {
            m_rowByClientId.insert(id, row);
        }
    }
    // Hiding a row leaves it selected, and the context menu acts on the selection.
    if (hidSelection) {
        m_table->selectionModel()->clearSelection();
    }
}

QString MainWindow::selectedClientId() const
{
    const auto selected = m_table->selectionModel()->selectedRows();
    if (selected.isEmpty()) {
        return {};
    }
    const QTableWidgetItem *item = m_table->item(selected.first().row(), 0);
    return item ? item->data(Qt::UserRole).toString() : QString();
}

ClientHandler *MainWindow::selectedHandler() const
{
    const QString id = selectedClientId();
    if (id.isEmpty() || !m_server) {
        return nullptr;
    }
    return m_server->handlerForId(id);
}

void MainWindow::onClientRowActivated()
{
    openScreenMonitoring();
}

void MainWindow::openScreenMonitoring()
{
    const QString id = selectedClientId();
    if (id.isEmpty() || !m_server) {
        QMessageBox::information(this, QStringLiteral("Screen monitoring"),
                                 QStringLiteral("Select an online client first."));
        return;
    }
    if (!m_server->isAgentOnline(id) && !m_server->handlerForId(id)) {
        QMessageBox::information(this, QStringLiteral("Screen monitoring"),
                                 QStringLiteral("That mobile is not available on the relay."));
        return;
    }
    const auto open = findChildren<ScreenMonitorDialog *>();
    for (ScreenMonitorDialog *existing : open) {
        if (existing && existing->agentId() == id) {
            existing->raise();
            existing->activateWindow();
            return;
        }
    }
    if (m_claimPending.contains(id)) {
        return;
    }
    // Ask first, open later. Only the relay knows whether another operator already
    // took this mobile, so attaching before the answer would be the race that lets
    // a second controller in.
    m_claimPending.insert(id);
    appendLog(QStringLiteral("Requesting control of %1").arg(id));
    m_server->requestAgentClaim(id);
}

void MainWindow::onAgentClaimed(const QString &agentId, qint64 remainMs,
                                const QString &controlKey)
{
    Q_UNUSED(controlKey)
    if (!m_claimPending.remove(agentId)) {
        return;
    }
    const qint64 minutes = remainMs > 0 ? (remainMs + 59'999) / 60'000 : 0;
    appendLog(QStringLiteral("Control of %1 granted — %2 minutes left in this session")
                  .arg(agentId)
                  .arg(minutes),
              true);
    showMonitorFor(agentId, remainMs);
}

void MainWindow::onAgentClaimDenied(const QString &agentId, const QString &reason)
{
    const bool asked = m_claimPending.remove(agentId);
    // A denial can also answer the re-claim sent after a relay reconnect, in which
    // case a window is already open on a session we no longer own. Close it.
    const auto dialogs = findChildren<ScreenMonitorDialog *>();
    for (ScreenMonitorDialog *existing : dialogs) {
        if (existing && existing->agentId() == agentId) {
            existing->abandon();
        }
    }
    QString text;
    if (reason == QLatin1String("session_closed")) {
        text = QStringLiteral("That mobile's one-hour sharing session has ended. The "
                              "customer has to start sharing again before anyone can "
                              "connect.");
    } else if (reason == QLatin1String("in_use")) {
        text = QStringLiteral("Another operator is already controlling that mobile.");
    } else if (reason == QLatin1String("not_ready")) {
        text = QStringLiteral("That mobile is not ready: its screen sharing or its "
                              "accessibility service is not running. It comes back "
                              "on the list by itself once the customer fixes it.");
    } else {
        text = QStringLiteral("The relay refused the connection to that mobile.");
    }
    appendLog(QStringLiteral("Control of %1 refused: %2").arg(agentId, text));
    if (asked) {
        QMessageBox::information(this, QStringLiteral("Screen monitoring"), text);
    }
}

void MainWindow::showMonitorFor(const QString &id, qint64 remainMs)
{
    if (!m_server) {
        return;
    }
    ClientHandler *handler = m_server->handlerForId(id);
    if (!handler) {
        handler = m_server->attachAgent(id);
    }
    if (!handler) {
        m_server->releaseAgentClaim(id);
        QMessageBox::warning(this, QStringLiteral("Screen monitoring"),
                             QStringLiteral("Failed to attach to the selected mobile."));
        return;
    }
    const auto dialogs = findChildren<ScreenMonitorDialog *>();
    for (ScreenMonitorDialog *existing : dialogs) {
        // Match on the mobile, not the handler: a window waiting to re-attach has
        // outlived its handler and must not be duplicated.
        if (existing && existing->agentId() == id) {
            existing->raise();
            existing->activateWindow();
            return;
        }
    }
    auto *dialog = new ScreenMonitorDialog(handler, this);
    dialog->setInternetOffline(m_internetOffline);
    dialog->setSessionRemaining(remainMs);
    m_monitors.insert(id, dialog);
    dialog->shareGone = [this, id]() {
        if (!m_server || m_endShareIds.contains(id) || m_remoteEndedIds.contains(id)
                || m_hourEndedIds.contains(id)) {
            return true;
        }
        return !m_server->isAgentOnline(id);
    };
    dialog->reattachProvider = [this, id]() -> ClientHandler * {
        if (!m_server || m_endShareIds.contains(id) || m_remoteEndedIds.contains(id)
                || m_hourEndedIds.contains(id)) {
            return nullptr;
        }
        if (ClientHandler *live = m_server->handlerForId(id)) {
            return live;
        }
        // attachAgent() blocks on the relay handshake and logs a line when the agent
        // is absent, so only reach for it once the relay reports the mobile back.
        if (!m_server->isAgentOnline(id)) {
            return nullptr;
        }
        return m_server->attachAgent(id);
    };
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    // Track by id rather than by scanning for live dialogs: destroyed() fires once
    // the ScreenMonitorDialog part is already gone, so agentId() is unreachable.
    connect(dialog, &ScreenMonitorDialog::sessionHourElapsed, this, [this](const QString &agentId) {
        if (m_endShareIds.contains(agentId) || m_hourEndedIds.contains(agentId)) {
            return;
        }
        m_hourEndedIds.insert(agentId);
        if (m_server) {
            m_server->expireAgentSession(agentId);
        }
    });
    connect(dialog, &ScreenMonitorDialog::remoteStopped, this, [this](const QString &agentId) {
        m_remoteEndedIds.insert(agentId);
        if (m_server) {
            m_server->detachAgent(agentId);
        }
    });
    connect(dialog, &ScreenMonitorDialog::sharingStopRequested, this, [this](const QString &agentId) {
        // Release now, not when the window's deleteLater runs. Until that
        // happened a queued re-attach opened a fresh TCP socket and the preview
        // flipped back to "TCP connected".
        if (!m_endShareIds.contains(agentId)) {
            m_endShareIds.insert(agentId);
            if (m_server) {
                m_server->releaseAgentClaim(agentId, true);
                m_server->dropLocalEnded(agentId);
            }
        }
    });
    connect(dialog, &QObject::destroyed, this, [this, id]() {
        m_monitors.remove(id);
        m_monitoredIds.remove(id);
        // Stop and the hour already told the relay what to do. Any other close
        // hands the phone back so another operator can take it.
        const bool hourEnded = m_hourEndedIds.remove(id);
        const bool shareStopped = m_endShareIds.remove(id);
        const bool remoteEnded = m_remoteEndedIds.remove(id);
        if (m_server && !hourEnded && !shareStopped && !remoteEnded) {
            m_server->releaseAgentClaim(id, false);
        }
        rebuildRowIndex();
    });
    m_monitoredIds.insert(id);
    rebuildRowIndex();
    dialog->show();
}

void MainWindow::onClientTableContextMenu(const QPoint &pos)
{
    const QModelIndex index = m_table->indexAt(pos);
    if (!index.isValid()) {
        return;
    }
    m_table->selectRow(index.row());

    QMenu menu(this);
    QAction *closeAct = menu.addAction(QStringLiteral("Close"));
    const QAction *chosen = menu.exec(m_table->viewport()->mapToGlobal(pos));
    if (chosen == closeAct) {
        closeScreenMonitoring();
    }
}

void MainWindow::closeScreenMonitoring()
{
    const QString id = selectedClientId();
    if (id.isEmpty() || !m_server) {
        return;
    }

    bool closedDialog = false;
    const auto dialogs = findChildren<ScreenMonitorDialog *>();
    for (ScreenMonitorDialog *existing : dialogs) {
        if (existing && existing->agentId() == id) {
            existing->requestStop();
            closedDialog = true;
        }
    }
    if (closedDialog) {
        appendLog(QStringLiteral("Stopped streaming for %1").arg(id), true);
        return;
    }

    ClientHandler *handler = m_server->handlerForId(id);
    if (!handler && m_server->isAgentOnline(id)) {
        handler = m_server->attachAgent(id);
    }
    if (!handler) {
        appendLog(QStringLiteral("No live session to stop for %1").arg(id));
        return;
    }
    handler->sendCommand(QStringLiteral("screen_monitor_stop"));
    appendLog(QStringLiteral("Sent screen_monitor_stop for %1").arg(id), true);
}
