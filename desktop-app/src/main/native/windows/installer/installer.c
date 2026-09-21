#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <commctrl.h>
#include <shlobj.h>
#include <wchar.h>
#include <stdio.h>
#include "miniz.h"

#define APP_NAME L"CloudStream Desktop"
#define APP_VERSION L"1.0.0"
#define APP_PUBLISHER L"CloudStream"
#define IDR_PAYLOAD_ZIP 100

// Control IDs
#define IDC_PROGRESS_BAR 1001
#define IDC_STATUS_LABEL 1002
#define IDC_TITLE_LABEL  1003
#define IDC_SUBTITLE_LBL 1004

// Custom messages
#define WM_INSTALL_PROGRESS (WM_USER + 1)
#define WM_INSTALL_STATUS   (WM_USER + 2)
#define WM_INSTALL_COMPLETE (WM_USER + 3)
#define WM_INSTALL_ERROR    (WM_USER + 4)

static HWND g_hWnd = NULL;
static HWND g_hProgressBar = NULL;
static HWND g_hStatusLabel = NULL;
static HICON g_hAppIcon = NULL;
static HFONT g_hFontTitle = NULL;
static HFONT g_hFontSub = NULL;
static HFONT g_hFontStatus = NULL;
static HBRUSH g_hBgBrush = NULL;

// Dark Palette
#define COLOR_BG RGB(24, 24, 37)
#define COLOR_CARD RGB(30, 30, 46)
#define COLOR_TEXT_WHITE RGB(245, 245, 255)
#define COLOR_TEXT_MUTED RGB(165, 173, 206)
#define COLOR_ACCENT RGB(137, 180, 250)

static HRESULT CreateShortcut(const wchar_t* targetPath, const wchar_t* lnkPath, const wchar_t* iconPath, const wchar_t* workDir) {
    IShellLinkW* psl = NULL;
    HRESULT hr = CoCreateInstance(&CLSID_ShellLink, NULL, CLSCTX_INPROC_SERVER, &IID_IShellLinkW, (void**)&psl);
    if (SUCCEEDED(hr)) {
        psl->lpVtbl->SetPath(psl, targetPath);
        if (workDir) psl->lpVtbl->SetWorkingDirectory(psl, workDir);
        if (iconPath) psl->lpVtbl->SetIconLocation(psl, iconPath, 0);
        psl->lpVtbl->SetDescription(psl, APP_NAME);

        IPersistFile* ppf = NULL;
        hr = psl->lpVtbl->QueryInterface(psl, &IID_IPersistFile, (void**)&ppf);
        if (SUCCEEDED(hr)) {
            hr = ppf->lpVtbl->Save(ppf, lnkPath, TRUE);
            ppf->lpVtbl->Release(ppf);
        }
        psl->lpVtbl->Release(psl);
    }
    return hr;
}

static void CreateDirectoryRecursive(const wchar_t* path) {
    wchar_t temp[MAX_PATH];
    wcsncpy(temp, path, MAX_PATH);
    for (wchar_t* p = temp + 1; *p; p++) {
        if (*p == L'/' || *p == L'\\') {
            *p = L'\0';
            CreateDirectoryW(temp, NULL);
            *p = L'\\';
        }
    }
    CreateDirectoryW(temp, NULL);
}

