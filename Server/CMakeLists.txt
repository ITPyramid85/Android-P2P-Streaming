cmake_minimum_required(VERSION 3.16)

project(MProjectServer VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)

find_package(Qt6 REQUIRED COMPONENTS Widgets Network)

include(FetchContent)
# Data channels only: the screen and input bytes ride SCTP, not WebRTC media tracks.
# libdatachannel needs OpenSSL. The Qt MinGW kit does not ship it; a local
# MSYS2 MinGW OpenSSL is used when one is installed.
if(NOT OPENSSL_ROOT_DIR AND EXISTS "C:/msys64/mingw64/include/openssl/ssl.h")
    set(OPENSSL_ROOT_DIR "C:/msys64/mingw64" CACHE PATH "OpenSSL used by libdatachannel" FORCE)
endif()
# FindOpenSSL turns OPENSSL_ROOT_DIR/include into a -isystem path. That
# directory is the whole MSYS2 sysroot, so it is searched before the Qt
# MinGW headers and renames ftime/nanosleep/pthread_cond_timedwait to the
# time64 symbols this toolchain does not export. Expose only openssl/.
if(EXISTS "C:/msys64/mingw64/include/openssl/ssl.h")
    set(_openssl_inc "${CMAKE_BINARY_DIR}/openssl-inc")
    file(MAKE_DIRECTORY "${_openssl_inc}")
    if(NOT EXISTS "${_openssl_inc}/openssl/ssl.h")
        # Junction, not a symlink: Windows denies unprivileged symbolic links.
        execute_process(
            COMMAND powershell -NoProfile -Command "New-Item -ItemType Junction -Path '${_openssl_inc}/openssl' -Target 'C:/msys64/mingw64/include/openssl'"
            RESULT_VARIABLE _openssl_link_rc
            OUTPUT_QUIET ERROR_QUIET)
        if(NOT _openssl_link_rc EQUAL 0 OR NOT EXISTS "${_openssl_inc}/openssl/ssl.h")
            message(FATAL_ERROR "Could not junction OpenSSL headers into ${_openssl_inc}")
        endif()
    endif()
    set(OPENSSL_INCLUDE_DIR "${_openssl_inc}" CACHE PATH "OpenSSL headers only" FORCE)
endif()
# A shared libdatachannel DLL fails to link MinGW time64/pthread symbols.
# Static is what the desktop executable needs anyway.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(NO_WEBSOCKET ON CACHE BOOL "" FORCE)
set(NO_MEDIA ON CACHE BOOL "" FORCE)
set(NO_EXAMPLES ON CACHE BOOL "" FORCE)
set(NO_TESTS ON CACHE BOOL "" FORCE)
FetchContent_Declare(libdatachannel
    GIT_REPOSITORY https://github.com/paullouisageneau/libdatachannel.git
    GIT_TAG v0.22.6
    GIT_SHALLOW TRUE
)
# Global AUTOMOC would moc libdatachannel and shadow libstdc++ headers such as
# <bits/random.tcc> with an autogen include directory.
set(_webrtc_automoc ${CMAKE_AUTOMOC})
set(_webrtc_autouic ${CMAKE_AUTOUIC})
set(_webrtc_autorcc ${CMAKE_AUTORCC})
set(CMAKE_AUTOMOC OFF)
set(CMAKE_AUTOUIC OFF)
set(CMAKE_AUTORCC OFF)
FetchContent_MakeAvailable(libdatachannel)
if(TARGET datachannel)
    set_target_properties(datachannel PROPERTIES AUTOMOC OFF AUTOUIC OFF AUTORCC OFF)
    # libdatachannel adds include/rtc as -I. MinGW then resolves <bits/...> inside
    # libstdc++ to that directory and aborts. Put the compiler's own include
    # directories ahead of it so the standard headers win.
    if(MINGW)
        foreach(_dir IN LISTS CMAKE_CXX_IMPLICIT_INCLUDE_DIRECTORIES)
            target_compile_options(datachannel BEFORE PRIVATE "SHELL:-I ${_dir}")
        endforeach()
    endif()
endif()
set(CMAKE_AUTOMOC ${_webrtc_automoc})
set(CMAKE_AUTOUIC ${_webrtc_autouic})
set(CMAKE_AUTORCC ${_webrtc_autorcc})

qt_standard_project_setup()

