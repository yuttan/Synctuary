; Synctuary Server — Inno Setup script
; Builds a Windows installer for third-party testing.
;
; Prerequisites:
;   - synctuaryd.exe built in the server root (go build ./cmd/synctuaryd)
;   - Inno Setup 6 installed
;
; Compile:
;   "C:\...\ISCC.exe" /DMyAppVersion=0.7.0 synctuary.iss

#ifndef MyAppVersion
  #define MyAppVersion "0.7.0"
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

[Files]
Source: "{#ServerRoot}\synctuaryd.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "config.yml"; DestDir: "{app}"; Flags: onlyifdoesntexist

[Dirs]
Name: "{app}\data"; Permissions: users-modify
Name: "{app}\data\files"; Permissions: users-modify
Name: "{app}\data\staging"; Permissions: users-modify
Name: "{app}\data\secret"; Permissions: users-modify
Name: "{app}\data\tls"; Permissions: users-modify

[Icons]
Name: "{group}\Start Synctuary Server"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{group}\Open Admin Panel"; Filename: "https://localhost:8443/admin/"
Name: "{group}\Uninstall Synctuary"; Filename: "{uninstallexe}"
Name: "{commondesktop}\Synctuary Server"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Run]
; Firewall rules — added automatically (admin install guarantees elevation)
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Synctuary Server (TCP)"" dir=in action=allow protocol=TCP localport=8443"; Flags: runhidden
Filename: "netsh"; Parameters: "advfirewall firewall add rule name=""Synctuary Server (TCP)"" dir=in action=allow protocol=TCP localport=8443 profile=private"; Flags: runhidden
; Launch server after install
Filename: "{app}\{#MyAppExeName}"; Description: "Launch Synctuary Server"; Flags: nowait postinstall skipifsilent; WorkingDir: "{app}"

[UninstallRun]
Filename: "netsh"; Parameters: "advfirewall firewall delete rule name=""Synctuary Server (TCP)"""; Flags: runhidden; RunOnceId: "RemoveFirewallRule"