static DWORD WINAPI InstallThread(LPVOID lpParam) {
    CoInitialize(NULL);

    PostMessageW(g_hWnd, WM_INSTALL_STATUS, 0, (LPARAM)L"Hedef dizin oluşturuluyor...");

    // 1. Target directory: %LOCALAPPDATA%\Programs\CloudStream
    wchar_t localAppData[MAX_PATH];
    if (FAILED(SHGetFolderPathW(NULL, CSIDL_LOCAL_APPDATA, NULL, 0, localAppData))) {
        PostMessageW(g_hWnd, WM_INSTALL_ERROR, 0, (LPARAM)L"AppData dizini bulunamadı.");
        CoUninitialize();
        return 1;
    }

    wchar_t targetDir[MAX_PATH];
    _snwprintf(targetDir, MAX_PATH, L"%ls\\Programs\\CloudStream", localAppData);
    CreateDirectoryRecursive(targetDir);

    // 2. Load embedded zip payload from resources
    PostMessageW(g_hWnd, WM_INSTALL_STATUS, 0, (LPARAM)L"Yükleme paketi açılıyor...");
    HRSRC hRes = FindResourceW(NULL, MAKEINTRESOURCEW(IDR_PAYLOAD_ZIP), RT_RCDATA);
    if (!hRes) {
        PostMessageW(g_hWnd, WM_INSTALL_ERROR, 0, (LPARAM)L"Gömülü kurulum paketi bulunamadı.");
        CoUninitialize();
        return 1;
    }

    HGLOBAL hMem = LoadResource(NULL, hRes);
    DWORD zipSize = SizeofResource(NULL, hRes);
    const void* pZipData = LockResource(hMem);

    if (!pZipData || zipSize == 0) {
        PostMessageW(g_hWnd, WM_INSTALL_ERROR, 0, (LPARAM)L"Paket verisi okunamadı.");
        CoUninitialize();
        return 1;
    }

    // 3. Extract with miniz
    mz_zip_archive zip;
    memset(&zip, 0, sizeof(zip));
    if (!mz_zip_reader_init_mem(&zip, pZipData, zipSize, 0)) {
        PostMessageW(g_hWnd, WM_INSTALL_ERROR, 0, (LPARAM)L"Paket arşivi çözülemedi.");
        CoUninitialize();
        return 1;
    }

    int totalFiles = (int)mz_zip_reader_get_num_files(&zip);
    PostMessageW(g_hWnd, WM_INSTALL_STATUS, 0, (LPARAM)L"Dosyalar kuruluyor...");

    for (int i = 0; i < totalFiles; i++) {
        mz_zip_archive_file_stat stat;
        if (!mz_zip_reader_file_stat(&zip, i, &stat)) continue;

        // Skip leading "CloudStream/" if archive root has it
        const char* relPath = stat.m_filename;
        if (strncmp(relPath, "CloudStream/", 12) == 0) {
            relPath += 12;
        }
        if (strlen(relPath) == 0) continue;

        wchar_t wRelPath[MAX_PATH];
        MultiByteToWideChar(CP_UTF8, 0, relPath, -1, wRelPath, MAX_PATH);

        wchar_t destFilePath[MAX_PATH];
        _snwprintf(destFilePath, MAX_PATH, L"%ls\\%ls", targetDir, wRelPath);

        if (stat.m_is_directory) {
            CreateDirectoryRecursive(destFilePath);
        } else {
            // Ensure parent dir exists
            wchar_t parentDir[MAX_PATH];
            wcsncpy(parentDir, destFilePath, MAX_PATH);
            wchar_t* lastSlash = wcsrchr(parentDir, L'\\');
            if (lastSlash) {
                *lastSlash = L'\0';
                CreateDirectoryRecursive(parentDir);
            }

            // Convert to char path for miniz extract
            char utf8Dest[MAX_PATH * 3];
            WideCharToMultiByte(CP_UTF8, 0, destFilePath, -1, utf8Dest, sizeof(utf8Dest), NULL, NULL);
            mz_zip_reader_extract_to_file(&zip, i, utf8Dest, 0);
        }

        int progress = (int)(((double)(i + 1) / (double)totalFiles) * 85.0);
        PostMessageW(g_hWnd, WM_INSTALL_PROGRESS, progress, 0);
    }
    mz_zip_reader_end(&zip);

    PostMessageW(g_hWnd, WM_INSTALL_STATUS, 0, (LPARAM)L"Kısayollar ve kayıtlar oluşturuluyor...");

    // 4. Create Shortcuts
    wchar_t exePath[MAX_PATH];
    _snwprintf(exePath, MAX_PATH, L"%ls\\CloudStream.exe", targetDir);

    wchar_t icoPath[MAX_PATH];
    _snwprintf(icoPath, MAX_PATH, L"%ls\\logo_ui.ico", targetDir);

    // Desktop Shortcut
    wchar_t desktopDir[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_DESKTOPDIRECTORY, NULL, 0, desktopDir))) {
        wchar_t desktopLnk[MAX_PATH];
        _snwprintf(desktopLnk, MAX_PATH, L"%ls\\CloudStream.lnk", desktopDir);
        CreateShortcut(exePath, desktopLnk, icoPath, targetDir);
    }

    // Start Menu Shortcut
    wchar_t programsDir[MAX_PATH];
    if (SUCCEEDED(SHGetFolderPathW(NULL, CSIDL_PROGRAMS, NULL, 0, programsDir))) {
        wchar_t startLnk[MAX_PATH];
        _snwprintf(startLnk, MAX_PATH, L"%ls\\CloudStream.lnk", programsDir);
        CreateShortcut(exePath, startLnk, icoPath, targetDir);
    }

    PostMessageW(g_hWnd, WM_INSTALL_PROGRESS, 95, 0);

    // 5. Windows Registry (Add/Remove Programs)
    HKEY hKey;
    const wchar_t* regSubKey = L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\CloudStream";
    if (RegCreateKeyExW(HKEY_CURRENT_USER, regSubKey, 0, NULL, 0, KEY_WRITE, NULL, &hKey, NULL) == ERROR_SUCCESS) {
        wchar_t uninstPath[MAX_PATH];
        _snwprintf(uninstPath, MAX_PATH, L"\"%ls\\uninstall.exe\"", targetDir);

        RegSetValueExW(hKey, L"DisplayName", 0, REG_SZ, (const BYTE*)APP_NAME, (DWORD)(wcslen(APP_NAME) + 1) * sizeof(wchar_t));
        RegSetValueExW(hKey, L"DisplayVersion", 0, REG_SZ, (const BYTE*)APP_VERSION, (DWORD)(wcslen(APP_VERSION) + 1) * sizeof(wchar_t));
        RegSetValueExW(hKey, L"Publisher", 0, REG_SZ, (const BYTE*)APP_PUBLISHER, (DWORD)(wcslen(APP_PUBLISHER) + 1) * sizeof(wchar_t));
        RegSetValueExW(hKey, L"InstallLocation", 0, REG_SZ, (const BYTE*)targetDir, (DWORD)(wcslen(targetDir) + 1) * sizeof(wchar_t));
        RegSetValueExW(hKey, L"DisplayIcon", 0, REG_SZ, (const BYTE*)icoPath, (DWORD)(wcslen(icoPath) + 1) * sizeof(wchar_t));
        RegSetValueExW(hKey, L"UninstallString", 0, REG_SZ, (const BYTE*)uninstPath, (DWORD)(wcslen(uninstPath) + 1) * sizeof(wchar_t));
        DWORD estimatedSize = 250000; // ~250 MB
        RegSetValueExW(hKey, L"EstimatedSize", 0, REG_DWORD, (const BYTE*)&estimatedSize, sizeof(DWORD));

        RegCloseKey(hKey);
    }

    PostMessageW(g_hWnd, WM_INSTALL_PROGRESS, 100, 0);
    PostMessageW(g_hWnd, WM_INSTALL_STATUS, 0, (LPARAM)L"Kurulum tamamlandı! Başlatılıyor...");
    Sleep(1200);

    // 6. Launch CloudStream.exe
    ShellExecuteW(NULL, L"open", exePath, NULL, targetDir, SW_SHOWNORMAL);

    PostMessageW(g_hWnd, WM_INSTALL_COMPLETE, 0, 0);
    CoUninitialize();
    return 0;
}

