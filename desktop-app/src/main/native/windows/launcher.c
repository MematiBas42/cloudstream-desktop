#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <wchar.h>

#define APP_TITLE L"CloudStream Desktop"

static void ShowError(const wchar_t* msg) {
    MessageBoxW(NULL, msg, APP_TITLE, MB_OK | MB_ICONERROR);
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    wchar_t exePath[MAX_PATH];
    if (!GetModuleFileNameW(NULL, exePath, MAX_PATH)) {
        ShowError(L"Uygulama dizini belirlenemedi.");
        return 1;
    }

    wchar_t* lastSlash = wcsrchr(exePath, L'\\');
    if (lastSlash) {
        *lastSlash = L'\0';
    }

    wchar_t javaExe[MAX_PATH];
    wchar_t jarPath[MAX_PATH];

    _snwprintf(javaExe, MAX_PATH, L"%ls\\runtime\\bin\\javaw.exe", exePath);
    if (GetFileAttributesW(javaExe) == INVALID_FILE_ATTRIBUTES) {
        _snwprintf(javaExe, MAX_PATH, L"%ls\\runtime\\bin\\java.exe", exePath);
        if (GetFileAttributesW(javaExe) == INVALID_FILE_ATTRIBUTES) {
            ShowError(L"Gömülü Java çalışma zamanı bulunamadı (runtime\\bin\\javaw.exe).");
            return 1;
        }
    }

    _snwprintf(jarPath, MAX_PATH, L"%ls\\app\\CloudStream.jar", exePath);
    if (GetFileAttributesW(jarPath) == INVALID_FILE_ATTRIBUTES) {
        _snwprintf(jarPath, MAX_PATH, L"%ls\\CloudStream.jar", exePath);
        if (GetFileAttributesW(jarPath) == INVALID_FILE_ATTRIBUTES) {
            ShowError(L"Uygulama paketi bulunamadı (app\\CloudStream.jar).");
            return 1;
        }
    }

    wchar_t cmdLine[4096];
    if (pCmdLine && wcslen(pCmdLine) > 0) {
        _snwprintf(cmdLine, 4096,
            L"\"%ls\" -XX:+UseG1GC -XX:+UseStringDeduplication -Dsun.java2d.d3d=true -Dskiko.renderApi=DIRECT3D -jar \"%ls\" %ls",
            javaExe, jarPath, pCmdLine);
    } else {
        _snwprintf(cmdLine, 4096,
            L"\"%ls\" -XX:+UseG1GC -XX:+UseStringDeduplication -Dsun.java2d.d3d=true -Dskiko.renderApi=DIRECT3D -jar \"%ls\"",
            javaExe, jarPath);
    }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    if (!CreateProcessW(NULL, cmdLine, NULL, NULL, FALSE, 0, NULL, exePath, &si, &pi)) {
        ShowError(L"CloudStream başlatılamadı.");
        return 1;
    }

    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return 0;
}
