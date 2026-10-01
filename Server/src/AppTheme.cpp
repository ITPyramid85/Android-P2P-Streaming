#include "AppTheme.h"

#include <QApplication>
#include <QColor>
#include <QFont>
#include <QPalette>
#include <QStyleFactory>

void applyAppTheme(QApplication &app)
{
    app.setStyle(QStyleFactory::create(QStringLiteral("Fusion")));
    app.setFont(QFont(QStringLiteral("Segoe UI"), 10));

    QPalette pal;
    const QColor window(0x1B, 0x1D, 0x23);
    const QColor base(0x22, 0x25, 0x2C);
    const QColor alt(0x28, 0x2C, 0x34);
    const QColor text(0xE8, 0xEA, 0xED);
    const QColor disabled(0x7A, 0x80, 0x8A);
    const QColor highlight(0x3D, 0x6C, 0xC9);
    const QColor highlightedText(0xFF, 0xFF, 0xFF);
    const QColor button(0x2C, 0x31, 0x3A);
    pal.setColor(QPalette::Window, window);
    pal.setColor(QPalette::WindowText, text);
    pal.setColor(QPalette::Base, base);
    pal.setColor(QPalette::AlternateBase, alt);
    pal.setColor(QPalette::Text, text);
    pal.setColor(QPalette::Button, button);
    pal.setColor(QPalette::ButtonText, text);
    pal.setColor(QPalette::BrightText, QColor(0xFF, 0x6B, 0x6B));
    pal.setColor(QPalette::ToolTipBase, QColor(0x2C, 0x31, 0x3A));
    pal.setColor(QPalette::ToolTipText, text);
    pal.setColor(QPalette::Highlight, highlight);
    pal.setColor(QPalette::HighlightedText, highlightedText);
    pal.setColor(QPalette::PlaceholderText, disabled);
    pal.setColor(QPalette::Disabled, QPalette::Text, disabled);
    pal.setColor(QPalette::Disabled, QPalette::WindowText, disabled);
    pal.setColor(QPalette::Disabled, QPalette::ButtonText, disabled);
    app.setPalette(pal);

    app.setStyleSheet(QStringLiteral(R"(
        QMainWindow, QDialog {
            background-color: #1B1D23;
            color: #E8EAED;
        }
        QLabel {
            color: #C5CAD3;
            background: transparent;
        }
        QLabel#loginTitle {
            color: #F1F3F4;
            font-size: 22px;
            font-weight: 600;
        }
        QLabel#loginError {
            color: #FF6B6B;
        }
        QLineEdit {
            background-color: #22252C;
            color: #E8EAED;
            border: 1px solid #3E4450;
            border-radius: 6px;
            padding: 8px 10px;
            min-height: 22px;
            selection-background-color: #3D6CC9;
        }
        QLineEdit:focus {
            border-color: #5B8DEF;
        }
        QPushButton#loginButton {
            background-color: #3D6CC9;
            color: #FFFFFF;
            border-color: #3D6CC9;
            padding: 10px 16px;
        }
        QPushButton#loginButton:hover {
            background-color: #4C8DFF;
            border-color: #4C8DFF;
        }
        QPushButton {
            background-color: #2C313A;
            color: #E8EAED;
            border: 1px solid #3E4450;
            border-radius: 6px;
            padding: 7px 16px;
            min-height: 18px;
        }
        QPushButton:hover {
            background-color: #3A4150;
            border-color: #5B8DEF;
        }
        QPushButton:pressed {
            background-color: #4C8DFF;
            color: #FFFFFF;
            border-color: #4C8DFF;
        }
        QTableWidget#clientTable {
            background-color: #22252C;
            alternate-background-color: #1E2128;
            color: #E8EAED;
            gridline-color: #3A3F4B;
            border: 1px solid #4A5160;
            border-radius: 8px;
            selection-background-color: #3D6CC9;
            selection-color: #FFFFFF;
            outline: 0;
        }
        QTableWidget#clientTable::item {
            padding: 6px 10px;
            border: none;
        }
        QTableWidget#clientTable::item:hover {
            background-color: #2A3140;
        }
        QHeaderView {
            background-color: #32363F;
            color: #F1F3F4;
        }
        QHeaderView::section {
            background-color: #32363F;
            color: #F1F3F4;
            border-top: 1px solid #5A6270;
            border-bottom: 1px solid #5A6270;
            border-right: 1px solid #5A6270;
            padding: 9px 12px;
            font-weight: 600;
        }
        QHeaderView::section:first {
            border-left: 1px solid #5A6270;
        }
        QHeaderView::section:last {
            border-right: 1px solid #5A6270;
        }
        QTextEdit#eventLog, QTextEdit {
            background-color: #17191E;
            color: #C5C8CE;
            border: 1px solid #32363F;
            border-radius: 8px;
            padding: 8px;
            selection-background-color: #3D6CC9;
        }
        QSplitter::handle {
            background-color: #1B1D23;
        }
        QSplitter::handle:vertical {
            height: 8px;
        }
        QSplitter::handle:horizontal {
            width: 8px;
        }
        QScrollBar:vertical {
            background: #1B1D23;
            width: 10px;
            margin: 0;
        }
        QScrollBar::handle:vertical {
            background: #4A5160;
            min-height: 28px;
            border-radius: 5px;
        }
        QScrollBar::handle:vertical:hover {
            background: #5B8DEF;
        }
        QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical,
        QScrollBar::add-page:vertical, QScrollBar::sub-page:vertical {
            background: none;
            height: 0;
        }
        QScrollBar:horizontal {
            background: #1B1D23;
            height: 10px;
            margin: 0;
        }
        QScrollBar::handle:horizontal {
            background: #4A5160;
            min-width: 28px;
            border-radius: 5px;
        }
        QScrollBar::add-line:horizontal, QScrollBar::sub-line:horizontal,
        QScrollBar::add-page:horizontal, QScrollBar::sub-page:horizontal {
            background: none;
            width: 0;
        }
        QMessageBox {
            background-color: #24272F;
        }
        QToolTip {
            background-color: #2C313A;
            color: #E8EAED;
            border: 1px solid #4C8DFF;
            padding: 6px 8px;
        }
    )"));
}