set(SHARED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../Shared")

qt_add_executable(MProjectServer
    src/main.cpp
    src/AppTheme.h
    src/AppTheme.cpp
    src/LoginDialog.h
    src/LoginDialog.cpp
    src/SessionIdleWatch.h
    src/SessionIdleWatch.cpp
    src/MainWindow.h
    src/MainWindow.cpp
    src/TcpServer.h
    src/TcpServer.cpp
    src/ClientHandler.h
    src/ClientHandler.cpp
    src/H264Decoder.h
    src/H264Decoder.cpp
    src/WebrtcSession.h
    src/WebrtcSession.cpp
    src/Packet.h
    src/FeatureDialogs.h
    src/FeatureDialogs.cpp
    src/ScreenInputView.h
    src/ScreenInputView.cpp
    "${SHARED_DIR}/AuthProtocol.h"
    "${SHARED_DIR}/AdminAuthClient.h"
    "${SHARED_DIR}/AdminAuthClient.cpp"
    resources/app.qrc
)

# Bundled FFmpeg (libavcodec/libswscale) — the H.264 decoder backend. Vendored
# under third_party so decoding works on every Windows edition without relying on
# Media Foundation, which is missing on Windows N/KN.
set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg")

target_include_directories(MProjectServer PRIVATE src "${SHARED_DIR}" "${FFMPEG_DIR}/include")
target_link_libraries(MProjectServer PRIVATE Qt6::Widgets Qt6::Network
    LibDataChannel::LibDataChannel
    "${FFMPEG_DIR}/lib/libavcodec.dll.a"
    "${FFMPEG_DIR}/lib/libavutil.dll.a"
    "${FFMPEG_DIR}/lib/libswscale.dll.a"
    "${FFMPEG_DIR}/lib/libswresample.dll.a"
)

# Ship the FFmpeg runtime DLLs next to the executable (app dir wins on the DLL
# search path, so these are used even if another FFmpeg is elsewhere on PATH).
if (WIN32)
    add_custom_command(TARGET MProjectServer POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${FFMPEG_DIR}/bin" "$<TARGET_FILE_DIR:MProjectServer>"
        COMMENT "Deploying bundled FFmpeg DLLs"
    )
    if(EXISTS "C:/msys64/mingw64/bin/libcrypto-3-x64.dll")
        add_custom_command(TARGET MProjectServer POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "C:/msys64/mingw64/bin/libcrypto-3-x64.dll"
                "C:/msys64/mingw64/bin/libssl-3-x64.dll"
                "$<TARGET_FILE_DIR:MProjectServer>"
            COMMENT "Deploying OpenSSL DLLs for WebRTC"
        )
    endif()
endif()

set_target_properties(MProjectServer PROPERTIES
    WIN32_EXECUTABLE ON
    OUTPUT_NAME RemoteController
)

if (WIN32)
    set(APP_ICON_PATH "${CMAKE_CURRENT_SOURCE_DIR}/resources/app.ico")
    configure_file(
        "${CMAKE_CURRENT_SOURCE_DIR}/resources/app.rc.in"
        "${CMAKE_CURRENT_BINARY_DIR}/app.rc"
        @ONLY
    )
    target_sources(MProjectServer PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/app.rc")
endif()

# Deploy Qt DLLs/plugins next to the exe so it can launch outside Qt Creator.
if (WIN32)
    get_target_property(_qmake_executable Qt6::qmake IMPORTED_LOCATION)
    get_filename_component(_qt_bin_dir "${_qmake_executable}" DIRECTORY)
    find_program(WINDEPLOYQT_EXECUTABLE windeployqt HINTS "${_qt_bin_dir}")
    if (WINDEPLOYQT_EXECUTABLE)
        add_custom_command(TARGET MProjectServer POST_BUILD
            COMMAND "${WINDEPLOYQT_EXECUTABLE}"
                $<$<CONFIG:Release>:--release>
                $<$<CONFIG:Debug>:--debug>
                --compiler-runtime
                \"$<TARGET_FILE:MProjectServer>\"
            COMMAND powershell.exe -NoProfile -ExecutionPolicy Bypass
                -File "${CMAKE_CURRENT_SOURCE_DIR}/../scripts/sign-windows-binaries.ps1"
                \"$<TARGET_FILE:MProjectServer>\"
            COMMENT "Deploying Qt runtime and code-signing RemoteController"
        )
    endif()
endif()
