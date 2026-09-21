#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <shlobj.h>
#include <wchar.h>

#define APP_TITLE L"CloudStream Desktop"

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    int res = MessageBoxW(
        NULL,
        L"CloudStream Desktop uygulamasını ve tüm kısayollarını bilgisayarınızdan kaldırmak istiyor musunuz?",
        APP_TITLE,
        MB_YESNO | MB_ICONQUESTION
    );

    if (res != IDYES) {
        return 0;
    }

    // 1. Delete Desktop shortcut
    wchar_t desktopPath[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_DESKTOPDIRECTORY, NULL, 0, desktopPath))) {
        wchar_t lnkPath[MAX_PATH];
        _snwprintf(lnkPath, MAX_PATH, L"%ls\\CloudStream.lnk", desktopPath);
        DeleteFileW(lnkPath);
    }

    // 2. Delete Start Menu shortcut
    wchar_t startMenuPath[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_PROGRAMS, NULL, 0, startMenuPath))) {
        wchar_t lnkPath[MAX_PATH];
        _snwprintf(lnkPath, MAX_PATH, L"%ls\\CloudStream.lnk", startMenuPath);
        DeleteFileW(lnkPath);
    }

    // 3. Delete Registry uninstall entry
    RegDeleteKeyW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\CloudStream");

    // 4. Determine install directory
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(NULL, exePath, MAX_PATH);
    wchar_t* lastSlash = wcsrchr(exePath, L'\\');
    if (lastSlash) {
        *lastSlash = L'\0';
    }

    // 5. Spawn background cmd to delete install directory after this process exits
    wchar_t cmdArgs[MAX_PATH * 2];
    _snwprintf(cmdArgs, sizeof(cmdArgs)/sizeof(wchar_t), L"/c timeout /t 1 /nobreak >nul & rmdir /s /q \"%ls\"", exePath);
    ShellExecuteW(NULL, L"open", L"cmd.exe", cmdArgs, NULL, SW_HIDE);

    MessageBoxW(
        NULL,
        L"CloudStream Desktop başarıyla kaldırıldı.",
        APP_TITLE,
        MB_OK | MB_ICONINFORMATION
    );

    return 0;
}
