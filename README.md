# BlackTerm

A terminal emulator for **BlackBerry Passport** (BB10 Android Runtime / Android 4.3 Jelly Bean).

## Features

- **Full Terminal Emulator** — VT100/ANSI escape sequence support with interactive shell
- **SSH Server** — Enable remote shell access from any SSH client
- **FTP Server** — Transfer files to/from your BlackBerry Passport
- **Customizable UI** — Change colors, fonts, and terminal dimensions
- **Bottom Button Bar** — Quick access to Tab, Escape, Shift, and Caps Lock
- **Burger Menu** — New session, toggle keyboard, paste, settings access
- **BB Passport Optimized** — Designed for the square 1440×1440 display

## Screenshots

*Coming soon*

## Building

### Prerequisites

- Android SDK with API Level 18 (Android 4.3)
- Gradle 7.5+
- Java JDK 7+

### Build

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Install on BlackBerry Passport

1. Enable **Development Mode** on your BB10 device
2. Transfer the APK to the device
3. Install via file manager or use:
   ```bash
   adb install app-debug.apk
   ```

## SSH Server

Enable from **Settings → SSH Server**:

- Default port: `8022`
- Default username: `admin`
- Default password: `blackterm`

Connect from another device:
```bash
ssh admin@<device-ip> -p 8022
```

## FTP Server

Enable from **Settings → FTP Server**:

- Default port: `2121`
- Default username: `admin`
- Default password: `blackterm`

Connect with any FTP client:
```
ftp://<device-ip>:2121
```

## Settings

| Category | Options |
|---|---|
| **Appearance** | Background color, foreground color |
| **Font** | Font size (10-24sp), font family |
| **SSH Server** | Enable/disable, port, username, password |
| **FTP Server** | Enable/disable, port, username, password |
| **Advanced** | Terminal columns/rows, scrollback lines, keep screen on, vibrate on key |

## Bottom Button Bar

| Button | Function |
|---|---|
| ☰ | Burger menu (New Session, Keyboard, Paste, Settings, About) |
| TAB | Send Tab character |
| ESC | Send Escape character |
| SHIFT | Toggle shift modifier (one-shot) |
| CAPS | Toggle Caps Lock |

## Architecture

```
com.qnxcraft.blackterm/
├── BlackTermApp.java          # Application class
├── TerminalActivity.java      # Main terminal UI
├── SettingsActivity.java      # Preferences screen
├── terminal/
│   ├── TerminalEmulator.java  # VT100/ANSI terminal engine
│   └── TerminalView.java      # Custom rendering view
└── service/
    ├── SshServerService.java  # SSH server
    ├── FtpServerService.java  # FTP server
    └── TerminalService.java   # Background service
```

## Compatibility

- **Target Platform**: BlackBerry Passport (SQW100-1/3)
- **OS**: BlackBerry 10.3.x with Android Runtime
- **Android API**: Level 18 (Android 4.3 Jelly Bean)
- **Display**: Optimized for 1440×1440 square display
- **Keyboard**: Full support for BB Passport physical keyboard

## License

[MIT License](LICENSE)

## Repository

[github.com/QNXcraft/BlackTerm](https://github.com/QNXcraft/BlackTerm)