static LRESULT CALLBACK WndProc(HWND hWnd, UINT message, WPARAM wParam, LPARAM lParam) {
    switch (message) {
        case WM_CREATE: {
            g_hProgressBar = CreateWindowExW(
                0, PROGRESS_CLASSW, NULL,
                WS_CHILD | WS_VISIBLE | PBS_SMOOTH,
                35, 195, 410, 16,
                hWnd, (HMENU)IDC_PROGRESS_BAR, ((LPCREATESTRUCT)lParam)->hInstance, NULL
            );
            SendMessageW(g_hProgressBar, PBM_SETRANGE, 0, MAKELPARAM(0, 100));
            SendMessageW(g_hProgressBar, PBM_SETPOS, 0, 0);

            // Start installation in background thread
            CreateThread(NULL, 0, InstallThread, NULL, 0, NULL);
            break;
        }

        case WM_INSTALL_PROGRESS:
            SendMessageW(g_hProgressBar, PBM_SETPOS, wParam, 0);
            break;

        case WM_INSTALL_STATUS:
            SetWindowTextW(g_hStatusLabel, (const wchar_t*)lParam);
            break;

        case WM_INSTALL_COMPLETE:
            DestroyWindow(hWnd);
            break;

        case WM_INSTALL_ERROR:
            MessageBoxW(hWnd, (const wchar_t*)lParam, APP_NAME, MB_OK | MB_ICONERROR);
            DestroyWindow(hWnd);
            break;

        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hWnd, &ps);

            // Fill background
            RECT rc;
            GetClientRect(hWnd, &rc);
            FillRect(hdc, &rc, g_hBgBrush);

            // Set Transparent Text Mode
            SetBkMode(hdc, TRANSPARENT);

            // Draw Icon (48x48)
            if (g_hAppIcon) {
                DrawIconEx(hdc, 35, 30, g_hAppIcon, 56, 56, 0, NULL, DI_NORMAL);
            }

            // Draw Title
            SelectObject(hdc, g_hFontTitle);
            SetTextColor(hdc, COLOR_TEXT_WHITE);
            RECT rcTitle = { 105, 30, 440, 65 };
            DrawTextW(hdc, L"CloudStream Desktop", -1, &rcTitle, DT_LEFT | DT_SINGLELINE);

            // Draw Subtitle
            SelectObject(hdc, g_hFontSub);
            SetTextColor(hdc, COLOR_TEXT_MUTED);
            RECT rcSub = { 105, 62, 440, 100 };
            DrawTextW(hdc, L"Windows 10/11 Hızlı Kurulum Sihirbazı", -1, &rcSub, DT_LEFT | DT_SINGLELINE);

            // Draw Info Card
            RECT rcCard = { 35, 110, 445, 175 };
            HBRUSH hCardBrush = CreateSolidBrush(COLOR_CARD);
            FillRect(hdc, &rcCard, hCardBrush);
            DeleteObject(hCardBrush);

            SelectObject(hdc, g_hFontSub);
            SetTextColor(hdc, COLOR_TEXT_WHITE);
            RECT rcCardText1 = { 50, 122, 430, 142 };
            DrawTextW(hdc, L"• Sıfır Bağımlılık (Gömülü JRE 21 ve Direct3D 11)", -1, &rcCardText1, DT_LEFT | DT_SINGLELINE);
            RECT rcCardText2 = { 50, 144, 430, 164 };
            DrawTextW(hdc, L"• Yönetici izni gerektirmez, doğrudan kurulur.", -1, &rcCardText2, DT_LEFT | DT_SINGLELINE);

            EndPaint(hWnd, &ps);
            break;
        }

        case WM_CTLCOLORSTATIC: {
            HDC hdcStatic = (HDC)wParam;
            SetBkMode(hdcStatic, TRANSPARENT);
            SetTextColor(hdcStatic, COLOR_ACCENT);
            return (INT_PTR)g_hBgBrush;
        }

        case WM_DESTROY:
            PostQuitMessage(0);
            break;

        default:
            return DefWindowProcW(hWnd, message, wParam, lParam);
    }
    return 0;
}

