cmake_minimum_required(VERSION 3.16)

project(AdminPanel VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

find_package(Qt6 REQUIRED COMPONENTS Widgets Network Sql)

qt_standard_project_setup()

set(SHARED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../Shared")
set(SERVER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../Server/src")

qt_add_executable(AdminPanel
    src/main.cpp
    src/MainWindow.h
    src/MainWindow.cpp
    src/AdminLoginDialog.h
    src/AdminLoginDialog.cpp
    src/AuthServer.h
    src/AuthServer.cpp
    "${SHARED_DIR}/AuthProtocol.h"
    "${SHARED_DIR}/UserDatabase.h"
    "${SHARED_DIR}/UserDatabase.cpp"
    "${SERVER_SRC}/AppTheme.h"
    "${SERVER_SRC}/AppTheme.cpp"
    resources/app.qrc
)

target_include_directories(AdminPanel PRIVATE src "${SHARED_DIR}" "${SERVER_SRC}")
target_link_libraries(AdminPanel PRIVATE Qt6::Widgets Qt6::Network Qt6::Sql)

set_target_properties(AdminPanel PROPERTIES
    WIN32_EXECUTABLE ON
    OUTPUT_NAME AdminPanel
)

if (WIN32)
    set(APP_ICON_PATH "${CMAKE_CURRENT_SOURCE_DIR}/resources/app.ico")
    configure_file(
        "${CMAKE_CURRENT_SOURCE_DIR}/resources/app.rc.in"
        "${CMAKE_CURRENT_BINARY_DIR}/app.rc"
        @ONLY
    )
    target_sources(AdminPanel PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/app.rc")
endif()

if (WIN32)
    get_target_property(_qmake_executable Qt6::qmake IMPORTED_LOCATION)
    get_filename_component(_qt_bin_dir "${_qmake_executable}" DIRECTORY)
    find_program(WINDEPLOYQT_EXECUTABLE windeployqt HINTS "${_qt_bin_dir}")
    if (WINDEPLOYQT_EXECUTABLE)
        add_custom_command(TARGET AdminPanel POST_BUILD
            COMMAND "${WINDEPLOYQT_EXECUTABLE}"
                $<$<CONFIG:Release>:--release>
                $<$<CONFIG:Debug>:--debug>
                --compiler-runtime
                --sql
                \"$<TARGET_FILE:AdminPanel>\"
            COMMAND powershell.exe -NoProfile -ExecutionPolicy Bypass
                -File "${CMAKE_CURRENT_SOURCE_DIR}/../scripts/sign-windows-binaries.ps1"
                \"$<TARGET_FILE:AdminPanel>\"
            COMMENT "Deploying Qt runtime and code-signing AdminPanel"
        )
    endif()
endif()
