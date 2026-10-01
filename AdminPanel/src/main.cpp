#include "MainWindow.h"
#include "AdminLoginDialog.h"
#include "AppTheme.h"
#include "UserDatabase.h"

#include <QApplication>
#include <QIcon>
#include <QMessageBox>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setApplicationName(QStringLiteral("AdminPanel"));
    app.setOrganizationName(QStringLiteral("MProject"));
    app.setWindowIcon(QIcon(QStringLiteral(":/icons/app.png")));
    applyAppTheme(app);

    if (!UserDatabase::instance().open()) {
        QMessageBox::critical(
            nullptr,
            QStringLiteral("AdminPanel"),
            QStringLiteral("Cannot open SQLite database:\n%1")
                .arg(UserDatabase::instance().lastError()));
        return 1;
    }

    AdminLoginDialog login;
    if (login.exec() != QDialog::Accepted) {
        return 0;
    }

    MainWindow window;
    window.show();
    return app.exec();
}