int WINAPI wWinMain(HINSTANCE hInstance, HINSTANCE hPrevInstance, PWSTR pCmdLine, int nCmdShow) {
    INITCOMMONCONTROLSEX icex;
    icex.dwSize = sizeof(INITCOMMONCONTROLSEX);
    icex.dwICC = ICC_PROGRESS_CLASS;
    InitCommonControlsEx(&icex);

    g_hBgBrush = CreateSolidBrush(COLOR_BG);
    g_hAppIcon = (HICON)LoadImageW(hInstance, MAKEINTRESOURCEW(1), IMAGE_ICON, 64, 64, LR_DEFAULTCOLOR);

    g_hFontTitle = CreateFontW(22, 0, 0, 0, FW_BOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");
    g_hFontSub = CreateFontW(14, 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");
    g_hFontStatus = CreateFontW(13, 0, 0, 0, FW_SEMIBOLD, FALSE, FALSE, FALSE, DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");

    WNDCLASSEXW wcex;
    ZeroMemory(&wcex, sizeof(wcex));
    wcex.cbSize = sizeof(WNDCLASSEXW);
    wcex.style = CS_HREDRAW | CS_VREDRAW;
    wcex.lpfnWndProc = WndProc;
    wcex.hInstance = hInstance;
    wcex.hIcon = g_hAppIcon;
    wcex.hCursor = LoadCursor(NULL, IDC_ARROW);
    wcex.hbrBackground = g_hBgBrush;
    wcex.lpszClassName = L"CloudStreamSetupClass";
    RegisterClassExW(&wcex);

    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    int w = 480;
    int h = 280;
    int x = (screenW - w) / 2;
    int y = (screenH - h) / 2;

    g_hWnd = CreateWindowExW(
        WS_EX_APPWINDOW,
        L"CloudStreamSetupClass",
        L"CloudStream Desktop Kurulumu",
        WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
        x, y, w, h,
        NULL, NULL, hInstance, NULL
    );

    g_hStatusLabel = CreateWindowExW(
        0, L"STATIC", L"Başlatılıyor...",
        WS_CHILD | WS_VISIBLE | SS_LEFT,
        35, 222, 410, 20,
        g_hWnd, (HMENU)IDC_STATUS_LABEL, hInstance, NULL
    );
    SendMessageW(g_hStatusLabel, WM_SETFONT, (WPARAM)g_hFontStatus, TRUE);

    ShowWindow(g_hWnd, nCmdShow);
    UpdateWindow(g_hWnd);

    MSG msg;
    while (GetMessageW(&msg, NULL, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    DeleteObject(g_hFontTitle);
    DeleteObject(g_hFontSub);
    DeleteObject(g_hFontStatus);
    DeleteObject(g_hBgBrush);

    return (int)msg.wParam;
}
