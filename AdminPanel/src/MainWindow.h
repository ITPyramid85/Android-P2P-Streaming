#pragma once

#include <QMainWindow>

class AuthServer;
class QTableWidget;

class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    explicit MainWindow(QWidget *parent = nullptr);

private slots:
    void refreshUsers();
    void addUser();
    void deleteSelectedUser();

private:
    void setupUi();

    QTableWidget *m_table = nullptr;
    AuthServer *m_auth = nullptr;
};
