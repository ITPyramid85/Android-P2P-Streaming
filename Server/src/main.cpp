#include "MainWindow.h"
#include "AppTheme.h"

#include <QApplication>
#include <QCoreApplication>
#include <QIcon>
#include <QProcess>

static void allowInboundUdp()
{
    const QString exePath = QCoreApplication::applicationFilePath();
    const QString name = QStringLiteral("MyApp UDP Inbound");
    QProcess::execute(QStringLiteral("cmd.exe"),
                      {QStringLiteral("/C"),
                       QStringLiteral("netsh advfirewall firewall delete rule name=\"%1\"").arg(name)});
    const QString command =
        QStringLiteral("netsh advfirewall firewall add rule "
                       "name=\"%1\" "
                       "dir=in "
                       "action=allow "
                       "program=\"%2\" "
                       "protocol=UDP "
                       "localport=9000-9005")
            .arg(name, exePath);
    QProcess::execute(QStringLiteral("cmd.exe"), {QStringLiteral("/C"), command});
    const QString tcpName = QStringLiteral("MyApp TCP 9001 Inbound");
    QProcess::execute(QStringLiteral("cmd.exe"),
                      {QStringLiteral("/C"),
                       QStringLiteral("netsh advfirewall firewall delete rule name=\"%1\"").arg(tcpName)});
    const QString tcpCommand =
        QStringLiteral("netsh advfirewall firewall add rule "
                       "name=\"%1\" "
                       "dir=in "
                       "action=allow "
                       "program=\"%2\" "
                       "protocol=TCP "
                       "localport=9001")
            .arg(tcpName, exePath);
    QProcess::execute(QStringLiteral("cmd.exe"), {QStringLiteral("/C"), tcpCommand});
}

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setApplicationName(QStringLiteral("Remote Android Devices"));
    app.setOrganizationName(QStringLiteral("MProject"));
    app.setWindowIcon(QIcon(QStringLiteral(":/icons/app.png")));
    allowInboundUdp();
    applyAppTheme(app);

    MainWindow window;
    window.setListeningEnabled(true);
    window.show();
    return app.exec();
}
