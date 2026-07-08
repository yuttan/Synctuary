; Synctuary Server — Inno Setup script
; Builds a Windows installer for third-party testing.
;
; Prerequisites:
;   - synctuaryd.exe built in the server root (go build ./cmd/synctuaryd)
;   - Inno Setup 6 installed
;   - (optional) ffmpeg component: run fetch-ffmpeg.ps1 first to download
;     ffmpeg.exe / ffprobe.exe into deploy\windows\ffmpeg\bin\. Without it
;     the ffmpeg component is empty and this script still compiles.
;
; ffmpeg is a SEPARATE, GPL-licensed program. Synctuary does not link it;
; it is invoked as an external child process (see internal/usecase). Its
; LICENSE from the upstream build is installed alongside the binaries.
;
; Compile:
;   "C:\...\ISCC.exe" /DMyAppVersion=0.7.9 synctuary.iss

#ifndef MyAppVersion
  #define MyAppVersion "0.7.9"
#endif

#define MyAppName "Synctuary"
#define MyAppPublisher "yuttan"
#define MyAppURL "https://github.com/yuttan/Synctuary"
#define MyAppExeName "synctuaryd.exe"
#define ServerRoot "..\.."

[Setup]
AppId={{E8A3F1D2-7B4C-4E9A-A1D6-3F8C2E5B9D71}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}/issues
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=output
OutputBaseFilename=SynctuarySetup-{#MyAppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=
UninstallDisplayIcon={app}\{#MyAppExeName}
WizardStyle=modern
LicenseFile=..\..\..\LICENSE

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "japanese"; MessagesFile: "compiler:Languages\Japanese.isl"

[Types]
Name: "full"; Description: "Full installation (recommended)"
Name: "compact"; Description: "Server only (no ffmpeg)"
Name: "custom"; Description: "Custom installation"; Flags: iscustom

[Components]
Name: "core"; Description: "Synctuary Server (required)"; Types: full compact custom; Flags: fixed
Name: "ffmpeg"; Description: "Video transcoding + thumbnails (ffmpeg, GPL-licensed)"; Types: full custom

[Files]
Source: "{#ServerRoot}\synctuaryd.exe"; DestDir: "{app}"; Flags: ignoreversion; Components: core
Source: "config.yml"; DestDir: "{app}"; Flags: onlyifdoesntexist; Components: core
; Optional ffmpeg component. Sources are guarded with #ifexist so this
; script compiles even when fetch-ffmpeg.ps1 has not been run yet.
; The exes land directly in {app}\ffmpeg\ so the server's beside-exe
; resolver (resolveMediaTool) finds {app}\ffmpeg\ffmpeg.exe.
#ifexist "ffmpeg\bin\ffmpeg.exe"
Source: "ffmpeg\bin\ffmpeg.exe"; DestDir: "{app}\ffmpeg"; Flags: ignoreversion; Components: ffmpeg
Source: "ffmpeg\bin\ffprobe.exe"; DestDir: "{app}\ffmpeg"; Flags: ignoreversion; Components: ffmpeg
  #ifexist "ffmpeg\LICENSE"
Source: "ffmpeg\LICENSE"; DestDir: "{app}\ffmpeg"; DestName: "LICENSE.ffmpeg.txt"; Flags: ignoreversion; Components: ffmpeg
  #endif
  #ifexist "ffmpeg\README.txt"
Source: "ffmpeg\README.txt"; DestDir: "{app}\ffmpeg"; DestName: "README.ffmpeg.txt"; Flags: ignoreversion; Components: ffmpeg
  #endif
#else
  #pragma warning "ffmpeg not fetched — run fetch-ffmpeg.ps1 to include the ffmpeg component"
#endif

[Dirs]
Name: "{app}\data"; Permissions: users-modify; Components: core
Name: "{app}\data\files"; Permissions: users-modify; Components: core
Name: "{app}\data\staging"; Permissions: users-modify; Components: core
Name: "{app}\data\secret"; Permissions: users-modify; Components: core
Name: "{app}\data\tls"; Permissions: users-modify; Components: core

[Icons]
Name: "{group}\Start Synctuary Server"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Components: core
Name: "{group}\Open Admin Panel"; Filename: "https://localhost:8443/admin/"; Components: core
Name: "{group}\Uninstall Synctuary"; Filename: "{uninstallexe}"; Components: core
Name: "{commondesktop}\Synctuary Server"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon; Components: core

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"; Components: core

[Run]
; Firewall rules — added automatically (admin install guarantees elevation)
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Synctuary Server (TCP)"" dir=in action=allow protocol=TCP localport=8443"; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Synctuary Server (TCP)"" dir=in action=allow protocol=TCP localport=8443 profile=private"; Flags: runhidden
; Launch server after install
Filename: "{app}\{#MyAppExeName}"; Description: "Launch Synctuary Server"; Flags: nowait postinstall skipifsilent; WorkingDir: "{app}"

[UninstallRun]
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Synctuary Server (TCP)"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"
